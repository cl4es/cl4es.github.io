---
layout: post
title: "Investigating startup with bytestacks"
author: "cl4es"
tags:
- java 
---

In my [previous](https://cl4es.github.io/2018/11/20/A-Story-About-Starting-Up.html) blog post I mentioned [bytestacks](https://github.com/cl4es/bytestacks), and I've been asked to demonstrate how I use it to investigate startup behavior. 

### Getting started

Bytestacks is a tiny tool I wrote to parse the output of `-XX:+TraceBytecodes` into a format which can be consumed by Brendan Gregg's excellent [FlameGraph](https://github.com/brendangregg/FlameGraph) tool, along with some minimal script wrapping to tie it all together. 

To use it I'll need a debug build of the JDK. For this demonstration I want to try optimizing something in the JDK, so I'll build my own:

```
hg clone http://hg.openjdk.java.net/jdk/jdk
cd jdk
bash configure --with-debug-level=fastdebug --with-boot-jdk=/path/to/jdk/11
make jdk-image
```

Your system might need some other preparations, so I'll refer to the official build documentation: [docs/building.html](http://hg.openjdk.java.net/jdk/jdk/raw-file/tip/doc/building.html#native-debug-symbols).

### Finding something to investigate

Let's begin with some simple program we'd like to start a bit faster. I tire of looking at the standard "Hello World!", so let's spice it up a notch:

```java
import java.util.stream.*;

public class HelloJoin {
  public static void main(String... args) {
    System.out.println("Your arguments:");
    System.out.println(Stream.of(args).collect(Collectors.joining(", ")));
  }
}
```

You _didn't_ bring any arguments? No matter, for our purposes we don't really need any:

```
$JAVA_HOME/bin/javac HelloJoin.java
$JAVA_HOME/bin/java -XX:+TraceBytecodes HelloJoin > hellojoin.base
```

This takes a few seconds on even the smallest programs, so be patient if you're attempting it on something... *real*.

If you open `hellojoin.base` you'd see there's _a lot_ of output, and it always starts with something like this:

```
[5158] static void java.lang.Object.<clinit>()
[5158]        1     0  invokestatic 19 <java/lang/Object.registerNatives()V> 
[5158]        2     3  return
```

As you might guess: yes, this logs every bytecode that the JVM interprets for the entire runtime of the program. And the first thing the interpreter does is initializing the `Object` class. You can spend hours sifting through this!

But that gets old fast. 

What bytestacks does is parse this output, build up the call stacks, then keeps a running score on how often we execute a bytecode for each unique stack. Let's try it!

```
# setup
git clone https://github.com/cl4es/bytestacks.git
(cd bytestacks; ./gradlew build)

# run
./bytestacks/bytestacks hellojoin.base
```

Running the above generates two files: `hellojoin.base.stacks` (which is the result of transforming from the raw bytecodes log to a stack format; this is usable as input to the various FlameGraph tools), and the resulting flame graph SVG image, `hellojoin.base.svg`:

[<img src="/images/hellojoin.base.svg" alt="FlameGraph of running HelloJoin" />](/images/hellojoin.base.svg)

(Open the image in a separate tab to interact, search for and zoom in on things...)

A lot of stuff can be discerned from a flame graph like this, but it can easily get a bit overwhelming. So let's zoom in on one of the obvious heavy hitters.

One such thing is the call to `Collectors.joining` at `302,783` out of `884,288` samples - or rather: executed bytecodes. Bytestacks doesn't attempt to weight different bytecodes based on how expensive they are in reality. That'd be hard to get right: loads can be very expensive, but can also read mostly from caches. This is a diagnostic tool - not a profiler - so instead of guessing each "sample" is exactly one executed bytecode.

Zooming in on `Collectors.joining()` we see that we're spending almost all of that "time" in `java.lang.invoke`, effectively setting up a few lambdas. Looking at the JDK source for the library call this is not so surprising:

```java
    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter,
                                                             CharSequence prefix,
                                                             CharSequence suffix) {
        return new CollectorImpl<>(
                () -> new StringJoiner(delimiter, prefix, suffix),
                StringJoiner::add, StringJoiner::merge,
                StringJoiner::toString, CH_NOID);
    }
```

A lot to unpack here: there's `() -> new StringJoiner(delimiter, prefix, suffix)` followed by a few simple method references, e.g., `StringJoiner::add`. Each of these are in practice a lambda. 

While the three method references are lambdas of the non-capturing kind, the first lambda captures the three arguments given to it at setup time for later evaluation. Ever wondered how...?

Simple! 

First, regardless of if a lambda is capturing or non-capturing, the runtime will load or generate some code which when invoked gives back an instance of the functional interface expected. In this case a `Supplier<?>`. What functional interface to implement is inferred by `javac` at compile time and kept around as metadata in the compiled class. The classes implementing these functional interfaces are referred to as lambda proxy classes.

Spinning up these lambda proxy classes happens a bit higher up in that flame at `InnerClassLambdaMetafactory.spinInnerClass`. Once the proxy class has been spun up, we get hold of a `MethodHandle` that produce instances of said functional interface. This *could* be a handle to a constructor. This handle is then wrapped in a `CallSite`, which the VM knows how to magically installs in lieu of the `invokedynamic` call so that subsequent visits to the same method won't have to do all this _again_.

Ahem...

There's _a lot_ more to say about the inner workings of lambdas and their translation at compile- and runtime. For most intents and purposes [this write-up](https://cr.openjdk.java.net/~briangoetz/lambda/lambda-translation.html) by Brian Goetz still holds, but there are a few developments in the pipeline.

The gist of it is that for a capturing lambda the `MethodHandle` returned is likely a handle to a constructor taking the captured values as arguments, and then there's code generated in the proxy class to get those captured values and send them on to the translated method that in the end will do `new StringJoiner(delimiter, prefix, suffix)`.

Quite the ceremony!

### Setting up an experiment

What if we could have only one argument to capture? One simple experiment would be creating the `StringJoiner` up front and then only capture that:

```diff
diff -r c9325aa887da src/java.base/share/classes/java/util/stream/Collectors.java
--- a/src/java.base/share/classes/java/util/stream/Collectors.java  Fri Nov 23 10:57:07 2018 +0100
+++ b/src/java.base/share/classes/java/util/stream/Collectors.java  Fri Nov 23 17:06:51 2018 +0100
@@ -399,8 +399,9 @@
     public static Collector<CharSequence, ?, String> joining(CharSequence delimiter,
                                                              CharSequence prefix,
                                                              CharSequence suffix) {
+        StringJoiner joiner = new StringJoiner(delimiter, prefix, suffix);
         return new CollectorImpl<>(
-                () -> new StringJoiner(delimiter, prefix, suffix),
+                () -> joiner,
                 StringJoiner::add, StringJoiner::merge,
                 StringJoiner::toString, CH_NOID);
     }
```

Maybe that is a bit awkward semantically, but let's just go with it. Applying this patch and tracing `HelloJoin` again:

```
$JAVA_HOME/bin/java -XX:+TraceBytecodes HelloJoin > hellojoin.test
./bytestacks/bytestacks hellojoin.test
```

[<img src="/images/hellojoin.test.svg" alt="FlameGraph of running HelloJoin with patch applied" />](/images/hellojoin.test.svg)

Unsurprisingly, it looks pretty much the same. But there are some differences if we look closely... zooming in on `Collectors.joining` we see that there's been a drop from `302,783` to `282,291`. That's almost a 7% reduction. 

Using the `difffolded.pl` script that comes with FlameGraph we can generate a differential view to help see this:
 
```
bytestacks/FlameGraph/difffolded.pl hellojoin.base.stacks hellojoin.test.stacks | bytestacks/FlameGraph/flamegraph.pl > hellojoin.diff.svg
```

[<img src="/images/hellojoin.diff.svg" alt="Differential FlameGraph" />](/images/hellojoin.diff.svg)

Blue zones are where we see a reduction in code executed, and red is where we saw some increases.

There's a lot of natural variance, even in a diagnostic like this. You can see this due some random movements in code we didn't even touch in this experiment. Main reason is small differences in the timing of JIT operations, since JITted code will not be traced by `-XX:+TraceBytecodes`. One can resort to testing with interpreter only (`-Xint`) if things are inconclusive, but that can add orders of magnitude to the time it takes to run these experiments. 

Let's not do that, since in the part of the code that we actually did change, we're almost entirely in the blue. That's good!

### Verifying results

Seeing an improvement with bytestacks does *not* verify that an experiment has a real, measurable impact. Getting rid of 20k bytecode might measure up to some real improvement, or it might get lost in the noise. Based on experience 10k can be anything from nothing up to around a millisecond or so, but it very much depends. 

To verify if there's a real change I'll produce product builds with and without the patch, and run something that measures actual time spent running this little program. Preferably many, many times, to account for random noise, interrupts etc.

On Linux, `perf stat` has become my tool of choice for startup evaluation. It might be a a bit heavy-handed, but it collects some useful information for "free":

```
perf stat -r 200 $JAVA_HOME/bin/java HelloJoin > /dev/null
```

Baseline numbers:
```
        141.503704      task-clock (msec)         #    1.690 CPUs utilized            ( +-  1.18% )
               350      context-switches          #    0.002 M/sec                    ( +-  0.53% )
                34      cpu-migrations            #    0.241 K/sec                    ( +-  0.65% )
             3,843      page-faults               #    0.027 M/sec                    ( +-  0.15% )
       330,165,611      cycles                    #    2.333 GHz                      ( +-  1.07% )
       197,476,839      instructions              #    0.60  insns per cycle          ( +-  0.66% )
        40,279,502      branches                  #  284.653 M/sec                    ( +-  0.92% )
         1,275,494      branch-misses             #    3.17% of all branches          ( +-  0.12% )

       0.083715530 seconds time elapsed                                          ( +-  0.75% )
```

Experiment:
```
        136.547480      task-clock (msec)         #    1.654 CPUs utilized            ( +-  1.01% )
               349      context-switches          #    0.003 M/sec                    ( +-  0.56% )
                34      cpu-migrations            #    0.248 K/sec                    ( +-  0.56% )
             3,842      page-faults               #    0.028 M/sec                    ( +-  0.13% )
       320,809,576      cycles                    #    2.349 GHz                      ( +-  1.04% )
       192,172,929      instructions              #    0.60  insns per cycle          ( +-  0.62% )
        39,080,214      branches                  #  286.202 M/sec                    ( +-  0.86% )
         1,250,386      branch-misses             #    3.20% of all branches          ( +-  0.12% )

       0.082562058 seconds time elapsed                                          ( +-  0.58% )
```

From 83.7ms to 82.6ms on average. Not much, but the improvement is visible on various counters and reproducible enough.

Is it enough to actually care about, though?

Well, I've filed RFEs for smaller improvements, but mainly for things that would be executed unconditionally during bootstrap. As this is library code the bar might be a bit higher. And perhaps there are _semantic_ difficulties with creating the `StringJoiner` eagerly.

Hmm...

We *could* specialize for the single-argument case: `() -> StringJoiner(delimiter)`, but then we'd generate two different proxy classes for the two different capturing `Collectors.joining` methods.

Maybe _that_ wouldn't be too bad.

Or maybe there's a better way to speed this up that would have wider implications and we should try to generalize things.

Either way: these are all questions that typically follows once you've found an inefficiency like this, so I hope this was a successful demonstration.
