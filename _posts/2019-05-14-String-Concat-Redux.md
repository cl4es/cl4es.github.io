---
layout: post
title: "String concatenation, redux"
author: "cl4es"
image: /images/concat.png#
tags:
- java
---

Indified String concatenation is a fantastic beast. In this post I will try to shed some light on some of the implementation details, and maybe get to why I get excited over finding some peculiar way to optimize it from time to time. 

Let me know if something is particularly unclear, or worse, wrong.

### TL;DR

<blockquote class="twitter-tweet" data-lang="sv"><p lang="en" dir="ltr">I might be way too excited about this, but it seems I have turned an exponential factor into a constant one... <a href="https://t.co/RPzOnDundN">https://t.co/RPzOnDundN</a></p>&mdash; redestad (@cl4es) <a href="https://twitter.com/cl4es/status/1120647321992204288?ref_src=twsrc%5Etfw">23 april 2019</a></blockquote>
<script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script>

### Joining Strings

[JEP 280](https://openjdk.java.net/jeps/280) - Indify String Concatenation - ISC - brought the ability to implement String concatenation expressions using `invokedynamic` - indy for short. 

What this means is that when a compiler like javac is faced with an expression like `"foo" + bar` then instead of emitting some bytecode sequence that more directly implements the expression, it uses an indy to defer to the runtime to build up an implementation. This can have some interesting effects, since the runtime is free to create a concrete implementation that is better tailored for the particular environment - and also free to hook into some internal implementation details that statically compiled bytecode couldn't (without resorting to `Unsafe` and such). 

Mainly the focus has been on building up an implementation that works well with the current breed of JIT compilers.

The `StringBuilder` chains emitted by javac historically turns out to occassionally be hard to optimize for most JITs, especially when things get more complex. There's been a lot of optimization work over the years to try and optimize String concatenation, so when an indy implementation managed to outperform those older forms by a rather significant factor (4-6x) there was much excitement. More work is now in progress to apply similar strategies to other parts of the runtime and libraries. For example [JEP 348](https://openjdk.java.net/jeps/348) should hopefully provide an indified version of `String.format` that is quite reminiscent of ISC.

The drawback is that when loading and linking each call site we now need to run through a complex bootstrap routine, which is likely to be more expensive than "just" loading a bunch of straight-forward, statically compiled bytecode. 

Exactly how expensive depends, but in JDK 9 bootstrapping the first indified String concat expression could take something like 30-90ms (depending on hardware). Some of that bootstrapping overhead is/was a one-off deal, some of the added overhead will happen again when bootstrapping subsequent call sites. For some cases that incremental overhead might look benign, but I guess that depends on expectations...

### Optimize the runtime, not the bytecode

One of the clever ideas behind deferring to the runtime to provide a concrete implementation is that our runtimes are likely to evolve faster than the bytecode it ends up running, which will often be compiled to an older target than the JVM version we run on.

At a high level the beautiful thing with this isn't so much the improvements themselves, but that these optimization apply to code compiled to a JDK 9 target or above, and further optimization will keep applying without recompiling. Any JDK 9-13 numbers in this post remains the same whether I compile with JDK 9 or the latest EA build.

But let's get into some of those promised implementation details then, shall we? Oh, boy...

### Expression shapes

When implementing a String concat expression like `"foo" + bar` we generalize the expression and say it has the shape of a String literal concatenated with some argument that has whatever type `bar` is. So for a `bar` of type `int` we'll get one shape, if `bar` is an `Object` we'll get another shape. When bootstrapping a method handle to implement these, each unique shape will result in a unique expression tree. Parts of the expression tree will be perfectly shareable between expressions of another shape, parts will not be.

```java
String bar = ...
String foo = "foo" + bar;
String baz = "baz" + foo; // perfectly shareable shape.
```

In the above example the two concat expressions are perfectly shareable. Any `MethodHandles` and underlying structures loaded and generated to implement them will be perfectly shareable - they'll be different instances of the same expression tree: one created with the `"foo"` literal, another with the `"baz"` literal. 

### Many expression shapes!

There are theoretically a huge number of possible such shapes expressible. Really big expressions would thankfully be split apart by `javac`, but there are still a staggering amount of possible shapes. Just enumerate the shapes of concatenating two String arguments intermixed by String literals:

```java
// No literals:
foo = bar + baz;

// One literal:
foo = "foo" + bar + baz;
foo = bar + "foo" + baz;
foo = bar + baz + "foo";

// Two literals:
foo = "foo" + bar + "foo" + baz;
foo = bar + "foo" + baz + "foo";
foo = "foo" + bar + baz + "foo";

// Three literals:
foo = "foo" + bar + "foo" + baz + "foo";
```

1 + 3 + 3 + 1 = 8 shapes already, and that's just getting started. If we add similar expressions where `bar` is an `int` instead? Eight more shapes. Same, but we replace `baz` with an `int` instead? Another eight. 

The number of shapes of two arguments mixed with constants are thus 8 times the number of types we care about, squared. The types we cared about originally were `String`, `Object` and all primitives (`boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`), so ounting 800 shapes in total. And that's just for two arguments.

The factor eight isn't constant, either: With more arguments, the number of ways to mix in String literals also grows. In fact the possibilities doubles with every argument, so in aggregate have `2^(n+1)` ways to mix in constants around `n` arguments. The actual number of shapes for `n` arguments is, in theory, `2^(n+1)*10^n`. So for 3 arguments it'd be possible to observe 16000 shapes in a given application. Four arguments: 320000, and so on.

In the default strategy for indified String concatenation each shape will require at least some unique class. Maximum number of classes? Well, things gets even more complicated after a while since the `MethodHandle` implementation will start chaining these expressions into sub-expressions that might themselves be partially shareable, but it definitely grows into the millions. 

In practice the number of shapes in any particular program is likely to be much more modest. While concatenation expressions are likely to be common, the number of unique shapes in an application is seldom too extreme, maybe 10-200 shapes are to be expected. Each shape can generate a small to moderate amount of classes, however, so we can get quite substantial improvements even when the number of shapes is small in practice.

### MethodHandleInlineCopyStrategy

The default strategy builds up a `MethodHandle` piece by piece using other `MethodHandle`s: 

- first some rough filtering to turn `String`, `Object`, `float` and `double` arguments into `String`
- then a sequence of "mixers" that look at each argument and calculates the right size and encoding for the String
- then instantiate the `byte[]` that will back the `String` object
- then a sequence of "prependers" that take each constant and argument in reverse order and fill up the `byte[]`
- finally take the `String` encoding value and the `byte[]` and allocate the resulting `String`

In essence the algorithm remains the same since JDK 9. [The implementation](http://hg.openjdk.java.net/jdk9/jdk9/jdk/file/65464a307408/src/java.base/share/classes/java/lang/invoke/StringConcatFactory.java#l1474) is "arguably hard to read" since the `MethodHandle` expression trees is built up in reverse. 

### JDK 12: indexCoder, Object Stringifier

In JDK 12, I found that lumping together the `index` and `coder` fields in the JDK 9 implementation into 
a single `long` field. [This](https://bugs.openjdk.java.net/browse/JDK-8213035) simplified the expression tree a lot, with 
fewer classes needed for any and all expressions.

I also [merged](https://bugs.openjdk.java.net/browse/JDK-8213741) the "Stringifier" used for filtering `String` and `Object` arguments into a `String` suited for prepending.
This effectively means the number of types we care about when determining whether a shape is unique drops from 10 to 9, which drops off a lot of unique shapes.
Four argument shapes drops from 320000 to 209952, for example. Big drop in theory, perhaps not so big in practice, but even on more realistic applications these optimization started to add up.

Oh, and I found out we could reduce the number of rebinds we do when applying the same filter to more than one argument. [This optimization](https://bugs.openjdk.java.net/browse/JDK-8213478) proved quite beneficial to some String concat usage scenarios, and is generally applicable so might end up being an optimization in other places as well.

All in all JDK 12 about halved bootstrap overheads on some typical scenarios.

### JDK 13: Folding constants

There are a few nice little optimizations coming in JDK 13, including one that should make
a category of really trivial concatenations bootstrap really fast. 

However, the one that really got me excited is this one:

Instead of binding in a simple prepender for each argument and each constant, bind surrounding 
constants to the prependers for the arguments. This prepender will then prepend the suffix
constant (if one exist), then prepend of argument, then the prefix constant (if on exist).

This means we'll only bind in one prepender per argument into the 
main `MethodHandle` tree, and none for each constants. The constants will be neatly _folded_ 
into the prepender. (Your compiler probably does this sort of thing all the time, but we 
don't have that luxury here.)

Effectively this means that constants will no longer affect the shape of the expression.
So in essence the expressions `"foo" + bar + baz` and `bar + "foo" + baz` will share the same shape
(assuming `bar` and `baz` are of the same type). This reduces the theoretical number of 
observable shapes for `n` arguments by a factor of `2^(n+1)`! _*tweets excitedly*_

Still a huge number of shapes are possible in theory; in practice things are looking quite... OK.

### Show you some numbers

I added a note to the review thread for my [latest optimization](http://mail.openjdk.java.net/pipermail/core-libs-dev/2019-May/060087.html)
in this area, showing numbers for a simple but realistic mix of concatenation expressions:

```java
public class StringConcatMix {
     public static void main(String ... args) {
         String s = String.valueOf(args.length);
         String concat;
         concat = s + s;
         concat = "c" + s + "c";
         concat = s + "c" + s;
         concat = "c" + s + "c" + s;
         concat = "c" + s + s + "c";
         concat = s + "c" + s + s + "c";
         concat = "c" + s + "c" + args.length;
         concat = "c" + s + "c" + s + "c" + s;
     }
}
```

Building and running the above on JDK 8 through most-recent JDK 13 (*EA!)
```
JDK 8:    60ms
JDK 9:   215ms
JDK 11:  164ms
JDK 12:  111ms
JDK 13*:  86ms
```

What we see here is that the overhead in JDK 9 was pretty hefty, and that we're now
down to more acceptable numbers. Still there's a small regression compared to the JDK 8
`StringBuilder` approach. Might be a small price to pay for better peak
performance. There are always trade-offs involved, and historically the 
JVM favors throughput (and latency) over startup and footprint.

### Show you some outrageous numbers

I mentioned above that there are theoretically up to 320000 shapes needed to implement all 
concatenation expressions of four arguments mixed with zero to five constants. 

Let's see how we fare in practice on something as outrageously synthetic like that.

So I wrote [this horrible little program](/snippets/StringGen.java) to generate a java program enumerating them all (the 
source code generated is 9Mb, and I had to split the implementation into a lot of nested inner classes for it to even compile). 

The results are somewhat interesting:

```
JDK   #classes   Time
8     -          ~3.6s
11    39394      ~19.5s
12    27212      ~18.5s
13*   3174       ~15.6s
```

(#classes is the difference in loaded classes between running the code built with JDK 8
and built with JDK 9 for the given JDK)

It appears we are far from needing one class per "shape" at this scale in JDK 11: Under the hood, the 
method handle implementation is pretty good at splitting apart heavy expression trees into shareable
sub-expressions, which makes reasoning about theoretical bounds of the needed number of 
generated classes (such as `LambdaForm`:s and `Species` classes) hard in practice.

In the end we really do massively reduce the number of classes needed in JDK 12, and spectacularly so 
in the latest builds. The bootstrap time _is_ falling off, too, but maybe not as quickly as I'd 
have expected on this synthetic test (compared to the outcome in other tests).

### A (sub)word about theory and practice 

Another saving grace in the implementation is that subword integral primitive types (`boolean`, `byte`, 
`char`, `short`) are automatically widened to `int` for purposes of some of the internal structures created,
so we end up being concerned with only six types for the most part (seven in JDK 9-11). This
helps improve internal sharing of structural classes a lot. The number of classes generated in practice does look closer to `2^5*7^4 = 76832` in
JDK 11, `2^5*6^4 = 41472` in JDK 12 and `6^4 = 1296` in JDK 13. Various implementation details make us
need more in some cases, fewer in others.

### What's next?

I think there's still room for improvement, for sure, but I guess I should keep in mind to focus on 
improvements that also help in more realistic cases. If we can improve some problematic but
theoretical corner case we should at least make sure we don't make trivial and realistic cases
worse.

A few ideas:

- Filtering is applied in order, so the optimization to bind in just one unique filter combinator acting on
  all the arguments won't apply perfectly when there are interleaving `String`, `float` and `double` Stringifiers. Since the "Stringification" of `float` and `double` arguments shouldn't be order dependent, we could group the filters and apply each filter in turn.
- We could try "unrolling" arguments once and consume arguments pairwise. That'd mean we'd need a mixer and a prepender for each combination of two types. We'd need a lot more boilerplate, but might end up with an implementation that builds much shorter trees in practice, so we'd get more reusable building blocks, and fewer unique forms. 
- Maybe we should also consider emitting a trivial, boxing vararg adapter for when the number of arguments becomes so large that we don't really gain much peak performance from specialization to begin with. Such a fallback might be able to implement every non-trivial shape with a single, complex but somewhat inefficient `MethodHandle`. After some given number of arguments then perhaps the performance loss will be lost in the noise, anyhow.
