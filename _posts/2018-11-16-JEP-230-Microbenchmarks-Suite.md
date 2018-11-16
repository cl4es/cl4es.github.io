---
layout: post
title: "JEP 230 - Microbenchmarks Suite"
author: "cl4es"
tags:
- java
- microbenchmarks
---

Currently finishing up work on integrating a microbenchmarks suite, [JEP 230](https://openjdk.java.net/jeps/230), into the OpenJDK source tree.

In and off itself there's not that much to it.
 
First off this adds support to the OpenJDK makefiles to build and run selected 
microbenchmarks on a fresh build. `make build-microbenchmark` will build `build/$PROFILE/images/test/micro/benchmarks.jar`, and something like `make test TEST="micro:java.lang.invoke"` would run all the `java.lang.invoke` microbenchmarks with default settings.

Secondly it co-locates most of the benchmarks currently residing in 
[jmh-jdk-microbenchmarks](https://openjdk.java.net/projects/code-tools/jmh-jdk-microbenchmarks/) with
the main OpenJDK sources.

One key difference with having the micros co-located is that they will always be built using the 
latest javac, so as we're redefining and experimenting with improvements to the language itself,
such changes will be automatically picked up. This means we can quickly detect regressions that 
use of some static benchmark would miss. 

Detecting regressions with "old" bytecode and benchmarks is of course also important, but operationally 
we will likely be using benchmarks produced by an older version of the OpenJDK (or from
jmh-jdk-microbenchmarks) in tandem with a "fresh" benchmarks built using the version under test. 

Technical arguments aside: my main motivation for moving forward with all this is my hope that it will make adding 
relevant microbenchmarks as natural tomorrow as adding functional regression tests are today.
