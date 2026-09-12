# Anodex Mobile

The native Android companion to [Anodex](https://github.com/Anodex/Anodex).

Pair it with your desktop over a QR code and you get Chat, Workspace, Agents, Email and Scheduler
on the phone, as you have them on the computer. **Every bit of the work still happens on
your machine.** The phone renders, asks and answers; it does not run a model, hold the workspace,
execute a tool, or touch a file. Your models, projects, keys and history never leave your PC —
which is the same promise the desktop app makes, and the reason this app exists in this shape.

The honest cost of that promise: if the desktop is asleep, closed or unreachable, the app says so
and does nothing. There is no offline mode and no fallback model. What it does do is say *which* of
those it is, because the desktop tells it before going away.

## Install

APKs are published on the [Releases page](https://github.com/Anodex/anodex-mobile/releases).
Download the `.apk`, allow installation from your browser when Android asks, and open it. The app
checks for its own updates and offers them; it never installs one behind you.

Then, in the desktop app, open **Settings → Remote** and scan the code.

Requires Android 8.0 (API 26) or newer.

## What is on the phone

| Section | What it does |
| --- | --- |
| **Chat** | General conversation and lookup. It can read your code and your projects; it cannot change them. |
| **Workspace** | Your projects, their files, and the runs that touched them. Project chats live here, and only here. |
| **Agents** | Start a run from away, watch it work, and see what it changed before it lands. |
| **Email** | The desktop's mailbox, with the same triage. |
| **Scheduler** | What is due, what ran, and what it did. |

Settings carries the rest:

| Section         | What it does                                                                     |
| --------------- | -------------------------------------------------------------------------------- |
| **Profile**     | Who you are, and what you have been doing — lifetime tokens, the last four weeks, the tools you reach for most |
| **Archive**     | What you put away, and the only place in the app anything can be deleted for good |
| **Diagnostics** | The connection, the computer's reason for going away, and the last crash — with a way to report it |
| **About**       | The version, this phone, a check for updates, and where to report a problem       |

Plus the model, the personality, what the assistant is remembering, and the pairing itself.

Everything in Profile is a read. A phone cannot reach the computer's settings — that prefix
carries the permission mode, the MCP servers and the model directory — so there is one narrow
channel for the profile alone, and a name and an avatar are all it answers with.

Chat and Workspace are deliberately not the same thing. A conversation's placement *is* its
permission: no project means no create, no edit, no run. That rule is enforced on the desktop, in
`buildTools`, not here — the phone cannot grant itself anything the computer would not.

## Building

Requires **JDK 17** and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The wrapper pins Gradle 8.9 and CI runs both of these on every push, so the build is verified even
if you have no local toolchain. `gradle/actions/wrapper-validation` checks the committed wrapper jar
against Gradle's published checksums on every run.

**Compile before you push.** Kotlin's exhaustiveness and scope rules are invisible to a text
search, and hand-checking a change for missing symbols is slower and worse than asking the
compiler. `local.properties` is gitignored, so point `sdk.dir` at your own SDK.

CI is still the final word — it builds on its own JDK from cold caches, and a local pass is a
faster check rather than the same one.

## How it is put together

**The design system** (`ui/theme/`) is ported from the desktop's `styles/theme.css` and
`styles/themes/`. Colours are exact. Typography is deliberately **re-stepped** for a phone rather
than copied, and `Typography.kt` explains why at length. The launcher icon and the icon set are
Anodex's real marks, taken from the desktop's own assets — `AnodexIcon.kt` holds the same path
strings `Icon.tsx` does, and a test pins them so the two cannot drift in silence.

**The connection model** is the app's top-level state, because the phone caches nothing:

- `connection/ConnectionReducer.kt` — a pure function over `(state, event, now)`. What a fact
  *means*.
- `connection/ConnectionController.kt` — the grace timer and the reconnect backoff. When to *act*.
  Split from the reducer so the timing is testable in virtual time instead of by standing in a lift
  with a phone.
- `connection/NetworkMonitor.kt` — whether the phone is on the network it paired on, derived from
  routing properties so it costs no location permission.
- `connection/RemoteFarewell.kt` — the reasons the computer gives for going away, mirroring
  `src/shared/remoteFarewell.ts` by hand. A socket that simply dies tells you nothing; quitting,
  sleeping and "remote access switched off" send you to three different places.

**Pairing storage** (`pairing/`) — the paired secret encrypted under a non-exportable Android
Keystore key. This is the entire local persistence story; see the note in `PairedHost.kt` before
adding a field to it.

**The transport** (`transport/`) speaks the desktop's generated protocol. Every channel the phone
uses is in that artifact; nothing is hand-written.

## House rules

These are inherited from the desktop app and are not negotiable per-PR.

- **Tokens only.** No hardcoded colours, sizes, or durations in a composable — everything comes
  from `ui/theme/`. Verify every screen in **both** dark and light mode; "it looked fine in dark"
  is how the light theme rots.
- **No dynamic colour.** Material You would recolour the app from the user's wallpaper, which
  would make the companion stop looking like the thing it accompanies.
- **Motion is for rare moments.** Designed, characterful motion belongs to event-driven moments —
  a first connection, a reconnect, a conversation's first reply — never an ambient loop. Honour
  `LocalReducedMotion`.
- **48dp touch targets.** The desktop's 26px controls do not port. Keep the control small and
  expand its touch target with padding.
- **Never hand-write a channel definition.** If a channel is not in the protocol artifact, it does
  not exist.
- **Never show a number nobody measured.** An absent reading is not zero. The context ring shipped
  a percentage derived from a count that was never taken, and it looked entirely convincing.
- **The APK signing keystore never enters this repository.** Android identifies an app by its
  signing key: lose it and existing installs can never be updated, only uninstalled and replaced —
  taking their paired keys with them. It is the only irreversible mistake available in this project.

## Source available, not open source

Anodex Mobile's source is published so you can read it, audit it, learn from it, and check
what the app does with a key that can drive your computer — which is not something you
should have to take on trust. It is not an open-source licence: redistribution, republished
builds and derivative products are not granted by default, and it remains copyright
© 2026 Anodex. See [LICENSE.md](LICENSE.md).

That limit is about ownership and distribution, not about keeping people out.

**Bug reports, reproduction steps, UX criticism and feature ideas are welcome**, and they
are the most useful thing you can send — the project is maintained centrally, so the
usual shape is that you describe the problem and the fix gets written here.
[CONTRIBUTING.md](CONTRIBUTING.md) explains how that works and the terms that apply if you
do send code. Security findings have their own private channel:
[SECURITY.md](SECURITY.md).

Anodex Mobile is built on software other people wrote, none of which the licence above
covers. Those components keep their own terms; their licences and copyright notices are in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

The same policy covers the [desktop application](https://github.com/Anodex/Anodex). They
are one product.
