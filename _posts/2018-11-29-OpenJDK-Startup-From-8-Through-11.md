---
layout: post
title: "OpenJDK Startup From 8 Through 11"
author: "cl4es"
tags:
- java
---

I've touched on regressions and improvements to Java startup in both talks and in an [earlier entry](2018-11-20-A-Story-About-Starting-Up.md) in my blog. I got some nice feedback, and also requests for more details, perhaps for inclusion in talks about OpenJDK, so this is just a quick summary of that:

<img src="/images/hellolambda.png" alt="HelloWorld and HelloWorld numbers from 8-11"/>

### JDK 9

- The Java module system brings both startup improvements and regressions
  - On the simplest of Hello World programs there's a bit of added up front cost
  - This up-front cost pays off in slighlty faster class loading off for things loaded off the module path.
  - `jlink` has a few built-in plugins to move some validation and code generation (around lambdas) from runtime to link time, including the bulk of an effort to reduce lambda startup overheads. As is visible in the graph above the improvements to HelloLambda outweigh the regression in HelloWorld.

- G1 replaced ParallelGC as the default GC
  - A few more threads created early in the JVMs lifecycle..
  - G1 can cause a slightly longer delay in _shutting down_ the JVM. This behavior G1 was [_greatly_ improved](https://bugs.openjdk.java.net/browse/JDK-8136854) in JDK 9. Still, even with several other startup, scalability and performance improvements to G1 during development of JDK 9, naïve startup measurements can see a bit longer total time spent attributable to the use of G1 compared to ParallelGC

### JDK 10

- [JDK-8185853](https://bugs.openjdk.java.net/browse/JDK-8185853): "Generate readability graph at link time and other startup improvements" 
  - The single biggest improvement in JDK 10 and meant that the amount of java code we execute during startup fell considerably

- Fixed a few HotSpot regressions that snuck into JDK 9:
  - [Segmented code cache](https://openjdk.java.net/jeps/197) and [JVMCI](https://openjdk.java.net/jeps/243) slowed down a couple of methods that are hot during class loading. Several of these regressions were fixed in JDK 10 ([JDK-8183001](https://bugs.openjdk.java.net/browse/JDK-8183001), [JDK-8180763](https://bugs.openjdk.java.net/browse/JDK-8180763))
  - A feature to better [Validate JVM Command-Line Flag Arguments](https://openjdk.java.net/jeps/245) caused a surpising amount of computation during VM setup (Fixed by [JDK-8180614](https://bugs.openjdk.java.net/browse/JDK-8180614) in JDK 10)

- AppCDS open sourced!
  - Using AppCDS can reduce the startup time of an application by 25-40%

- ~25 other startup enhancements

### JDK 11

- [JDK-8198418](https://bugs.openjdk.java.net/browse/JDK-8198418): Invoke LambdaMetafactory::metafactory exactly from the BootstrapMethodInvoker
  - Carves a large chunk off of the cost for bootstrapping lambdas

- 28 other startup enhancements

### Other improvements

- (App)CDS has been continually improved to be able to include more and more things that the JVM would otherwise have to evaluate at runtime
- An experimental AOT tool was added in JDK 9, `jaotc`, which allows applications to compile Java code ahead of time to a shared library. It can speed up time to performance and reduce CPU use early on in the JVM lifecycle, but overheads in mapping in the shared library itself means _startup_ often regress.


