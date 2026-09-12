# Anodex Mobile licence

**Copyright © 2026 Anodex. All rights reserved.**

Anodex Mobile is **source available, not open source.** The same terms cover it and the
[desktop application](https://github.com/Anodex/Anodex); they are one product.

The difference matters, so it is worth stating plainly. An open-source licence grants
everyone the right to redistribute, modify and republish the software. This licence does
not. The source is published so that you can read it, audit it, learn from it, report
what is wrong with it, and satisfy yourself about what the software does on your machine
— which is a promise Anodex makes repeatedly, and one you should not have to take on
trust.

Everything below is the whole of what is granted. Rights not granted here are reserved.

## What you may do

- **Read, inspect and audit** the source, for any purpose, including security research.
- **Discuss, review, quote and write about** it — publicly and critically.
- **Build and run it yourself**, from this source, for your own use.
- **Install and use the official released APKs.**
- **Report bugs, propose features and suggest changes.** See `CONTRIBUTING.md`.

You do not need to ask permission for any of that.

## What is not granted

Without separate written permission from Anodex, you may not:

- **redistribute** Anodex, modified or unmodified, in source or binary form;
- **publish your own builds** of Anodex, or offer them for download;
- **sell, rent, sublicense or relicense** Anodex, or charge for access to it;
- **distribute derivative works** — anything substantially based on this source;
- **offer Anodex as a hosted or managed service** to others;
- **rebrand** it, or ship it as part of another product.

These are all about _distribution_. Nothing here restricts what you do with Anodex on
your own machine, or what you say about it.

## About forks

GitHub's own terms let anyone fork a public repository, and that is fine — a fork is how
you read code properly, test a theory, or prepare a suggested change.

What a fork does not carry is a distribution right. Publishing builds from your fork,
offering it as a competing product, or presenting it as Anodex are exactly the things
above that are not granted.

## Separate permission

If you want to do something this licence does not grant, ask. Permission can be given,
and it has to be in writing to count. Silence is not permission.

## Name, logo and branding

The **Anodex** name, its logo and app icons, its artwork, and its distinctive visual
design are not licensed to you merely because you can see the source. Branding rights are
reserved.

You may of course use the name to refer to the project — reviewing it, writing about it,
reporting a bug, explaining that something is compatible with it, or saying what your fork
was based on. That is ordinary descriptive use and nobody needs permission for it.

What you may not do is imply endorsement, or present an unofficial build as an official
Anodex release. Official releases come from this repository and nowhere else.

## Third-party components

Anodex Mobile is built on software written by other people. **None of the terms above
apply to it.** Every third-party library remains under its own licence, held by its own
authors, and that licence governs it entirely. Nothing in this file claims ownership of
anything Anodex did not write.

What the app depends on is declared in `gradle/libs.versions.toml` and used in
`app/build.gradle.kts` — AndroidX and Jetpack Compose, the Kotlin coroutines and
serialization libraries, OkHttp, CameraX and ZXing. All are permissive, and at the time of
writing all are Apache-2.0.

Their licences and copyright notices are reproduced in
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md), along with the attribution for the icon
set. That file also says plainly what is not done yet: the app does not display those
licences on a screen of its own.

The desktop application's dependencies are separate and are audited in its own repository,
in [`docs/THIRD_PARTY_AUDIT.md`](https://github.com/Anodex/Anodex/blob/main/docs/THIRD_PARTY_AUDIT.md).

## No warranty

Anodex is provided **"as is", without warranty of any kind**, express or implied,
including but not limited to the implied warranties of merchantability, fitness for a
particular purpose, and non-infringement.

To the maximum extent permitted by law, Anodex and its contributors are not liable for any
claim, damages, data loss, or other liability arising from the software or its use —
whether in contract, tort or otherwise.

This matters more than usual here. The phone is a window onto a computer that runs
commands and edits files on your behalf — read what it is asking for before you approve
it.

## Contributions

If you send code, the terms in [`CONTRIBUTING.md`](CONTRIBUTING.md) apply to it. In short:
you keep your copyright, and you grant Anodex a broad licence to use and build on what you
sent.

---

_This is a plain-language notice, not a negotiated agreement, and it has not been reviewed
by a lawyer. It is written to be honest about intent rather than exhaustive._
