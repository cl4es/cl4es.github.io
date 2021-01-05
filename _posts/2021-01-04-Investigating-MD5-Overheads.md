---
layout: post
title: "Investigating MD5 overheads"
author: "cl4es"
tags:
- java
---

* TOC
{:toc}

## Background 

A few weeks ago we received a [PR](https://github.com/openjdk/jdk/pull/1821) intending to speed up `java.util.UUID::nameUUIDFromBytes` by caching the return from `MessageDigest.getInstance("MD5")` in a static field . On the face of it, this seemed like a nice little improvement. But that was before realizing that `MessageDigest` objects are stateful and thus can't be shared statically without some synchronization mechanism.

To fix this correctness issue the PR was updated to use a `ThreadLocal`, which comes with its own set of issues: 

- Every thread that has ever called `UUID::nameUUIDFromBytes` would indefinitely hold on to an instance of the MD5 `MessageDigest` object. (This could be partially resolved by wrapping the object stored in the `ThreadLocal` in a `SoftReference`, at the cost of complexity and some speed...)
- `ThreadLocals` might not play at all nicely with [Project Loom](https://openjdk.java.net/projects/loom/). In fact, the OpenJDK team has deliberately gotten rid of a few dubious `ThreadLocal` optimizations for this reason.

Shooting down or flat out rejecting a PR is no fun, though. And it definitely seems like there is room for improvement here. In this post I'll explore some of that profiling and prototyping work to find alternative optimizations we could consider. Maybe this can inspire even better suggestions.

I've [drafted a PR](https://github.com/openjdk/jdk/pull/1855) with the current best performing mix of improvements, but let's start from the beginning...

### It's 2021 - why bother with MD5?

[MD5](https://tools.ietf.org/html/rfc1321) is a cryptographic hashing function used for more things beside the `UUID` method we're looking at here.

I'm no cryptography expert but I know that MD5 is considered cryptographically broken and susceptible to collision attacks. So while it's probably never a good idea to rely on MD5 hashing for anything security sensitive it's a very fast hash that remain a popular choice for integrity checking.

As it happens the MD5 implementation in the OpenJDK was recently [optimized](https://bugs.openjdk.java.net/browse/JDK-8250902) by means of intrinsifying the bulk of the algorithm. Only on x86 initially, but implementations on other architectures seem to be coming. Intrinsification is a fun but complicated (and sometimes [surprising](https://alidg.me/blog/2020/12/10/hotspot-intrinsics)) technique where the JVM replaces some Java code with hand-optimized versions during compilation. This recent optimization, which will be added in JDK 16, allegedly improves performance of doing MD5 digests by 15-20%.

## Basic performance analysis

Before even attempting to optimize anything, it's important to first try to understand the performance of what you're trying to optimize. We could zoom in on `MessageDigest.getInstance("MD5")` directly - or why not the public API method the original PR sought to optimize: `java.util.UUID.nameUUIDFromBytes`. There's no given answer exactly where to start examining, but drilling down into the details from a somewhat high level API extracted from a real world use case will often lead you in the right direction.

### Pick (or create) a microbenchmark

Consider this method in `java.util.UUID`:

```java
    public static UUID nameUUIDFromBytes(byte[] name) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException nsae) {
            throw new InternalError("MD5 not supported", nsae);
        }
        byte[] md5Bytes = md.digest(name);
        md5Bytes[6]  &= 0x0f;  /* clear version        */
        md5Bytes[6]  |= 0x30;  /* set to version 3     */
        md5Bytes[8]  &= 0x3f;  /* clear variant        */
        md5Bytes[8]  |= 0x80;  /* set to IETF variant  */
        return new UUID(md5Bytes);
    }
```

This certainly seem like a gateway to examining the overheads of `MessageDigest.getInstance` and `digest`, as well as seeing how these overheads stack up against the creation of the `UUID` object itself. Which struck me as a good starting point to allow us to get a feel for how much each part contributes.

It so happens there already is a `UUID`-related microbenchmark in the OpenJDK sources, so why not [extend](https://github.com/openjdk/jdk/pull/1855/files#diff-fcb56f7dd0c6f7fc1bdc199e3b41d492eba49b20fe198d898b97ca23024a9e9dR82) that. Let's keep it simple: generate some random `byte[]s` at setup and call the public method:

```java
    @Benchmark
    public UUID fromType3Bytes() {
        return UUID.nameUUIDFromBytes(uuidBytes[index]);
    }
```

After [setting up](https://github.com/openjdk/jdk/blob/master/doc/testing.md#configuration) the OpenJDK for building and running JMH, running this will execute that microbenchmark: `make test TEST=micro:UUIDBench.fromType3Bytes`.

```
Benchmark         Score    Error   Units
fromType3Bytes    1.460 ±  0.089  ops/us
```

### Run some quick diagnostics

Many of the typical JMH parameters are controllable via the `make`-based [runner](https://github.com/openjdk/jdk/blob/master/doc/testing.md#microbenchmark-keywords), including a catch-all `MICRO=OPTIONS="..."`, but you can also just build the microbenchmarks bundle with `make build-microbenchmark` and run them directly via `java -jar build/<platform>/images/test/micro/benchmarks.jar`

Running with the JMH GC profiler, `-prof gc`, is my go-to first quick check. It gives rundown on how much allocations we're doing every call:

```
Benchmark                Score    Error   Units
·gc.alloc.rate.norm    488.042 ±  0.005    B/op
```

488 bytes per call? That's quite a bit of allocations for something so innocuous.

### Pick your profiler

Since version 1.24 JMH includes support for [async-profiler](https://github.com/jvm-profiling-tools/async-profiler) (needs to be installed separately). A very powerful profiler which now works nicely together with JMH. The ability to generate CPU flame graphs is nice and easy to use:

```
make test TEST=micro:UUIDB.from MICRO=OPTIONS="-prof async:output=flamegraph"
# generates flame-cpu-forward|reverse.svg in a folder named after the micro
```

There's also an allocation profiler/sampler, which I decided to toy with for this analysis. No better reason than wanting to play with something that's new to me:

```
make test TEST=micro:UUIDB.from MICRO=OPTIONS="-prof async:event=alloc"
...

--- 1291138320 bytes (24.90%), 923 samples
  [ 0] int[]
  [ 1] sun.security.provider.MD5.<init>
  [ 2] jdk.internal.reflect.GeneratedConstructorAccessor1.newInstance

--- 1287651328 bytes (24.83%), 921 samples
  [ 0] byte[]
  [ 1] sun.security.provider.DigestBase.<init>
  [ 2] sun.security.provider.MD5.<init>
  [ 3] jdk.internal.reflect.GeneratedConstructorAccessor1.newInstance

--- 645300224 bytes (12.44%), 310 samples
  [ 0] byte[]
  [ 1] sun.security.provider.DigestBase.engineDigest
  [ 2] java.security.MessageDigest$Delegate.engineDigest

--- 640256000 bytes (12.35%), 610 samples
  [ 0] java.util.UUID
  [ 1] java.util.UUID.nameUUIDFromBytes
  [ 2] org.openjdk.bench.java.util.UUIDBench.fromType3Bytes

--- 640251904 bytes (12.35%), 609 samples
  [ 0] java.lang.ref.WeakReference
  [ 1] java.lang.reflect.AccessibleObject.slowVerifyAccess
  [ 2] java.lang.reflect.AccessibleObject.verifyAccess
  [ 4] java.lang.reflect.Constructor.newInstanceWithCaller
  [ 5] java.lang.reflect.Constructor.newInstance
  [ 6] java.security.Provider.newInstanceUtil

--- 639006720 bytes (12.32%), 305 samples
  [ 0] java.lang.Object[]
  [ 1] java.security.Provider.newInstanceUtil
  [ 2] java.security.Provider$Service.newInstance
```

I've trimmed some of the output. It took me a moment to realize the output listed are all reversed stack traces of the hottest allocation sites. And that the current JMH integration does not seem to support generating flamegraphs from alloc events. Alas! This is still pretty cool since it quickly points me to some potential areas of concern...

The first two stack traces pertain to the instantiation of the `sun.security.provider.MD5` instance - and represent just 
about half of the allocations. Seems like a good place to focus our search for improvements.

Next is some `byte[]` allocated when calling digest on the `MD5`. This is likely what's being returned out to `UUID`. The `UUID` code will then unpack the `byte` values into two `long` values. I guess returning a view into the digested bytes _could_ avoid some of this, but that might be a lot of brain surgery for a dubious gain.

Next is the `UUID` allocation itself. Probably not much we can do about that. 

The next two are interesting. Both originate in `java.security.Provider::newInstanceUtil`. Allocating `WeakReferences` in a method called `slowVerifyAccess` seems suspect. And the `Object[]s` allocated in `Provider.newInstanceUtil` seem they might be empty varargs arrays? We'll get to that..

## MD5 - room for improvement?

I mentioned that code in the `MD5` implementation has recently been intrinsified. When a method is intrinsified, what you see is not necessarily what you get. And in this case something struck me when I examined the state of the current code: one of the large data structures in our allocation profiles is not used at all by the intrinsified code. Still it's always allocated, filled up and cleared.

```java
    // temporary buffer, used by implCompress()
    private int[] x; // will be cleared after a call to digest(byte[])

    void implCompress(byte[] buf, int ofs) {
        implCompressCheck(buf, ofs);
        implCompress0(buf, ofs);
    }

    private void implCompressCheck(byte[] buf, int ofs) {
        Objects.requireNonNull(buf);

        // The checks performed by the method 'b2iBig64'
        // are sufficient for the case when the method
        // 'implCompressImpl' is replaced with a compiler
        // intrinsic.
        b2iLittle64(buf, ofs, x); // fills up x
    }

    // The method 'implCompress0 seems not to use its parameters.
    // The method can, however, be replaced with a compiler intrinsic
    // that operates directly on the array 'buf' (starting from
    // offset 'ofs') and not on array 'x', therefore 'buf' and 'ofs'
    // must be passed as parameter to the method.
    @IntrinsicCandidate
    void implCompress0(byte[] buf, int ofs) {
        // regular java code uses x, but the intrinsic doesn't
    }
```

It also seems a bit wrong to have a method `implCompressCheck` whose name implies that it's just doing a few checks also have a side effect of reading values into the temporary buffer `x`. 

I've [prototyped](https://github.com/openjdk/jdk/pull/1855/files#diff-30a4459e7a46b540d576d9d00fdda9babfdcb177c90e0f39881e5197c658d3a9) a variant that refactors `implCompressCheck` to only do checking, gets rid of the temporary buffer and moves the reading of values from the buffer into `implCompress0` - which avoid redundantly reading values twice. This turns out to be beneficial in the `UUID` micro:

```
Benchmark                    Score    Error   Units
fromType3Bytes               1.523 ±  0.066  ops/us
fromType3Bytes:·gc.norm    408.036 ±  0.003    B/op
```

80 B/op reduction over our 488 B/op baseline, and maybe a small throughput win. If we zoom in on the `digest` method, for which there's another microbenchmark we see that the improvement there can be rather pronounced:

```
Benchmark                   Score     Error   Units
MessageDigests.digest    2719.049 ±  30.538  ops/ms
MessageDigests.digest    3241.352 ±  67.353  ops/ms

(digesterName: md5, length: 16, provider: DEFAULT)
```

The effect of this optimization diminish on larger inputs - down to getting lost in the noise on digests over a few Kbs. This suggests the work done in `implCompressCheck` - which would scale with the input length - is successfully [DCE'd](https://en.wikipedia.org/wiki/Dead_code_elimination) by the JIT, while the pointless allocation and clearing of the `x` buffer can't be eliminated.

### Handling conversion from bytes to ints

If you've looked at the source code for the last experiment you might have noticed how I played around with using `VarHandles` to read `int` values from a `byte[]`. This is essentially what the existing code in `sun.security.provider.ByteArrayAccess` does, but with slightly more ceremony and use of `Unsafe`. `VarHandles` were added in JDK 9. They do have a few quirks, but I think using them can nicely encapsulate some of the tricky logic and `Unsafe` usage in the  [`ByteArrayAccess::b2iLittle64` method](https://github.com/openjdk/jdk/blob/05a764f4ffb8030d6b768f2d362c388e5aabd92d/src/java.base/share/classes/sun/security/provider/ByteArrayAccess.java#L130) that I'm "inlining" here.

Running experiments on this code with the intrinsic disabled shows that this quick and dirty `VarHandles` implementation perform just as well as `Unsafe` on my hardware. There's a bit of overhead (-30%) when running in the interpreter. This overhead was almost halved extracting `long` values rather than `ints`, without regressing higher optimization levels. But optimizing for the interpreter is typically not worth the hassle, unless you're optimizing code that is startup sensitive in some way. And if so it might be better to look at ways of improving the `VarHandle` implementation itself...?

Anyway.. if I am to finish up and propose a patch based on this experiment I want to first simplify the code as much as possible and do more thorough testing on more hardware. Revamping using more modern, non-`Unsafe` APIs is nice, but comes with some risks. It would also be interesting to try out implementing and comparing this byte-to-int conversion using other techniques, including `ByteBuffers` and the incubating Foreign Access API - as well as consolidating any change in approach with the code in the legacy implementation in `sun.security.provider.ByteArrayAccess`.

### Accidental constraints

One unfortunate side effect of the intrinsification of `MD5.implCompress0` is that now there's a HotSpot intrinsic depending on the `int[] state` array in `MD5`. This means that to try out things like flattening this array into four `ints` I'd have to rewrite some gnarly intrinsic code on all platforms they are implemented on. I'm not sure flattening this array would make much of a difference here (a bit less allocation and faster cloning) but it's a bit annoying that that's now far from trivial.

## Reflection overheads

Let's take a step back and instead zoom in on the various allocations we saw in `Provider::newInstanceUtil`. In the code that was allocating `Object[]s` to the tune of 12%(!?) of the total we find this:
```java
      Constructor<?> con = clazz.getConstructor();
      return con.newInstance();
```

For security and integrity reasons `clazz.getConstructor()` will copy out a new `Constructor` on each call, and on the first call of `newInstance` on a `Constructor` there's an expensive access check performed on every invocation.

Both methods are also vararg methods. Calling a vararg method with no arguments means `javac` generates the allocation of an empty `Object[]` and passes that to the method. This could mean optimizing this like is "meaningful":

```java
    private static final Class<?>[] EMPTY_CLZ = new Class<?>[0];
    private static final Object[] EMPTY_OBJ   = new Class<?>[0];

      Constructor<?> con = clazz.getConstructor(EMPTY_CLZ);
      return con.newInstance(EMPTY_OBJ);
```

Our JIT compilers can often optimize away such allocations. But in this case we _do_ see a tiny gain:

```
Benchmark                Score     Error   Units
fromType3Bytes           1.575 ±   0.497  ops/us
·gc.alloc.rate.norm    472.040 ±   0.029    B/op
```

-16 bytes per call. Or about 3.2% of the total. Not the ~12% the allocation profiling estimated for this method..

Since I'm running this on a 64-bit JVM with default settings this equals one less allocation of a minimally sized object. Such as an empty array. In real applications a micro-optimizations like this is likely to be a waste of time 99 times out of 10 - but in internal JDK code that might be used by an assortment of public APIs in any number of ways it _could_ very well be a reasonable thing to consider. Even so it seems we might have been led astray...

A bigger cost allocation-wise is probably that we're copying a `Constructor` from `clazz` on every call. And for calling into a default constructor there's a (deprecated) short-hand that caches the constructor: `clazz.newInstance()`:

```
Benchmark                Score     Error   Units
fromType3Bytes           1.576 ±   0.061  ops/us
·gc.alloc.rate.norm    368.031 ±   0.027    B/op
```

-120 bytes per call compared to the baseline. That's more like it! And quite possibly a decent throughput gain.

Though our documentation recommends replacing uses of `clazz.newInstance()` with
`clazz.getDeclaredConstructor().newInstance()`, it appears the latter can underperform due copying along with an expensive (and allocating!) first-time access check. Fixing this might not be trivial without pulling off some heroics in the JIT compiler so in the meantime we could be better off caching the default constructor in `Provider`. 

Using a `ClassValue` could be an efficient way of doing so while ensuring we only weakly reference classes - avoiding any leaks:

```java
    private static final ClassValue<Constructor<?>> DEFAULT_CONSTRUCTORS =
            new ClassValue<>() {
                @Override
                protected Constructor<?> computeValue(Class<?> clazz) {
                    try {
                      return clazz.getConstructor();
                    } catch (NoSuchMethodException e) {
                      throw new RuntimeException(e);
                    }
                }
            };

    private Constructor<?> getConstructor(Class<?> clazz)
            throw NoSuchMethodException {
        try {
            return DEFAULT_CONSTRUCTORS.get(clazz);
        } catch (RuntimeException re) {
            if (re.getCause() instanceof NoSuchMethodException nsme) {
                throw nsme;
            }
            throw re;
        }
    }

    ...
    Constructor<?> con = getConstructor(clazz);
    return con.newInstance(EMPTY_OBJ);
```

The need to wrap a `NoSuchMethodException` in a `RuntimeException` and then unwrap it slightly unfortunate, but this does the trick and gives a result very close to using the deprecated `clazz.newInstance()`:

```
Benchmark                Score    Error   Units
fromType3Bytes           1.575 ±  0.086  ops/us
·gc.alloc.rate.norm    368.034 ±  0.006    B/op
```

But we're still spending quite a bit of time in `Provider`. I prototyped a variant inspired by the original PR that caches the `MessageDigest` returned from `MessageDigest.getInstance("MD5")` in a holder in `UUID`. But instead of a `ThreadLocal` I clone the shared `MD` before use. This is thread-safe, relatively straightforward, and avoids any reflection and `Provider`-induced overheads on the hot path. This appears to be a marginal improvement over `clazz.newInstance()` on total allocations, but a large improvement on throughput:

```
Benchmark                Score    Error   Units
fromType3Bytes           2.018 ±  0.076  ops/us
·gc.alloc.rate.norm    344.029 ±  0.022    B/op
```

This might be the best we can do safely here, but it'd be nice to optimize this more generally and avoid tailoring our optimization efforts so specifically to `UUID`.

## Summing up

We started out with a baseline:

```
Benchmark                Score    Error   Units
fromType3Bytes           1.460 ±  0.089  ops/us
·gc.alloc.rate.norm    488.042 ±  0.005    B/op
```

Combining the idea of cloning a cached `MD5` object together with the removal of the temporary buffer `x` from `MD5` nets us a rather significant improvement over our baseline:

```
Benchmark                Score    Error   Units
fromType3Bytes           2.186 ±  0.228  ops/us # ~1.45-1.5x
·gc.alloc.rate.norm    264.023 ±  0.006    B/op # -46%
```

The `ThreadLocal` approach suggested in [PR#1821](https://github.com/openjdk/jdk/pull/1821) still wins on this 
microbenchmark - and also works together with the independent `MD5` optimization to get a bit further:

```
Benchmark                Score    Error   Units
fromType3Bytes           2.578 ±  0.060  ops/us # ~1.75x
·gc.alloc.rate.norm     64.005 ±  0.001    B/op # -87%
```

We'll probably not beat the `ThreadLocal` approach on this microbenchmark by trimming down overheads in the underlying code, but the current and upcoming disadvantages of using `ThreadLocals` in the JDK libraries means that `ThreadLocal` PR is unlikely to make it through review. So maybe it's the best we can do in a practical sense.

My intent is to clean up and get some of the experiments explored here integrated over the next few weeks as time allows. Rampdown work for JDK 16 might take precedence, though.

The ideal here would be to inch us close enough on the specific micro without making anything worse and without just resorting to point fixes.

(Happy New Year!)