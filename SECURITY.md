# Security policy

Anodex Mobile holds a key that can drive a computer. It pairs over a channel it
authenticates itself, stores the paired secret under a non-exportable Android Keystore
key, and then acts as a remote control for software that runs commands and edits files.

That is the whole surface, and it is why the source is published: you should be able to
check what the app does with that key rather than believe it.

This policy matches the [desktop repository's](https://github.com/Anodex/Anodex/blob/main/SECURITY.md).
A finding that spans both belongs wherever you found it — they reach the same person.

**Security research on Anodex is welcome.** Read it, probe it, take it apart. The licence
puts no restriction on that, and finding something is a service.

## Reporting a vulnerability

**Please report privately first, and please do not open a public issue for anything
exploitable.**

Use GitHub's private vulnerability reporting on this repository — the **Report a
vulnerability** button under the **Security** tab. It opens a private thread visible only
to the maintainer, and it is the right channel precisely because it does not tell everyone
else how to do the thing you found.

If that button is not available to you for some reason, open a public issue saying only
that you have found a security problem and asking how to send the details. Do not put the
details in it.

## What to include

As much as you would want if you were fixing it:

- what an attacker can actually do, and what they need in order to do it — physical
  access to an unlocked phone is a different finding from something reachable over the
  network;
- the steps to reproduce;
- the app version, the Android version, and the device;
- a proof of concept if you have one.

**Leave out anything sensitive.** Real pairing codes or secrets, API keys, tokens,
certificates, the contents of a private mailbox, the contents of a workspace. Redact them, or
describe them. This applies to public issues especially, but to private reports too — a
report should not be the reason a credential leaks.

## What happens next

A report gets acknowledged, investigated, and answered. If it is real, it gets fixed and
shipped, and the release notes say what was wrong in enough detail to be useful without
being a recipe.

There is no bounty programme and no formal response-time commitment — one person
maintains this, and promising a window would be pretending otherwise. You will not be
ignored.

If you would like credit, say so and you will get it. If you would rather not be named,
that is fine too.

## Disclosure

Please give a reasonable chance to fix something before publishing it. What is reasonable
depends on how bad it is; if you tell me your timeline, I will tell you honestly whether
it is achievable.

Publishing a working exploit for an unfixed issue affects the people using the software,
not the person who wrote it.

## Worth knowing about the threat model

Some of these are deliberate design decisions rather than oversights, and it may save you
time to know which:

- **The phone is a display, not an authority.** It cannot grant itself a permission the
  desktop would refuse — a chat with no project cannot edit code, and that is enforced
  on the computer, not here. A way for the phone to get around that is a serious finding.
- **The phone stores one secret, and only one.** The paired key, encrypted under a
  non-exportable Keystore key. Conversations, files and model output are not cached. A
  way to extract the paired secret, or to find anything else persisted that should not be,
  is a serious finding.
- **Pairing is the trust boundary.** Anything that lets an unpaired device connect,
  impersonate a paired one, or survive being unpaired is a finding.
- **The transport is authenticated and pinned.** A way to intercept, downgrade or
  man-in-the-middle the connection between phone and computer is a serious finding.
- **Approvals must be seen to be given.** The app can be asked to approve a tool call. A
  way to get an approval recorded that the person never actually saw is a serious finding.
- **Updates.** The app checks for and offers its own updates. A way to get an unexpected
  APK in front of somebody as though it were an Anodex release is a finding.
