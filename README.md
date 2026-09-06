# Anodex Mobile

The native Android companion to [Anodex](https://github.com/Anodex/Anodex).

Pair it with your desktop over a QR code and you get Chat, Agent, Workspace, Email and Critical
Thinking on the phone, as you have them on the computer. **Every bit of the work still happens on
your machine.** The phone renders, asks and answers; it does not run a model, hold the workspace,
execute a tool, or touch a file. Your models, projects, keys and history never leave your PC —
which is the same promise the desktop app makes, and the reason this app exists in this shape.

The honest cost of that promise: if the desktop is asleep, closed or unreachable, the app says so
and does nothing. There is no offline mode and no fallback model.

## Status

**Early.** The design system and the connection-state model are in; there is no transport yet.

The full design lives in the desktop repo at
[`docs/HANDOFF_REMOTE_MOBILE.md`](https://github.com/Anodex/Anodex/blob/main/docs/HANDOFF_REMOTE_MOBILE.md)
— read it before adding anything here. It is self-contained and settles most of the questions you
are likely to have, including several that look open and are not.

| Phase | What                                                       | State           |
| ----- | ---------------------------------------------------------- | --------------- |
| 1     | Protocol generator + CI gate (desktop repo)                | not started     |
| 2     | `ClientChannel` refactor + `RemoteBridge` (desktop repo)   | not started     |
| 3     | App skeleton: pairing, transport, reconnect + resume       | **in progress** |
| 4     | Chat + tool confirmations                                  | not started     |
| 5–8   | Agent, Critical Thinking, Workspace, Email                 | not started     |

What is here now — Phase 3 groundwork, with no transport yet:

**The design system** (`ui/theme/`) ported from the desktop's `styles/theme.css` and
`styles/themes/`. Colours are exact. Typography is deliberately **re-stepped** for a phone rather
than copied, and `Typography.kt` explains why at length. The launcher icon is Anodex's real mark,
regenerated from the desktop's own asset by `tools/generate_icons.py` — never redrawn by hand.

**The connection model**, which is the app's top-level state because the phone caches nothing:

- `connection/ConnectionReducer.kt` — a pure function over `(state, event, now)`. What a fact
  *means*.
- `connection/ConnectionController.kt` — the grace timer and the reconnect backoff. When to *act*.
  Split from the reducer so the timing is testable in virtual time instead of by standing in a lift
  with a phone.
- `connection/NetworkMonitor.kt` — whether the phone is on the network it paired on, derived from
  routing properties so it costs no location permission.

**Pairing storage** (`pairing/`) — the paired secret encrypted under a non-exportable Android
Keystore key. This is the entire local persistence story; see the note in `PairedHost.kt` before
adding a field to it.

**Screens** — `NotPairedScreen`, `OfflineScreen`, `ConnectionHeader`. The offline screen gets real
design attention because a phone that caches nothing shows it more than anything except the chat.

A design harness lives behind a tap on the unpaired screen, since there is no transport and
therefore no way to reach the connected states on a device. It is scaffolding; delete it when
pairing and the transport are real.

## Building

Requires **JDK 17** and the Android SDK (compileSdk 35).

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The wrapper pins Gradle 8.9 and CI runs both of these on every push, so the build is verified even
if you have no local toolchain. `gradle/actions/wrapper-validation` checks the committed wrapper jar
against Gradle's published checksums on every run.

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
- **Never hand-write a channel definition.** Once the protocol artifact exists, if a channel is
  not in it, it does not exist.
- **The APK signing keystore never enters this repository.** Android identifies an app by its
  signing key: lose it and existing installs can never be updated, only uninstalled and replaced —
  taking their paired keys with them. Back it up off-machine before the first release. It is the
  only irreversible mistake available in this project.

## Licence

Not yet chosen.
