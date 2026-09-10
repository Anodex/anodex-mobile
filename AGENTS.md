# Anodex Mobile — Agent Notes

Kotlin + Jetpack Compose. A native Android companion to the Anodex desktop app,
which lives in a separate repository (`Anodex/Anodex`).

## What this app is for

The phone is a **remote control for the desktop**, not a second IDE. The desktop
is where the work happens; this app exists so somebody away from it can:

1. **Notice** — what changed, failed, finished, or needs an answer.
2. **Decide** — approve or reject a tool call with enough context to be right.
3. **Direct** — start a bounded task, send a short instruction, stop a run.
4. **Inspect** — read the resulting chat, diff, plan, task, mail, or file.

Editing large files, exhaustive settings, and project administration stay
desktop-first. A feature request that amounts to "mirror the desktop screen"
is usually the wrong shape; ask what decision the person is trying to make on a
phone.

`docs/MOBILE_PRODUCT_SECURITY_UI_HANDOFF.md` is the current product, privacy and
UI brief. Read it before changing user-facing language about networks, models,
or what data lives where — several of its corrections exist because the app
previously made claims that were not true.

## There is no local Android toolchain

**CI is the only verification.** There is no Android SDK on the development
machine, so nothing here compiles locally — not the app, not the unit tests.

That has a direct consequence: **do not claim a change builds.** Push it and let
`.github/workflows/build.yml` answer. Before pushing, what you *can* check by
hand is worth checking — brace balance, that every callback is in scope, that
each theme token you referenced actually exists — but say plainly that a
compiler has not seen it.

CI runs on every push to `main` and every pull request:

```
./gradlew assembleRelease        # the APK
./gradlew testDebugUnitTest      # unit tests
python3 tools/summarize_tests.py # says what the tests actually covered
```

The APK lands as the `anodex-mobile-apk` artifact, which is what the release
script later downloads.

## Releasing

**Merging is not shipping.** A change nobody can install has not been delivered.
The desktop repository is held to this same standard — see its `AGENTS.md`;
when a change spans both, release both, and keep their notes consistent about
what happened.

### Version numbers

`MAJOR.MINOR.PATCH`, in `app/build.gradle.kts`:

```kotlin
versionCode = ... ?: 5801       // must increase, or Android refuses the install
versionName = ... ?: "0.58.1"   // what a person reads
```

- **PATCH** — bug fixes only.
- **MINOR** — a new capability. Resets patch to 0.
- **MAJOR** — still `0`; the shape of the app is not yet promised.

`versionCode` is **`major * 10000 + minor * 100 + patch`** — 0.58.1 is 5801,
0.59.0 is 5900. Both must rise together: Android compares `versionCode` to decide
whether an install is an upgrade, and the in-app check compares `versionName` to
decide whether to offer one. Raise only one and the update either never appears
or appears and never applies.

It used to be the minor on its own, 0.54.0 → 54, and that rule had no room for a
patch in it: 0.58.1 would have kept code 58, so the release would have shown up in
the in-app check and then refused to install. That is the same loop the release
script exists to prevent, arriving through the version scheme rather than the
wrong bytes. Every release up to 0.58.0 was a minor, so the gap was never hit —
which is the only reason it survived to be found by the first patch. Codes only
ever have to increase, so the jump from 58 to 5801 costs nothing.

Release tags carry a third number: `v0.54.0-preview.62`. The `preview.N` is a
running count of releases, unrelated to the version — take the previous one and
add one.

### Cutting one

There is no release workflow. After the change is merged and CI is green on
`main`:

```bash
python tools/release_apk.py v0.54.0-preview.62 0.54.0 --notes-file notes.md
```

That script resolves the CI run **by commit sha** and refuses to upload an APK
whose `versionName` does not match the tag. Both guards exist because of a real
failure: v0.44.0 shipped a 0.43.0 APK, picked by taking the newest CI run
moments after pushing — which was the merge commit's run, not the version
bump's. Both were green, both produced an APK, and only one had the new version
in it. The phone updated, reinstalled the same build, and offered the update
again for ever.

**A correct tag on the wrong bytes is indistinguishable from success until
somebody installs it.** Never bypass those checks.

### Release notes are written, not generated

Say what changed and why it mattered, in the app's own voice — the same voice
the code comments use. A list of commit subjects is not release notes. This is
the part people actually read, and it is the standard the desktop repository was
asked to match.

## Attribution

`tools/strip_attribution.py` is installed as a `commit-msg` hook and removes
coding-agent attribution trailers before they are recorded. Do not add
`Co-Authored-By` lines naming a tool, and do not disable the hook. Re-install it
in a fresh clone with:

```bash
python tools/strip_attribution.py --install
```

Only trailers that credit a tool are removed. Prose that discusses these tools,
and genuine human co-author trailers, are left alone.

## Compose gotchas that have actually bitten

- **`List` is not stable — but since Kotlin 2.0 that no longer means what it used
  to.** A `List` parameter is still an unstable one, and this was indeed why the
  chat transcript stuttered. The fix used to be `@Immutable` or an immutable
  collection type. It is not needed any more: the Compose compiler's *strong
  skipping* is on by default from Kotlin 2.0, and it makes a composable with
  unstable parameters skippable anyway, comparing those parameters by instance.

  Measured rather than assumed, on 2026-09-10 against `main`: **every restartable
  composable in this app's own code is skippable — 171 of 171**, including the
  whole chat hot path (`MessageRow`, `ChatScreen`, `ToolRow`, `MarkdownText`,
  `ConversationRow`). Fifty-one classes are still inferred unstable, `ChatMessage`
  and `AgentRun` among them, and it costs nothing.

  So do not annotate the payload types or add an immutable-collections dependency
  on the strength of the old rule — that is churn buying a fix for a problem the
  compiler already solved. Re-check before believing either version of this:

      gh workflow run stability.yml

  It writes the report as an artifact. `StrongSkipping` in the module JSON is the
  flag that decides which of these two paragraphs is true.
- **`LazyColumn` composes out of order.** A running variable accumulated inside
  `items { }` is wrong — items are composed as they scroll into view, not front
  to back. Date headers computed that way appeared and vanished while
  scrolling. Precompute groups outside the list.
- **`LaunchedEffect` keys must name every late-arriving input.** A key that
  omits a value which arrives after first composition means the effect never
  re-runs with the real data. This is what made a screen look permanently empty
  while the data was in fact present.

## Style

Draw from the theme, never from literals: `AnodexTheme.colors`, `AnodexTheme.type`,
`Spacing`, `Radii`, `Touch`. Material components carry their own elevation, corner
scale and ripple, so the app builds its own buttons and dialogs from tokens
instead — a borrowed control is visible immediately, and worst at the moment it
is asking the user to trust it.

Both themes are first-class. The warm light palette is a deliberate
differentiator, not an afterthought — validate it as carefully as dark.

**Colour that carries meaning has two steps, and the second one is not optional.**
`accent`, `danger`, `warn`, `success`, `accentGreen` and `accentCyan` were picked
against a near-black field. As *text* on the light palette's cream they measured
between 1.14:1 and 3.13:1 — the same values measure 5.3 to 12 on Midnight, which
is why nobody working in dark ever saw it. Use the `*Ink` variant for anything
read at text size, including glyphs; keep the base for fills, borders and
anything else large enough to answer to the 3:1 threshold. `ContrastTest`
enforces this on every push, and a new token that skips it will fail there.

**Surfaces are separated by colour, not by shadow** — that is `Tokens.kt`'s
deliberate choice — so two surface tokens holding the same value is not a
cosmetic tie, it is a boundary that does not exist. `bgApp`, `bgSurface` and
`bgInput` were once identical in the light theme, and a filled card, a search
field and a loading list had no edge at all there. `ContrastTest` now holds
every surface step to **Midnight's own weakest step**, measured rather than
picked: the dark theme is the one that gets looked at daily, so whatever
separation it settles for is by definition enough.
`ui/components/Gallery.kt` is where you *look* at both — every shared component
and both colour columns on one page, previewed in each theme. Anything added to
the shared components belongs there too: a component nobody can see in both
themes has only been designed for one.

**`textFaint` is deliberately below AA, and this is the decision, not an
oversight.** It measures 2.71:1 at worst on Midnight and 2.15:1 on Light, against
the 4.5 that `type.meta` and `type.badge` would need to pass — so every timestamp,
section label and second line on an empty screen is under the bar. It was weighed
and kept: lifting it collides with `textMuted` (5.06:1 on Light), so the whole
three-step ramp has to move — about 15/7/4.7 on Midnight and 12.7/7/4.7 on Light
is what works — and that visibly changes 112 sites, trading the quietest step in
the hierarchy for legibility. That is a design call, not a bug fix, and it is the
user's to make rather than a passing contributor's.

Two things follow. It is **not** symmetric — Light is about twenty percent worse
than Midnight, and it is the theme where pale-on-cream is harder to begin with —
so do not repeat the claim that it is even. And `ContrastTest` pins it: a floor it
may not sink below, and a check that Light does not drift further from Midnight
than it already has. Changing the ramp means changing that test on purpose.

Keep 48dp touch targets. Never communicate a waiting state with motion alone;
pair it with text and a static shape or colour.

### Reach for the shared piece before writing a new one

Every one of these exists because the same thing had been written out by hand
between three and nine times, and the copies had drifted. A new screen that
builds its own is how the drift starts again.

| Want | Use | Not |
| --- | --- | --- |
| A panel | `AnodexCard` | a `Column` with `clip`/`background`/`border` |
| A screen title | `ScreenScaffold` | a `Text(type.heading)` above the content |
| "Nothing here" | `EmptyState` | a centred `Text` in a `Box` |
| "Still loading" | `ListSkeleton` | a sentence on a blank page |
| A failure beside content | `InlineProblem` | dropping it, which is the usual outcome |
| Somewhere to type | `AnodexTextField` | anything from Material |
| A rule | `Hairline` | a 1dp `Box` with a background |

Two rules that are not obvious from the signatures:

- **Clip before `clickable`.** Every tap draws a press wash (`AnodexPress`, which
  replaced Material's ripple in `LocalIndication`) and it takes the shape of
  whatever layer it lands in. A rounded surface made tappable without a `clip`
  first flashes a square.
- **`ScreenScaffold` hands its content the height it measured.** The content owes
  it two things: `fadingEdges(topInset, 0.dp)` so it dissolves under the bar
  rather than being sliced by it, and `listPadding(topInset)` so its first row
  can be read.
- **Settings is the one screen that does not use it, on purpose.** It is a modal
  stack rather than a destination, and a centred title over a back chevron is the
  idiom for that everywhere on the phone. It is an exception that was considered,
  not a screen that was missed — leave it unless you are changing what Settings
  *is*.

### Absence, failure and delay are three states, not one

The `EmptyTone` argument is required for this reason. A screen that can fail
*and* still have something to draw — the scheduler reads tasks and offers
starters from two different places — must say the read failed rather than
quietly rendering what it happens to have. That is the same defect described
under **Absence is not failure** below, arriving through the UI instead of the
data layer.

## Absence is not failure

The most common defect shape in this codebase: a `getOrNull()` or
`getOrDefault(emptyList())` that turns an error into an empty list, so a screen
reports "nothing here" when the truth is "the request failed". It has been found
in six-plus places. When a call can fail, say it failed.

Related: `JsonNull` **is** a `JsonPrimitive`, so `.content` returns the literal
string `"null"`. Use `contentOrNull`.
