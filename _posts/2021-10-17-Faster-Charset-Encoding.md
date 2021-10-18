---
layout: post
title: "Faster Charset Encoding"
author: "cl4es"
tags:
- java
---


TL;DR: `CharsetDecoder`s got several times faster in JDK 17, leaving `CharsetEncoder`s behind. After a few false starts and some help from the community I found a trick to speed up `CharsetEncoder`s similarly. This may or may not speed up your apps in the future. 

This is a technical read, but also a story about the process of failing and trying again, with no graphs and lots of distracting links to source code. Sorry. But there will be cake.

### Decoding / Encoding

I've previously blogged about some JDK 17 improvements to [charset decoding](https://cl4es.github.io/2021/02/23/Faster-Charset-Decoding.html), where I used intrinsics originally added to optimize [JEP 254](https://openjdk.java.net/jeps/254) in a few places to realize localized speed-ups of 10x or more.

But dealing with text is a two-way street. When turning some text data into `char`s the Java APIs _decode_ into an internal representation (which since JEP 254 is either ISO-8859-1 or a variant of UTF-16). When communicating with the outside world, those `char`s will have to be _encoded_ from the internal representation into whichever character is expected out there.

Encoding characters was left more or less unchanged in JDK 17, though, because there was seemingly no straightforward way to adapt existing intrinsics for a similar speed-up. The intrinsic that did exist only works specifically for ISO-8859-1.

I have grown up using [ISO 8859-1](https://en.wikipedia.org/wiki/ISO/IEC_8859-1) - a legacy encoding with nice properties such as encoding every supported character using a single byte and not having any emojis. It even has cute nicknames such as "latin-1". But much of the world use something else these days. UTF-8 is probably a good guess.

### Performance check: JDK 17

Let's take [`CharsetEncodeDecode`](https://github.com/openjdk/jdk17u/blob/master/test/micro/org/openjdk/bench/java/nio/CharsetEncodeDecode.java) - a naive microbenchmark for testing `CharsetDecoder` and `CharsetEncoder` performance using ASCII-only data. We can pick which encodings to tests, so let's check the most common ones. According to [W3Techs.com](https://w3techs.com/technologies/overview/character_encoding) these are the top 10 used on public websites (ran using the [Oracle OpenJDK 17](http://jdk.java.net/17/) bits on my x86 workstation):

```
CharsetEncodeDecode.encode:
      (type)  Mode  Cnt   Score   Error  Units
       UTF-8  avgt   30  12.019 ± 0.430  us/op
  ISO-8859-1  avgt   30   2.983 ± 0.197  us/op
Windows-1251  avgt   30  41.315 ± 0.715  us/op
Windows-1252  avgt   30  41.845 ± 1.406  us/op
      GB2312  avgt   30  45.914 ± 1.043  us/op
   Shift-JIS  avgt   30  46.328 ± 1.617  us/op
  ISO-8859-9  avgt   30  41.519 ± 0.776  us/op
Windows-1254  avgt   30  41.589 ± 0.911  us/op
      EUC-JP  avgt   30  64.199 ± 3.368  us/op
      EUC-KR  avgt   30  46.412 ± 1.367  us/op
```

In this ASCII-centric test encoding to ISO-8859-1 is faster than pretty much anything else. 4x faster than UTF-8, and more than 10x faster than any of the other encodings. This is thanks to the [`implEncodeISOArray`](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/sun/nio/cs/ISO_8859_1.java#L155) method being intrinsified, something that was implemented already in [JDK-8](https://bugs.openjdk.java.net/browse/JDK-6896617).

The UTF-8 encoder does have a helpful [ASCII fast-path](https://github.com/openjdk/jdk17u/blob/master/src/java.base/share/classes/sun/nio/cs/UTF_8.java#L457), explaining that the gap here is significantly smaller than for the other encodings:

```java
            // ASCII only loop
            while (dp < dlASCII && sa[sp] < '\u0080')
                da[dp++] = (byte) sa[sp++];
```

Any charset that is ASCII-compatible would probably benefit from doing the same, but most are somewhat surprisingly missing such fast-paths.

Regardless: any encoding that is ASCII-compatible (and produce a single byte stream when encoding ASCII) is essentially doing the same thing in this benchmark, but ending up with vastly different results. The difference is that ISO-8859-1 intrinsic, which speeds up ISO-8859-1 and ISO-8859-1 alone. That's not very nice, but that's how it's been since JDK 8.

### Dropping the ball

I admit feeling proud after optimizing the charset decoders: Even though I've been working as a performance engineer on the OpenJDK for a while now it's exceedingly rare to be able to pull off a 10x optimization in anything, no less something that might be up front and center in many applications. The HotSpot JVM has impressive performance in most every way, so getting just a few percent performance increase can require a lot of engineering effort.

But I couldn't see a way to speed up encoding using anything similar. I knew there ought to be some way, but when optimizing the decoders I basically re-used intrinsics that were already there in the JDK-internal Java API, and the intrinsics for encoding - basically only the aforementioned `implEncodeISOArray` - was not directly applicable for anything but ISO-8859-1.

With no real regression in sight other things took priority. I also went on parental leave for a while and had a nice summer break.

### Nudged back into the game

Sometimes the reason to take a second look comes from surprising places. Including Twitter:

<blockquote class="twitter-tweet"><p lang="en" dir="ltr"><a href="https://twitter.com/cl4es?ref_src=twsrc%5Etfw">@cl4es</a> any chance you could provide insight into this based on your (much appreciated) work with strings/encoding/decoding?<br>We&#39;re encoding from a reused StringBuilder into a reused ByteBuffer, sb.toString().getBytes(cs) is much faster in the non-unicode case on hot paths!</p>&mdash; Carter Kozak (@carter_kozak) <a href="https://twitter.com/carter_kozak/status/1433798391604162561?ref_src=twsrc%5Etfw">September 3, 2021</a></blockquote> <script async src="https://platform.twitter.com/widgets.js" charset="utf-8"></script> 

(Maybe _someone_ reads my blog!?) I also got a few pings from others who seemed anxious to hear if I had found a solution to their woes (Hi, [@yazicivo](https://twitter.com/yazicivo)!).

The issue boils down to `String.getBytes(UTF8)` being much faster than using a `CharsetEncoder` - even when the usage of the latter involves reusing `CharBuffer`s and `ByteBuffer`s to be essentially allocation-free. My initial guess-work as to why this was way off the mark, but the conversation led to a JMH [test case](https://github.com/carterkozak/stringbuilder-encoding-performance/pull/4) which demonstrated the core issue. The reported performance issue was mostly a discrepancy between JDK 8 and later JDKs caused by a large _improvement_ in JDK 9 to certain `String` methods. But there also seemed to be some 5-10% regressions when comparing back to JDK 8 in some of these microbenchmarks.

A nicely written, well-defined microbenchmark? And a noticable regression on some variants? Caused by one of the best performance features in JDK 9? OK, I'm in!

### Zooming in on the core issue

Since Compact Strings `String.getBytes` has specialized implementations to encode to some built-in charsets such as UTF-8 and ISO-8859-1. These can be hard to beat since all `String`s that can be will be represented internally in a ISO-8859-1 binary format. Encoding to ISO-8859-1 from such a `String` means a simple `Arrays.copyOf`. Encoding to UTF-8 _can_ be an array copy, too, if your `String` is [all ASCII](https://github.com/openjdk/jdk17u/blob/aabc4ba0eef9e47fc547b4ec91153a9427acd968/src/java.base/share/classes/java/lang/String.java#L1264):

```java
        if (!StringCoding.hasNegatives(val, 0, val.length))
            return Arrays.copyOf(val, val.length);
```

You'll have to take my word for it when I say that allocating and copying arrays is pretty fast. And the `StringCoding.hasNegatives` is a HotSpot intrinsic method that will use hand-optimized vector instructions to check for negative bytes in the `String` byte array. If there are none that means they are all ASCII, and you get a fast copy. 

Looking at the [`StringEncode` JDK microbenchmark](https://github.com/openjdk/jdk17u/blob/master/test/micro/org/openjdk/bench/java/lang/StringEncode.java) (which target `String.getBytes`) we can see that this meant a pretty solid speed-up compared to JDK 8:
```
StringEncode.WithCharset.encodeCharset:
JDK (charsetName)  Mode  Cnt    Score   Error  Units 
  8         UTF-8  avgt   15  128.070 ± 5.013  ns/op
 17         UTF-8  avgt   15   68.964 ± 5.236  ns/op
```

These optimizations are absent in the code used by `CharsetEncoder`. Again I was where I was months ago. Staring at a set of intrinsic, optimized methods used in one place that seemed perfectly incompatible with the use case I sought to optimize. Alas.. maybe there's some way to get halfway there?

### He's going the distance

One of the intrinsics I had looked at earlier is the one used to "compress" `char`s to `byte`s. (It's really tossing away the upper byte of each `char`, which would be hilariously lossy unless you've checked that the upper bytes are all zeroes.) Which led to my [first attempt](https://github.com/openjdk/jdk/pull/5621/commits/4da3d71efadff9d2f3db235c5e838c6af0a66a7e) at fixing this in the UTF-8 encoder:

```java
            while (lastAscii < slASCII && sa[lastAscii] < '\u0080') {
                lastAscii++;
            }
            if (lastAscii > sp) {
                int len = lastAscii - sp;
                JLA.compressCharsToBytes(sa, sp, da, dp, len);
                sp = lastAscii;
                dp += len;
            }
```

Simply scan the input until we find a non-ASCII character, then use the `StringUTF16.compress` method (which I here exposed as `JavaLangAccess.compressCharsToBytes`). 

Even though the scan for non-ASCII isn't vectorized with something similar to `StringCoding.hasNegatives`, this tweak meant a sizable speed-up: 1.45x in Carter Kozak's microbenchmark, and almost 1.65x on `CharsetEncodeDecode.encode`:

```
      (type)  Mode  Cnt  Score   Error  Units
       UTF-8  avgt   30  7.134 ± 0.336  us/op
```

Great! But the fully intrinsified ISO-8859-1 encoding was still much faster. 

### He's going for speed

So I took a long hard look at what that ISO-8859-1 intrinsic actually does. Then it finally dawned upon me. That thing that in hindsight is so obvious.

On x86, `implEncodeISOArray` is [implemented](https://github.com/openjdk/jdk17u/blob/master/src/hotspot/cpu/x86/macroAssembler_x86.cpp#L5630) by setting up a bitmask made up of `0xFF00` repeated 8, 16 or 32 times (depending on hardware capabilities). As long as there are no bits detected when OR:ing that bitmask against a chunk of `char`s that means that all those `char`s can be ISO-8859-1-encoded.

Every chunk that pass through the filter will be subject to some AVX magic to copy every other byte to the destination. (`vpackuswb` + `vpermq` = yeah, no, I get it but also don't.) Thankfully that part isn't something we have to care about. All that needs to be different for our needs are those bitmasks. And those were easy enough to spot.

What we need is the exact same thing, but with a different bitmask. One that would detect any non-ASCII `char`s:`0xFF80`.

It took me a few hours of furiously copy-pasting code from various places in the C2 code to get it all up and running but finally everything seemed properly duct-taped together and I had created a new, very derivative, intrinsic: `_encodeAsciiArray`. That first version is all there in the [PR changelog](https://github.com/openjdk/jdk/pull/5621/commits/cef05f44fd482646c5df496a50bdf78527d908cb), hacks and redundant scaffolding included.

But it worked!

After cleaning up the implementation (thanks to some much needed input from [Tobias Hartmann](https://twitter.com/TobiasJava)! and making sure most ASCII-compatible charsets ges the same treatment then numbers look almost too good to be true:

```
CharsetEncodeDecode.encode:
        (type)  Mode  Cnt   Score   Error  Units
         UTF-8  avgt   30   3.246 ± 0.192  us/op
    ISO-8859-1  avgt   30   3.028 ± 0.202  us/op
  Windows-1251  avgt   30   2.922 ± 0.092  us/op
  Windows-1252  avgt   30   2.880 ± 0.196  us/op
        GB2312  avgt   30  46.004 ± 0.903  us/op
     Shift-JIS  avgt   30  46.130 ± 1.142  us/op
    ISO-8859-9  avgt   30   3.112 ± 0.304  us/op
  Windows-1254  avgt   30   3.016 ± 0.235  us/op
        EUC-JP  avgt   30  64.867 ± 3.100  us/op
        EUC-KR  avgt   30  47.918 ± 1.847  us/op
```

UTF-8 is 4x faster. Several legacy encodings see 15x improvements. Not the result I expected getting back at this - but very nice!

### Combined effect

Microbenchmarks are great tools with surprisingly spotty predictive power on how real application will behave. A large speed-up in an isolated test might mean little in practice unless that just-so happened to be the bottleneck. Which is rather unlikely. On one application we saw a significant drop in CPU time from the decoder optimization in 17 and.. no improvement on throughput. Perhaps not too surprising since encoding and decoding `String`s often happen when doing I/O, where the bottleneck is probably the network or some disks. Things that regular microbenchmarks typically avoid touching.

To naively try and emulate the real world a tiny bit better I prepared [EncodeDecodeLoop](/snippets/EncodeDecodeLoop.java): a simple program which generates a chunk of ASCII data, encodes it and writes it to a file, then reads it back before decoding it. These are numbers for `Windows-1251`, using a file, repeated 100.000 times:

```
          Real time   User time
JDK 11      31.149s      9.511s
JDK 17      27.526s      6.850s
JDK 18      21.586s      1.820s
```

A huge drop in user time - as expected - but also a significant speed-up on real time measurements. Despite the benchmark doing its worst to bottleneck on disk I/O. Yes, in essence this is yet another microbenchmark, but it suggests that maybe these optimizations - when taken together - _will_ be powerful enough to be seen through the noise on some apps. And even when there's no observable speed-up to be had, doing the same work using significantly less CPU cycles is always good.

### Future Work

I've only implemented this optimization on x86. I'm not very good at reading assembler code (assembly?) on any platform, much less writing it. I really got lucky when spotting a straightforward way to fix the x86 routine. I've filed a follow-up bug to get it fixed on Aarch64 and it's being looked at by people who should know what they're doing.

I've also taken a look at some of the encodings in that Top 10 list and seen that at least some of them actually are ASCII-compatible, just implemented differently. I have a patch the EUC-JP encoding and are looking at a few of  the others.

And finally I think this work should be impactful, safe and uncontroversial to backport 17u: While it ended up being a large improvement over JDK 8 - the most baseline of baselines - it also resolved some regressions.