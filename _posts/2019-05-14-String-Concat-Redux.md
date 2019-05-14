---
layout: post
title: "String concatenation, redux"
author: "cl4es"
draft: true
tags:
- java
---
## A String concatenation story
<blockquote class="twitter-tweet" data-lang="sv"><p lang="en" dir="ltr">I might be way too excited about this, but it seems I have turned an exponential factor into a constant one... <a href="https://t.co/RPzOnDundN">https://t.co/RPzOnDundN</a></p>&mdash; redestad (@cl4es) <a href="https://twitter.com/cl4es/status/1120647321992204288?ref_src=twsrc%5Etfw">23 april 2019</a></blockquote>
<script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script>

Indified String concatenation in JDK 9 is a fantastic beast. In this post I will try to shed some light on some of the implementation details, and maybe get to why I get excited over finding some peculiar way to optimize it from time to time. If I fail to explain the finer details of this it's likely because I've figured out _all_ the details myself. 

Let me know if something is particularly unclear, or worse, wrong.

### TL;DR

Yes.

### There's a JEP for concat

JDK 9 added [JEP 280](https://openjdk.java.net/jeps/280), bringing the ability to emit String concatenation expressions with invokedynamic. 

What this means is that when a compiler like javac is faced with an expression like `"foo" + bar` then instead of emitting some bytecode sequence that directly implements the expression, it may defer to the runtime to choose a fitting implementation. This can have some interesting results, since the runtime can be free to create a concrete implementation that is better customized for that particular runtime, such as a form that is a better fit for the current breed of JIT compilers. 

The `StringBuilder` chains emitted by javac historically turns out to be hard to optimize for most JITs, especially when things get more complex. There's actually been a lot of optimization work over the years to try and optimize some of these things, so when the now-default indified implementation strategy turned out to be able to outperform old bytecode by a rather significant factor (4-6x), it felt like a pretty big deal, and more work is now in progress to apply similar strategies to other parts of the runtime and libraries (e.g., [JEP 348](https://openjdk.java.net/jeps/348)).

The drawback is that each call site now needs to run through a bootstrap routine, which is likely to be more expensive than "just" loading a bunch of straight-forward, statically compiled bytecode. 

Exactly how expensive depends, but in JDK 9 bootstrapping the first indified String concat expression could be something like 30-90ms depending on hardware - and each new call-site will cost a little something extra. Some assumed most of that incremental overhead would be benign, but that might depend on expectations...

### Optimize the runtime, not the bytecode

One of the clever ideas behind deferring to the runtime to provide a concrete implementation is that our runtimes are likely to evolve faster than the bytecode it ends up running, which will often be compiled to an older target than the JVM version we run on. In a [previous entry](https://cl4es.github.io/2018/12/28/Preview-OpenJDK-12-Startup.html) I pointed to some improvements I did during the JDK 12 timeframe to reduce bootstrap overhead of String concatenation, without going into much detail.

At a high level the beautiful thing with this isn't so much the improvements themselves, but that these optimization apply to code compiled to a JDK 9 target or above, and further optimization will keep applying without recompiling. Any numbers in this post remains the same whether I compile with JDK 9 or the latest EA build.

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

1 + 3 + 3 + 1 = 8 shapes already, and that's just getting started. What if `bar` is an `int`? Eight more shapes. What if `baz` is? Another eight. 

The number of shapes of two arguments mixed with constants are thus 8 times the number of types we care about, squared. The types we cared about originally were String, Object and all primitives (8: `boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`), so counting 800 shapes in total. And that's just two arguments.

The factor eight isn't constant, either: With more arguments, the number of ways to mix in String literals also grows. In fact the possibilities doubles with every argument. The actual number of shapes for `n` arguments is roughly `2^(n+1)*10^n`, so for 3 arguments it'd be possible to observe 16 000 shapes in a given application. Four arguments: 320 000.

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

### JDK 12: indexCoder etc

In JDK 12, I found that lumping together the `index` and `coder` fields in the JDK 9 implementation into 
a single `long` field. [This](https://bugs.openjdk.java.net/browse/JDK-8213035) simplified the expression tree a lot, with 
fewer classes needed for any and all expressions.

I also merged the "Stringifier" used for `String` and `Object` arguments to [only use a single one](https://bugs.openjdk.java.net/browse/JDK-8213741).
This effectively means the number of types we care about when determining whether a shape is unique drops from 10 to 9, which drops off a lot of unique shapes.
Four argument shapes drops from 320 000 to 209 952, for example.

All in all JDK 12 about halved bootstrap overheads on typical tests.

### JDK 13: Folding constants

There are other optimizations coming in 13, but the one that got me all excited is this one:

Instead of binding in a simple prepender for each argument and each constant, bind surrounding 
constants to the prependers for the arguments. This prepender will then prepend the suffix
constant (if one exist), then prepend of argument, then the prefix constant (if on exist).

This means we'll only bind in one prepender per argument into the 
main `MethodHandle` tree, and none for each constants. The constants will be neatly _folded_ 
into the prepender. Your compiler probably does this sort of thing all the time, but we 
don't have that luxury here (yet).

Effectively this means that constants will no longer affect the shape of the expression,
so expressions like `"foo" + bar + baz` and `bar + "foo" + baz` will share the same shape
(assuming `bar` and `baz` are of the same type). This reduces the theoretical number of 
observable shapes for `n` arguments by a factor of `2^(n+1)`. _tweets excitedly_!

Still a huge number of possible shapes possible, theoretically, but in practice things are
looking much better.

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
JDK 12:  111ms
JDK 13*:  86ms
```

What we see here is that the overhead in JDK 9 was pretty hefty, and that we're now
down to more acceptable numbers. Still a small regression compared to the JDK 8
`StringBuilder` approach in bootstrap times, but remember that this unlocks some 
potentially significant throughput improvements. There are always trade-offs involved,
and historically the JVM favor throughput (and latency) over startup and footprint.

### Show you some outrageous numbers

I mentioned above that there are theoretically up to 320 000 shapes needed to implement all 
concatenation expressions of four arguments mixed with zero to five constants. 
Let's see how we fare in practice on something as outrageously synthetic like that.

This little [code generator] generates a program enumerating them all, and we get some pretty interesting results:

```
JDK   #classes   Time
8     0           ~3.6s
11    39394      ~19.5s
12    27212      ~18.5s
13*   3174       ~15.6s
```

(The application generates 10000 inner holder classes, the #classes listed is the
difference in loaded classes between running the code built with JDK 8 and built
with JDK 9 for the given JDK)

We are far from the hypothetical 320 000 classes needed for the 320000 shapes on JDK 9
through JDK 11: The `BoundMethodHandle` implementation starts splitting heavy expression 
trees pretty quickly, so reasoning about max bounds on number of generated `LambdaForm`:s
and `Species` classes gets messy in practice.

But we really do massively reduce the number of classes needed to
implement all the shapes in JDK 12, and spectacularly so in the latest builds. 
The bootstrap time is falling off, too, but not as quickly as I'd have expected compared 
to the outcome in other tests.

There's more work to be done here, but whatever we do we should definitely focus on improvements
that also help in more realistic cases.
