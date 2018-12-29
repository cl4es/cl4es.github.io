---
layout: post
title: "Preview: OpenJDK 12 startup"
author: "cl4es"
image: /images/hello12.png
tags:
- java
---

JDK 12 development is ramping down with a targetted [release in March](https://openjdk.java.net/projects/jdk/12/). This hopefully means things will be pretty stable from here on out. 

Disclaimer: *everything below is subject to change*.

That said, allow me to follow up on my notes on [startup from 8 through 11](/2018/11/29/OpenJDK-Startup-From-8-Through-11.html) with some fresh data points:
 
<img src="/images/hello12.png" alt="Hello World, Lambda and Concat numbers from 8-12"/>

(\*note that JDK 12 numbers are preliminary)

### Leaner string concatentations

JDK 9 introduced [Indyfied String concatenation](https://openjdk.java.net/jeps/280), which can dramatically optimize apps that spend significant time doing String concatenations (who would ever...). To achieve this, however, the JVM needs to do a bit more work the first time it sees a String concatenation callsite. Quite a bit on the first one it sees, then typically much less on every subsequent callsite.

To showcase the initial startup overhead, the `Hello Concat` numbers in the graph above measures the time to run the following simple program (compiled using the respective JDK's javac):

```java
public class HelloConcat {
  public static void main(String ... args) {
    System.out.println("Hello Concat: " + args.length);
  }
}
```

- On JDK 8 there's no measurable overhead compared to a regular Hello World
- From JDK 9, ISC will be used unless you tell `javac` otherwise, introducing a ~50ms overhead (brought down to ~37ms in JDK 11)
- In JDK 12 a few targetted optimizations ([JDK-8213035](https://bugs.openjdk.java.net/browse/JDK-8213035), [JDK-8213741](https://bugs.openjdk.java.net/browse/JDK-8213741)) to the ISC implementation itself brings both the one-off (~18ms) and per-callsite overheads down significantly. 

Some overheads left, but we're now beating JDK 8 on my machine. (Victory!)

### CDS enabled by default

If you care about startup and aren't enabling CDS, you're missing out! 

All the above data points above are with basic CDS enabled. Disabling it would tell a very different story (latest JDK 12):

<img src="/images/hellocds.png" alt="Image showing how CDS cuts startup time in more half"/>

Yes, default CDS means a 55-60% startup time reduction in these small tests. (The default CDS archive most likely won't give you 60% on any _real_ application, but 50% reductions isn't unheard of if you [deploy with AppCDS enabled](https://blog.codefx.org/java/application-class-data-sharing/))

However - historically - to enable just the _default_ CDS you had to:

1. run `java -Xshare:dump` once to dump the CDS archive
2. ensure to include `-Xshare:auto` on any and all command lines (not necessary in JDK 11)

Which is easily forgotten and might not be possible if you don't control the deployment environment. In JDK 12, none of this is necessary (but won't hurt).

So: faster startup for free - no actions required!

### CDS heap archiving

The HotSpot runtime team has added new heap archiving capabilities to CDS, which allows for relatively straightforward serialization/deserialization of select Java objects to/from the CDS archive from internal code.

Not only is this a bit faster than allocating objects in the interpreted stages of the JVM bootstrap, but also allows for better sharing between JVMs (the archive regions can be mapped in read-only and safely shared between processes). 

Even better(?): when applied strategically it can avoid executing large chunks of code altogether. A few object graphs that are unconditionally created early on in the JVM bootstrap were picked to pioneer use of this feature, which helped shave a substantial number of milliseconds off of bootstrap. Example: [JDK-8202035](https://bugs.openjdk.java.net/browse/JDK-8202035)

### A few regressions... 

Unfortuntately there are some startup regressions that eat into the gains we've seen from CDS heap archiving etc.

One of these [has been identified and resolved](http://mail.openjdk.java.net/pipermail/hotspot-compiler-dev/2018-December/031924.html) very recently, but has not made it into JDK 12 EA builds just yet. 

Another known issue is due a rewrite to use concurrent hash tables for a few VM internal data structure ([JDK-8208142](https://bugs.openjdk.java.net/browse/JDK-8208142)). There _might_ be time to get some fixes to this into JDK 12, but as things stands it's not really a blocker so I fear work to improve in this area will be pushed off to the next release.

And as always there might be a few additional things that slipped under the radar...

### TL;DR

Overall JDK 12 has improvements and a few regressions that add up to a small startup improvement. ISC startup overheads has been significantly reduced.
