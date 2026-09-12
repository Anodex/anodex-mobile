# Contributing to Anodex Mobile

Anodex Mobile is the Android companion to [Anodex](https://github.com/Anodex/Anodex), and
the two are one product with one policy — this file says the same thing its desktop
counterpart does.

Anodex is maintained by one developer, and the official code is written here. That does
not make outside input decoration — a good bug report from somebody using the software
differently is worth more than most things I could think of on my own, and I would rather
hear that something is broken than not.

So: reports, criticism, ideas and security findings are all genuinely welcome. What is
unusual about this project is the _shape_ of contribution, not the appetite for it.

## The short version

**Describe the problem. I will write the fix.**

That is the model. It is not a polite way of saying "go away" — it is how the code stays
coherent when one person is responsible for all of it.

## What helps most

In rough order of how useful it is:

1. **A bug report that reproduces.** Steps, what you expected, what happened instead. If I
   can make it happen on my machine, it usually gets fixed.
2. **A crash log, `adb logcat` output, or a screenshot.** Screenshots carry more here
   than they would elsewhere — this is a UI, and "it looks wrong" is a real report.
3. **A clear account of what is wrong and why.** "It disconnects" is a start. "It
   disconnects when the screen locks on mobile data, and reconnects the moment I unlock"
   is most of a fix.
4. **Security findings.** See [`SECURITY.md`](SECURITY.md) — please read it before
   opening an issue.
5. **Design and UX feedback.** Including the uncomfortable kind, and especially about
   the light theme, which gets less use and rots faster. Whole screens of this app have
   been redrawn because somebody said a menu felt clunky.
6. **Feature proposals**, with the problem you are actually trying to solve. The problem
   is the valuable half; the proposed solution is often not the one that ships.

**Where to put it:** open an **issue** for something that is broken or a change you can
describe concretely — there are templates, and they ask for what is usually missing.
Use **Discussions** for a question, or for an idea you want to think through out loud
before it becomes a request. Neither is wrong; the split just keeps the issue list
readable as a list of real defects.

## About pull requests

**Unsolicited pull requests are not the normal path here, and may be closed even when the
idea behind them is good.** That is not a judgement on the code. It is that a patch has to
fit an architecture, a set of conventions and a test suite that live mostly in one head,
and reviewing that fit often costs more than writing the change.

If you have found something worth fixing:

- **Open an issue first**, or a discussion if you are still working out what the right
  shape is. If a patch turns out to be the way forward, I will say so.
- **Small and obvious** — a typo, a broken link, a one-line guard with a test — may be
  reviewed as-is. Use judgement.
- **Large unsolicited rewrites** will almost certainly be declined, however good they are.
  Please ask before spending an evening on one.
- **A composable that hardcodes a colour, a size or a duration** will be declined on
  sight. Everything comes from `ui/theme/`, and both themes have to be checked.

A closed pull request does not mean the problem was not real. The fix may well land in the
next release, written differently.

## If you do send code

By deliberately submitting code, a patch, a pull request, or any other copyrightable
material for use in Anodex, you agree to the following. This is the whole agreement; there
is no separate CLA to sign.

**You keep your copyright.** You are not assigning it, and nothing here takes it from you.

**You grant Anodex a licence to use it.** Specifically: a perpetual, worldwide,
irrevocable, royalty-free, non-exclusive licence, with the right to sublicense, to use,
reproduce, modify, adapt, rewrite, create derivative works from, incorporate, publish,
distribute and relicense your contribution as part of Anodex — in current and future
versions, commercial or not.

That is broad on purpose. Anodex is not open source, it may be sold, and it ships as one
work; a narrower grant would mean contributed code could not be part of it.

**You confirm you have the right to send it.** That it is yours to give, and that you are
not bound by an employment agreement or another licence that says otherwise.

And to be clear about what happens next:

- **Sending something does not mean it will be used.** Most contributions are read,
  considered, and answered rather than merged.
- **What you send may be rewritten**, substantially or completely.
- **Your idea may be implemented independently**, without your code. If you show me the
  cause of a bug and I fix it my own way, that is the process working. It does not create
  an obligation in either direction.

### Please do not send

- code owned by your employer, or by anyone who has not agreed to this;
- anything under a licence incompatible with the terms above — in particular **GPL, AGPL
  or LGPL** code, which cannot be used here;
- confidential material of any kind;
- API keys, tokens, passwords, certificates or pairing secrets. Not in code, not in a log
  you paste into an issue. See [`SECURITY.md`](SECURITY.md).

## On AI

Anodex is built with AI assistance, and that includes work that begins from something
somebody reported. Your description of a bug may be analysed, summarised, tested against,
and used as the basis for an implementation written here.

To say the obvious thing plainly: **AI involvement does not change who owns what.** If you
send code, the licence above is why it can be used — not the fact that a model touched it
afterwards. A rewrite of your code is still derived from your code. That is precisely why
the terms are stated rather than assumed.

If you use AI to help write a contribution, that is fine, and the same confirmation
applies: it has to be yours to give.

## Getting it running

Requires **JDK 17** and the Android SDK (compileSdk 35). You do not need Android Studio,
though it helps.

```bash
git clone https://github.com/Anodex/anodex-mobile.git
cd anodex-mobile
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Those two are what CI runs on every push, so a green run here is a green run there.

The app does nothing on its own — it needs a desktop Anodex to pair with. Without one
you can reach the unpaired and offline screens and not much else.

One thing worth knowing before you spend time on a change: **a local test run does not
prove the module compiles.** Kotlin's exhaustiveness and scope rules will not show up in a
text search or a passing test file. `AGENTS.md` and the README's house rules cover the
conventions in more depth than this file does.

## Conduct

Be direct, be technical, and assume the other person is trying to get it right. Harsh
about the code is fine; harsh about the person is not.

There is no committee. If something goes badly wrong in a thread, I will deal with it.
