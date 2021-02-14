---
layout: post
title: "Reducing MD5 (and SHA) overheads"
author: "cl4es"
tags:
- java
---

* TOC
{:toc}

### Background 

Following up on a recent post - ([Investigating MD5 Overheads](https://cl4es.github.io/2021/01/04/Investigating-MD5-Overheads.html) - where I detailed some of the analysis I did around [improving `java.util.UUID::nameUUIDFromBytes`](https://github.com/openjdk/jdk/pull/1821). As of writing that PR is still open, but we have integrated a few optimizations that improve things substantially in the vicinity.

### Optimize MessageDigest.getInstance

(... by optimizing `java.security.Provider::getService`.)

[#1933](https://github.com/openjdk/jdk/pull/1933) was the first PR to get integated as a result of the analysis I did back then.

As suggested by the prototyping and analysis I did early on, the main improvements comes from caching the `Constructor` used to instantiate the digest object. The main difference from the prototype is that instead of caching in a `ClassValue` or a `ThreadLocal`, I cache it in the `java.security.Provider$Service` object. This means there's no extra lookup or anything. And the gain gets a bit better for it.

Add in a few minor enhancements such as desugaring the `Objects.hash`. `Objects.hash` is a nice convenience, but it allocates a vararg array. Thus desugaring it _can_ improve performance, but as always make sure that it really matters first. In this particular case - slightly affecting performance of a few public JDK APIs - it seemed like a reasonable thing to do.

All in all athe allocation overhead of `MessageDigest.getInstance` is now zero. Only the `MessageDigest` itself will be allocated. Per operation this means a 144 byte reduction in allocation pressure.

Since the optimization is general for any `java.security.Provider$Service` then this not only means that `MessageDigest.getInstance` gets a speed bump - but any security service retrieved via said Provider API will improve similarly.

Too late to change the summary of the enhancement to reflect this, though...

### Shrink MD5 and SHA down to size

The [prototype](https://cl4es.github.io/2021/01/04/Investigating-MD5-Overheads.html#md5---room-for-improvement) I did to optimize `MD5` itself turned out pretty good: Get rid of the temporary array, fold the code to read data to digest into the method that will be replaced by an intrinsic.

This shrinks the instance size by a fair chunk, and by avoiding some activity that did not get optimized away by the intrinsic we get a small improvement to digest performance. Most noticeable when the data to digest is small.

So I drafted the [pull request](https://github.com/openjdk/jdk/pull/1855) ... only to soon realize the same (or at least very similar) optimization could be applied to most of the SHA implementations. The main difference is that the temporary state array to be optimized away for the SHA impls are quite a bit larger, so removing the array code altogether would be cumbersome. But moving the allocation of and use of the arrays inside the intrinsified methods means that once the method gets JIT compiled, the array will never need to be allocated.

Running the `getAndDigest` microbenchmark I added with the PR with `-prof gc` shows that this takes effect, and can reduce allocations a lot:

```
Benchmark                                 (digesterName)  (length)    Cnt     Score      Error   Units

Baseline
MessageDigests.getAndDigest:·gc.alloc.rate.norm      MD5        16     15   312.011 ±    0.005    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm    SHA-1        16     15   584.020 ±    0.006    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm  SHA-256        16     15   544.019 ±    0.016    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm  SHA-512        16     15  1056.037 ±    0.003    B/op

PR
MessageDigests.getAndDigest:·gc.alloc.rate.norm      MD5        16     15   232.009 ±    0.006    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm    SHA-1        16     15   584.021 ±    0.001    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm  SHA-256        16     15   272.012 ±    0.015    B/op
MessageDigests.getAndDigest:·gc.alloc.rate.norm  SHA-512        16     15   400.017 ±    0.019    B/op
```

(The intrinsic for SHA-1 isn't enabled by default, so no allocation reduction there.)

Together with the 144b/op allocation reduction we got from the `MessageDigest.getInstance` optimization, that means that stronger digests like `SHA-256` and `SHA-512` drop their footprint overheads by a lot: 416 and 800 bytes per operation respectively. More than half for `SHA-256` and two thirds for `SHA-512`. 

Pretty neat!

### Summing up

Going back now and running the microbenchmark for the `UUID::nameUUIDFromBytes` that I added in [PR#1855](https://github.com/openjdk/jdk/pull/1855), it's clear we've achieved a respectable gain on the specific operation that started this little adventure.

Before:
```
Benchmark                 (size)   Mode  Cnt  Score   Error   Units
UUIDBench.fromType3Bytes     128  thrpt   15  1.620 ± 0.048  ops/us
```

After:
```
Benchmark                 (size)   Mode  Cnt  Score   Error   Units
UUIDBench.fromType3Bytes     128  thrpt   15  2.309 ± 0.049  ops/us
```

Around 40-50% speed-up, depending on how large your inputs are.

By analysing and trying to find improvements deeper down in the internals we've managed to deliver patches that improve the security area in general. Translating the MD5 improvements to various SHA implementations is also a great success. Some of these improvements will hopefully add up to real gains on some hypothetical real app.

While great, those improvements doesn't necessarily preclude [caching](https://github.com/openjdk/jdk/pull/1821) of the `MD5` object in `java.util.UUID::nameUUIDFromBytes`. There's still some gains to be had on the microbenchmark by doing so:

```
Benchmark                 (size)   Mode  Cnt  Score   Error   Units
UUIDBench.fromType3Bytes     128  thrpt   15  2.632 ± 0.080  ops/us
```

However, we think adding a thread-local cache to the specific call site is not motivated. The true cost of adding 
thread-locals are hard to analyze: there might be footprint creep in large applications with many threads and there might be performance penalties if/when the cached objects are moved around by GCs (which is hard to capture properly in microbenchmarks).