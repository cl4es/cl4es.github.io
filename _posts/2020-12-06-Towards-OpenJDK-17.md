---
layout: post
title: "Towards OpenJDK 17"
author: "cl4es"
image: /images/hello16.png#
tags:
- java
---

JDK 16 is soon entering rampdown, which means that we'll create a fork to let things stabilize before the GA release in March 2021. Work in [openjdk/jdk](https://github.com/openjdk/jdk) will soon take aim towards JDK 17 and beyond. 

I'll start with a quick update on the startup numbers in JDK 16, but what's really on my mind today is what _I_ should aim for in JDK 17.

### Startup: Fixed?

I've been involved in work finding and fixing startup and footprint regressions for a few years now, prompted by a few noticeable regressions in JDK 9. I've [reported](https://cl4es.github.io/2019/11/20/OpenJDK-Startup-Update.html) on this progress before, detailing both some of the regressions and many of the small improvements that has turned the tide. 

The work has continued. My own contribution to OpenJDK 16 has been slim (I've been on parental leave for much of the development cycle), which means we are set to deliver a large batch of improvements. Depending on app and setup we broke even with JDK 8 some time before 11 or shortly thereafter, and now we're just incrementally widening that gap (Intel(R) Core(TM) i5-7300U CPU @ 2.60GHz):

<img src="/images/hello16.png" alt="Hello World and Hello Lambda and Concat numbers from JDK 8 through 16"/>

In other words: a minimal Hello World that took my laptop ~45ms to run on JDK 8 might now take ~25ms to run on JDK 16. Down from the all time high of ~85ms in JDK 9. Roughly a 3.4x speed-up.

### JDK 16: Module archiving

The main improvement in JDK 16 comes from [archiving](https://github.com/openjdk/jdk/commit/03a4df0acd103702e52dcd01c3f03fda4d7b04f5) most of the datastructures needed by java modules in the default CDS archive. Making the full module graph archivable has required a concerted effort of several JDK and JVM engineers. Big thanks to Ioi Lam and Thomas Schatzl for heroic efforts in pulling this off. 

Work to archive heap data in our CDS archives begun back in JDK 9, and was built out incrementally in subsequent releases to allow more and more complex things to be archived. Always with some strict and forbidding implementation restrictions, though. I gave a so-so technical talk on this topic at JFokus back in February (before Covid-19.. those were the days), but at the time the effect of heap archiving wasn't [all that impressive](http://cr.openjdk.java.net/~redestad/slides/heap_archiving.pdf).

Now with the ability to archive the module graph the effect is getting a lot more significant and promising. And having a more solid story w.r.t. GC support should mean we can move forward with more confidence.

### Going static

I'm sure there's a lot more we could do to get the JVM to bootstrap even faster - including archiving more and more little things that steal cycles on startup - but it does seem like each millisecond gained comes at an increased cost. And will getting the JVM to boot in 5 or 10ms matter much if it still take seconds to warm up that microservice that loads tens of thousands of classes?

But as it turns out we might want to use the JVM as a basis for [Project Leyden](https://mail.openjdk.java.net/pipermail/discuss/2020-April/005429.html). In a context like that every little millisecond might matter to stay reasonably competitive. 

Leyden aims to implement and specify static images within the scope of the OpenJDK. Static images boils down to optimizing java applications under a closed world assumption. A closed world means that we won't allow dynamic classloading et.c., so we can optimize some things more aggressively than otherwise, including the removal of classes, methods and fields that can be proven to be unused. 

The goal is to get something that comes very close to a native executable in size and startup time, preferably without dropping support for certain GCs, monitoring tools and other features such as observability and tool compatibility. Easy, right? Other efforts in this area all seem to end up with some limitations, and it might become a goal of the project to define which of those limitations are reasonable for an implementation to be a well-specified "static" java.

To lay this Leyden puzzle, heap archiving could become a surprisingly important piece. Rather than just archiving static data, we should be able to draw heavily from the closed world assumption and end up with a full snapshot of the JVM process state. This alone might very well end up being the biggest startup win.

But a project like Leyden will need a few more pieces to be compelling. AOT compilation seem poised to be one such piece. While the experimental `jaotc` AOT compilation tool might be down for the count as far as Oracle is concerned, [AOT compilation using C2](https://github.com/openjdk/jdk/pull/960#issuecomment-722023038) might replace it.

A lot of things to shake out, right? And I'm not really sure what my role is supposed to be in all of this.

### See to something else entirely?

To complicate things further Project Leyden still hasn't officially started. Instead I've been warming up by learning more about the technologies we _might_ be using. In particular this means I've been diving into C2. 

Yes. The old, infamous but highly optimzing JIT that much of the java ecosystem relies on. That C2.

For now I'm mostly trying to figure out how things work by adding tests and microbenchmarks while cleaning up the code as I'm trying to understand it. Sometimes I might even manage to improve things a smidge. I've already managed to push a [small number](https://bugs.openjdk.java.net/browse/JDK-8256274?jql=labels%20in%20(startup)%20and%20labels%20in%20(c2)) of cleanups and improvements to JDK 16.

I'm sure few - if any - are likely to notice any difference, both since the improvements so far are modest and because these JIT compilation happen in the background. So unless you're starting lots of JVMs on systems that are either heavily constrained or under heavy load, any improvement to C2 are likely mostly noticeable as some transient CPU and memory usage spikes during the early stages of the JVM. 

But with some luck (and lots of work) we might end up making significant dents in the warmup behavior for most.

Just thinking of using C2 as an AOT compiler for Leyden might mean we end up further improving warmup of regular JVM apps as a side effect. Before Leyden even takes off! I think that kind of synergy is pretty cool.

Regardless I'm having a lot of fun digging into and improving C2. I might even try my hands at implementing some _real_ optimizations one of these days. I'm far away from something like a real compiler engineer but who knows what will happen with some time, experience and directed reading.

Stay tuned and upgrade to the latest JDK release!