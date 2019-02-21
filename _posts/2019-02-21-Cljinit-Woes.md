---
layout: post
title: "cljinit woes"
author: "cl4es"
tags:
- java
---

Earlier this week it was pointed out to me that the latest JDK updates (8u202/11.0.2)
has a rather large startup/performance issue when running Clojure in certain modes.

<blockquote class="twitter-tweet" data-lang="sv"><p lang="en" dir="ltr"><a href="https://twitter.com/cl4es?ref_src=twsrc%5Etfw">@cl4es</a>  <a href="https://t.co/3sGSA1PmFF">https://t.co/3sGSA1PmFF</a>  I have a potential fix in Clojure but oooof!</p>&mdash; Ghadi Shayban (@smashthepast) <a href="https://twitter.com/smashthepast/status/1097557132566773760?ref_src=twsrc%5Etfw">18 februari 2019</a></blockquote>
<script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script>

Others were reporting [similar issues](https://bugs.openjdk.java.net/browse/JDK-8219233), and all seemed to be related to Clojure and startup. We tried narrowing down the slowdown to something simpler with some success, and identified a few bottlenecks.

The root issue is a security-sensitive class initialization bug fix that ensures static methods aren't marked as resolved until the class has been fully initialized.

The manifestation of the issue in clojure seems more complex than our simple reproducers suggested, but with some assistance from [Ghadi Shayban](https://twitter.com/smashthepast) along with ample help from David Holmes and [Vladimir Ivanov](https://twitter.com/iwan0www/) we now have a pretty good picture of what's going on.

I'll reason about the issues using Java code. Sorry. :-)

## Baseline

Let's start with a no-op baseline where we do nothing a million
times. Typically such code would be eliminated by the JIT pretty 
fast:

```java
public class Baseline {
  static {
    long start = System.nanoTime();
    for (int i = 0; i < 1_000_000; i++) {
    }
    long end = System.nanoTime();
    System.out.println("Elapsed: " + (end - start) + " ns");
  }
  public static void main(String... args) {}
}
```

Result:
```
$ ~/jdks/11.0.1/bin/java Baseline.java
Elapsed: 4964249 ns
$ ~/jdks/11.0.2/bin/java Baseline.java
Elapsed: 4946020 ns
```

It takes a few milliseconds for the JIT to wake up and eliminate
the loop, which is what I expected. There's also no regression
in 11.0.2. So now we at least know the issue isn't _just_ a 
result of doing a lot of work in a `<clinit>`. _Phew!_

## Bad <clinit>
```java
public class Bad {
  static void foo() {}
  static {
    long start = System.nanoTime();
    for (int i = 0; i < 1_000_000; i++) {
      foo();
    }
    long end = System.nanoTime();
    System.out.println("Elapsed: " + (end - start) + " ns");
  }
  public static void main(String... args) {}
}
```

When `Bad` initializes, it will call static method `foo` a million 
times.

```
$ ~/jdks/11.0.1/bin/java Bad.java
Elapsed: 14956790 ns
$ ~/jdks/11.0.2/bin/java Bad.java
Elapsed: 1596766461 ns
```

Two orders of magnitude worse in 11.0.2: Oooof!

Profiling we see a lot of time spent resolving static methods, with 
massive overheads in the interpreter and in the JIT compiler code. 

In essence `foo` will be resolved and linked *every time* it's called,
a result of the bug fix that went into 8u202, 11.0.2, etc. Resolving
a method is relatively expensive, and aggressively cached - but now
with the caveat that the method resolution won't be cached until its
holder class has been fully initialized.

This particular case is accidentally improved in 13-b07 by 
[JDK-8188133](https://bugs.openjdk.java.net/browse/JDK-8188133), mostly
by alleviating and avoiding work in the JIT compiler. This doesn't seem 
to do much for the clojure case, though.

## Good <clinit>

As a workaround we can move static utility methods to a utility class 
that is allowed to initialize completely before calling into it.

```java
public class Good {
  public static class StartupsLittleHelper {
    static void foo() {}
  }
  static {
    long start = System.nanoTime();
    for (int i = 0; i < 1_000_000; i++) {
      StartupsLittleHelper.foo();
    }
    long end = System.nanoTime();
    System.out.println("Elapsed: " + (end - start) + " ns");
  }
  public static void main(String... args) {}
}
```

`Good` is equivalent to `Bad`, just that `foo` has been moved to `StartupsLittleHelper`.

Result:
```
$ ~/jdks/11.0.1/bin/java Good.java
Elapsed: 9130700 ns
$ ~/jdks/11.0.2/bin/java Good.java
Elapsed: 8938148 ns
```

No regression in 11.0.2, and in fact it's _faster_ than `Bad` in 
both 11.0.1 and 11.0.2. This is likely because the compiler can be more
aggressive when dealing with fully initialized classes. 

## AlsoBad <clinit>

Just a caution: Refactoring out static methods to helper classes is likely not 
going to be enough if all they do is call back into the not-fully-initialized 
class:

```
public class AlsoBad {
  static void foo() {}
  public static class StartupsLittleHelper {
    static void foo() { AlsoBad.foo(); }
  }
  static {
    long start = System.nanoTime();
    for (int i = 0; i < 1_000_000; i++) {
      StartupsLittleHelper.foo();
    }
    long end = System.nanoTime();
    System.out.println("Elapsed: " + (end - start) + " ns");
  }
  public static void main(String... args) {}
}
```

```
$ ~/jdks/11.0.1/bin/java AlsoBad.java
Elapsed: 18997877 ns
$ ~/jdks/11.0.2/bin/java AlsoBad.java
Elapsed: 1694381527 ns
```

Based on the profiles I've collected running the clojure reproducers, it seems like
there's a mix of `Bad` and `AlsoBad` style calls happening. 

## What now?

We'll work to resolve some of the corner cases exposed in the OpenJDK here. There are
no guarantees of how much we'll be able to recuperate. At the very least it will take 
some time before a fix can be delivered. 

Working around the performance drop by refactoring heavy-lifting to utility classes
should have lasting benefits.
