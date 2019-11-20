---
layout: post
title: "OpenJDK Startup - Late 2019 Edition"
author: "cl4es"
image: /images/hello14.png#
tags:
- java
---

With a couple of weeks left until development work on OpenJDK 14 starts to ramp down, I figured it's time to take a fresh look at what's been going on in the OpenJDK to make java and friends start up faster, use less memory et.c.

### Hello World(s)

I've previously blogged about improvements to Hello World from [java 8 through 11](https://cl4es.github.io/2018/11/29/OpenJDK-Startup-From-8-Through-11.html), [java 8 through 12](https://cl4es.github.io/2018/12/28/Preview-OpenJDK-12-Startup.html), so I guess
it's only fair to start off with a refreshed look at where we're at. I'll leave out JDK 10 and 12, since they have been superseded by 11 (LTS) and 13, respectively. I'll keep JDK 9 around, since although it's also been superseded it's also the last major release and a midpoint between JDK 8 and the rest. 

Without any tuning or preparation of the JDKs, this is the startup numbers I get today for the Hello World, Hello Lambda and Hello Concat samples on my machine:

<img src="/images/hello14.png" alt="Hello World, Lambda and Concat numbers from 8-14"/>

(8 is 8u231, 9 is 9.0.4, 11 is 11.0.5, 13 is 13.0.1, 14 is the latest build off of jdk/jdk as of today)

Overall we're down dramatically compared to the historic high for each "app": more than twice as fast on Hello World, almost four times faster for Hello Lambda..

JDK 14 *is* on target to be a small improvement over JDK 13 on these minimal startup tests, even though the relative improvement is tiny in comparison. While we have done a number of cleanups and improvements, there's no denying that the incremental improvement here is small in both relative and absolute terms. But that's OK, since JDK 13 is _really_ good.

If the numbers skew a bit from my earlier posts it's because I've not primed the JDKs with a CDS archive by running `java -Xshare:dump` in this experiment. This was a trick few knew or cared about, and which we since JDK 12 no longer _have_ to care about thanks to [Default CDS Archives](https://openjdk.java.net/jeps/341) being a thing. If you're really chasing milliseconds there are ways to tune and improve, but overall our goal is to improve the default experience. 

### Scaling up

Now, just because `Hello World` runs in _37.9ms_ or whatever, that doesn't mean java will start up and run _any_ application fast. Simplifying those early bootstrap overheads is _great_, but will disappear into the noise when loading that big app server or even that new and shiny microservice framework thing.

To exemplify, I downloaded the [Micronaut Hello World example](https://github.com/micronaut-projects/micronaut-examples/tree/master/hello-world-java), [modified it slightly](/snippets/micronaut.patch) to not turn off verification and disable most JIT compilation (no cheats!) and add a shutdown hook.

Run that in a loop a few times, take the average:

<img src="/images/micronaut14.png" alt="Micronaut startup timings" />

We went from somewhere around 2.72s on JDK 8u231, up to 2.96s on JDK 9.0.4, down to around 2.35s on JDK 14. Nothing dramatic, but a 14-15% reduction on the aggregate since JDK 8, and also an improvement relative JDK 13. 

Perhaps more surprising is that total memory used by the JVM has been dropping, too:

<img src="/images/micronaut14-footprint.png" alt="Micronaut max memory usage" />

Yes: _40% less memory used since JDK 8!_ Looking at various hardware counters we see similar improvements: we're down *40% total CPU* used compared to JDK 9 etc. 

We can of course tune this down even further. Just applying AppCDS and jaotc I got it down to about 1.49s using 138Mb. There are other ways of tuning this down further, or you can use the GraalVM native-image tool and get it down to tens of milliseconds on an even smaller footprint.

But the point here is you can get a substantial startup and footprint improvements just by upgrading to 13 (and 14, in due time) without _any_ tuning at all!

While bootstrap improvements etc are helping here, there's more to the story here. Let's break it down a bit...

#### Class loading improvements

One obvious cause for larger applications taking seconds to start are the overheads related to class loading, linking and bytecode verification. When loading and linking classes the runtime might also spend time generating bytecode behind the scenes. 

Using [AppCDS](https://blog.codefx.org/java/application-class-data-sharing/) or [Dynamic CDS](https://openjdk.java.net/jeps/350) can often reduce startup time by 20-50%. Not everyone will end up deploying App- or Dynamic CDS, and even if they did the sweet spot might not be to include _everything_ your app might ever use.

So while there's good work being done to make CDS even better, there's also a good case to optimize class loading etc in the _absense_ of CDS. And there's been plenty of improvements:

- On micronaut, default method generation at link time could take around 800-850M instructions in JDK 8 and 9, well over 0.3s on my machine. Two different improvements in 13 ([JDK-8219713](https://bugs.openjdk.java.net/browse/JDK-8219713)) and 14 ([JDK-8233497](https://bugs.openjdk.java.net/browse/JDK-8233497)) this is down to ~250M instructions, or about 0.1s
- Bytecode verification speedups due improvements such as [JDK-8219579](https://bugs.openjdk.java.net/browse/JDK-8219579)
- Many small improvements to reduce work, such as removing implicit conversion between `Method*`s and `MethodHandle`s ([JDK-8233913](https://bugs.openjdk.java.net/browse/JDK-8233913)).

#### Compiler improvements

The other main contributor to the startup time of a JVM-based application are the direct and indirect effects of JIT compilation. When starting up, the JVM interpret bytecode, while spinning up JIT compiler threads in the background with the sole goal of optimizing the bytecode your application seem to be spending most time in. 

By default, the OpenJDK JVM, HotSpot, has a tiered configuration where bytecode is first compiled by a fast compiler, C1, into a form that is faster than interpreting bytecode, but also has a lot of profiling counters that help the next tier JIT compiler to optimize as aggressively as possible. By default the next JIT compiler used is C2, which is slower than C1 but generates code that is often many times faster.

One reason we're spending less resources early is because we don't spin up JIT threads as aggressively as before, due the introduction in JDK 11 of `-XX:+UseDynamicNumberOfCompilerThreads` ([JDK-8198756](https://bugs.openjdk.java.net/browse/JDK-8198756)). We'll only start up more C1 and C2 threads if compilation requests start to queue up faster the JVM can handle, which overall means we run a smaller number of threads during startup. 

We've also done a number of things to [optimize the C1 and C2 compilers](https://bugs.openjdk.java.net/issues/?jql=labels%20in%20(startup)%20and%20subcomponent%20%3D%20compiler%20and%20status%20in%20(Resolved)%20and%20fixVersion%20in%20(9%2C%2010%2C%2011%2C%2012%2C%2013%2C%2014)) themselves, ranging from improvements to use optimized hardware instructions when possible, to reducing allocations in C1. Effect of all this varies between platforms, but I've seen significant speed-ups, which combined with the now more dynamic nature of how many compilation threads we use definitely translate to overall less resource consumption.

### What's next? 

Who knows... :-) 

I'll continue paying close attention to how well OpenJDK performs during startup/warmup, regardless of whether it's for highly tuned and specialized use cases, or just to run those builds and tests a bit faster.

### Moar!

I did a talk on some of this and some related stuff recently:

<blockquote class="twitter-tweet"><p lang="in" dir="ltr">.<a href="https://twitter.com/javazone?ref_src=twsrc%5Etfw">@javazone</a> The lean, mean... OpenJDK? <a href="https://t.co/jdPoQ2jJrH">https://t.co/jdPoQ2jJrH</a></p>&mdash; Claes Redestad (@cl4es) <a href="https://twitter.com/cl4es/status/1172147472501751812?ref_src=twsrc%5Etfw">September 12, 2019</a></blockquote> <script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script> 

Slides [here](http://cr.openjdk.java.net/~redestad/slides/lean_mean_openjdk.pdf).

