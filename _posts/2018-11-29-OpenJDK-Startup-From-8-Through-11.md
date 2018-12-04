---
layout: post
title: "OpenJDK Startup From 8 Through 11"
author: "cl4es"
tags:
- java
---

I've touched on regressions and improvements to Java startup in both talks and in an [earlier entry](/2018/11/20/A-Story-About-Starting-Up.html) in my blog. I got some nice feedback along with requests for ever more details.

So here's a quick summary:

<img src="/images/hellolambda.png" alt="HelloWorld and HelloWorld numbers from 8-11"/>

### JDK 9

- The Java module system brings both startup improvements and regressions
  - On the simplest of Hello World programs there's a bit of added up front cost
  - Slightly faster class loading overall: the JVM knows which packages are in which module, so delegating through a hierarchy of class loaders is minimized. This indirectly helps classes loaded from the class path, too.
  - `jlink` has a few built-in plugins to move some validation and code generation (around lambdas) from runtime to link time, including the bulk of an effort to reduce lambda startup overheads. As is visible in the graph above the improvements to HelloLambda outweigh the regression in HelloWorld.

- G1 replaced ParallelGC as the default GC
  - A few more threads created early on, a little added cost due increased complexity...
  - G1 _can_ cause a slightly longer delay in _shutting down_ the JVM. This behavior was [_greatly improved_](https://bugs.openjdk.java.net/browse/JDK-8136854) in JDK 9. Still, even with several other startup, scalability and performance improvements to G1 during development of JDK 9, naïve startup measurements can see a bit longer total time spent attributable to the use of G1 compared to ParallelGC

### JDK 10

- [JDK-8185853](https://bugs.openjdk.java.net/browse/JDK-8185853): "Generate readability graph at link time and other startup improvements" 
  - The single biggest improvement in JDK 10 - meant the amount of java code we execute during startup fell considerably

- Fixed a few HotSpot regressions that snuck into JDK 9:
  - [Segmented code cache](https://openjdk.java.net/jeps/197) and [JVMCI](https://openjdk.java.net/jeps/243) slowed down a couple of methods that are hot during class loading. Several of these regressions were fixed in JDK 10 ([JDK-8183001](https://bugs.openjdk.java.net/browse/JDK-8183001), [JDK-8180763](https://bugs.openjdk.java.net/browse/JDK-8180763))
  - A feature to better [Validate JVM Command-Line Flag Arguments](https://openjdk.java.net/jeps/245) caused a surpising amount of computation during VM setup (Fixed by [JDK-8180614](https://bugs.openjdk.java.net/browse/JDK-8180614) in JDK 10)

- AppCDS open sourced!
  - Using AppCDS can reduce the startup time of an application by 25-40%
  - (App)CDS has been continually improved since to be able to include more and more things that the JVM would otherwise have to calculate at runtime - and more and more of the data included in the CDS archive is read-only in a way that allows it to be mapped in directly. This facilitates sharing between processes, enabling footprint wins. Up until JDK 11 this includes String constants. JDK 12 adds support for more generic read-only Java objects to be mapped in via shared archives.

- [JDK-8146115](https://bugs.openjdk.java.net/browse/JDK-8146115): Improve docker container detection and resource configuration usage
  - While not strictly a startup improvement, being a better citizen when resources are limited can lead to much smoother behavior overall, including startup.

- ~25 other startup enhancements

### JDK 11

- [JDK-8198418](https://bugs.openjdk.java.net/browse/JDK-8198418): Invoke LambdaMetafactory::metafactory exactly from the BootstrapMethodInvoker
  - Carves a large chunk off of the cost for bootstrapping lambdas.
  - Similar trick applied to help bootstrap [Indyfied String concatenation](https://openjdk.java.net/jeps/280) a bit faster. This JDK 9 feature trades a bit of startup overhead for better peak performance, but hopefully we can further reduce the startup overhead. (I've mentioned elsewhere that JDK 12 is set to reduce the startup overheads of ISC by more than half...)

- `-XX:+UseDynamicNumberOfGCThreads` and `-XX:+UseDynamicNumberOfCompilerThreads` by default, which scales up the JVMs use of resources for background GC and JIT activity more gracefully
 
- 27 other startup enhancements


### TL;DR

Startup regressed on some measures in JDK 9, but we fixed most of that. On many deployments and systems JDK 11 will get up and running faster than JDK 8 without any effort. If you're heavily using lambdas and other new and advanced features, JDK 11 is likely to pull further ahead.
