# Anodex Mobile: product language, data boundaries, security, and UI brief

## Purpose

This document updates the product framing for Anodex Mobile.

The important decision is that a user's phone is one of that user's own computers. It is acceptable for selected Anodex data to move between the user's desktop and phone, provided that:

1. the transfer is authenticated and encrypted;
2. the data remains under the user's control rather than being stored by Anodex or another relay service;
3. any data retained on the phone is deliberate, visible, protected at rest, and removable.

Anodex also lets the user choose cloud models. That is a separate, explicit data boundary: when a cloud model is selected, relevant prompt/context content is sent to the selected model provider under that provider's terms and data policy. Mobile must not blur this choice, hide it, or imply that a cloud-model request stays entirely on the user's devices.

This is not an instruction to turn Mobile into a generic cloud client or to mirror every desktop feature. It should remain a focused remote control surface for the desktop, optimized for monitoring work, answering interruptions, and starting bounded work away from the desk.

## The terminology problem

The word **local** currently does two incompatible jobs:

| Term being conflated | What it should mean | Example |
| --- | --- | --- |
| Data locality | Who owns the devices and where models/data are processed or retained | “Local models run on your computer.” |
| Network route | How the phone reaches the desktop | same Wi-Fi, private VPN, or a configured secure remote route |

An internet-routed connection is not automatically a privacy failure. If the phone and desktop are both user-controlled endpoints, authenticate one another, and use encrypted transport without an Anodex relay, the desktop↔phone link remains in the user's private device environment even though packets cross the internet. That says nothing about a separately selected cloud-model or external-service request; that request has its own disclosed boundary.

Do **not** use “Anodex is local-network only” as a privacy claim. It means “LAN-only” to users, conflicts with VPN/public-route support, and incorrectly makes remote access sound inherently less private.

### Recommended vocabulary

Use these terms consistently:

- **Private-device / device-owned link:** the phone and desktop are user-controlled endpoints.
- **Local models:** model inference runs on the user's desktop, not a hosted model provider.
- **Cloud models:** inference runs with the selected provider; relevant request data leaves the user's devices for that provider.
- **Direct secure connection:** the phone talks to the desktop directly over an encrypted, authenticated connection; no Anodex relay handles content.
- **Same network:** the phone is on the desktop's LAN or home Wi-Fi.
- **Private VPN route:** a secure private overlay route, such as the user's VPN/mesh network.
- **Secure remote route:** an intentionally configured way to reach the desktop away from home. Do not call this “local network.”

Avoid these claims:

- “Nothing leaves your computer.” The conversation and any selected files necessarily travel to the phone to be shown there.
- “Your keys never leave your PC.” The phone intentionally has a pairing-derived device credential. Say “your desktop credentials and model keys stay on your desktop” if that is the intended claim.
- “Truly secure.” Security is a set of guarantees under a stated threat model, not an absolute promise.

## Proposed product copy

### Primary privacy promise

> **Your desktop and phone connect directly and securely.**
>
> Anodex Mobile securely connects to Anodex on your desktop. With local models, inference runs on your computer. If you choose a cloud model, Anodex sends the information needed for that request to the model provider you selected.

### Pairing screen

> Pair this phone directly with your desktop Anodex. The connection is encrypted and bound to this computer. Anodex does not route your work through its servers.

### Remote-access explanation

> Your phone can reach this desktop on the same Wi-Fi, through your private VPN, or using a secure remote route you configure. The route may cross the internet; Anodex still encrypts and authenticates the connection directly between your devices. This connection route is separate from your choice of local or cloud model.

### Optional phone storage setting

> **Keep selected data on this phone**
>
> By default, Anodex Mobile is live-only. Turn this on to keep selected recent activity or conversations on this phone for faster reopening and offline reference. Stored data is encrypted on this device and can be cleared at any time.

### Local and cloud model disclosure

> **Local model:** runs on your computer.
>
> **Cloud model:** sends this request to the provider you selected. Review that provider's data policy before using it with sensitive material.

If the selected provider's API/data controls are known, surface them accurately (for example, organization account, enterprise controls, zero-retention agreement, or consumer account). Do not make a generic “private” claim that overrides the provider's actual terms.

This qualifier matters. “Data stays on your devices” must not accidentally imply that content sent to a user-enabled cloud model, email provider, web search, or other external service remains only on the desktop/phone.

## Data-flow model

| User choice or feature | Main data path | Accurate user-facing summary |
| --- | --- | --- |
| Local model | phone ↔ encrypted desktop connection ↔ model running on desktop | “This model runs on your computer.” |
| Cloud model | phone ↔ encrypted desktop connection ↔ selected cloud-model provider | “This request is sent to the selected provider.” |
| Email / web / connected service | phone ↔ encrypted desktop connection ↔ selected service | “This feature uses the connected service and its data policy.” |
| Optional Mobile cache | desktop ↔ encrypted phone connection → encrypted storage on phone | “Selected Anodex data is kept on this phone.” |

The mobile app should not present the cloud-model path as a failure of its phone/desktop encryption. The desktop↔phone bridge can be secure while a user independently elects to use a cloud provider. What matters is that the interface reveals both boundaries before a user shares sensitive material.

## What the current mobile implementation already does well

The current code has a strong foundation for this position:

- It uses `wss://` with a certificate pin created during pairing. The phone will only accept the desktop certificate it pinned, even if the desktop address changes.
- QR pairing includes a certificate fingerprint and a one-time, minimum-32-byte secret with a limited validity period. Manual pairing asks the user to compare a fingerprint before the credential is sent.
- The desktop exchanges the one-time pairing secret for a long-lived device key; reconnects use that device credential over the pinned TLS channel.
- The mobile-side credential is encrypted at rest with AES-GCM under a non-exportable Android Keystore key. Backups are disabled in the Android manifest.
- The app intentionally avoids a location permission merely to identify Wi-Fi names.
- It already knows about multiple reachability routes, including LAN, mesh/VPN, and configured public addresses. This makes the proposed terminology a correction of the product story, not a change in intent.
- Approval UX is correctly conservative: deny is prominent, destructive changes can show a diff, and unanswered approvals time out to denial.

Relevant source: `transport/AnodexSocket.kt`, `pairing/PinnedTrust.kt`, `pairing/PairingPayload.kt`, `pairing/SecretCipher.kt`, and `pairing/PairedHost.kt`.

## Security boundary and threat model

### What Anodex Mobile can reasonably promise

With correct desktop implementation and a protected paired phone, the current design is intended to protect the desktop↔phone connection against:

- passive observers on home Wi-Fi, public Wi-Fi, or the internet route reading the connection;
- a different machine answering at the expected IP address and port;
- a certificate authority or DNS/route change silently substituting a different desktop certificate after pairing;
- a copied app-data directory revealing the saved device credential outside the physical phone;
- stale or malformed pairing QR codes being accepted without validation.

### What it cannot protect against

The product should say this plainly in documentation, not in primary UI:

- A compromised desktop can read or alter the work because it is the execution endpoint.
- An unlocked, stolen, or shared phone can control the desktop while its paired credential remains usable.
- Someone who obtains a valid pairing QR code during its short validity window may be able to pair a second device. Treat the QR code as a short-lived password: do not share or photograph it.
- A user who approves a malicious command has authorized that command; cryptography cannot correct a bad decision.
- A public remote route can still attract scans, connection attempts, and denial-of-service traffic even if it rejects unauthenticated requests.
- Hosted models and external services receive whatever the user elects to send to them.

### Phrase security accurately

Recommended claim:

> Anodex Mobile uses an encrypted, certificate-pinned connection and a per-device pairing credential to talk directly to your desktop. It is designed to protect that connection from interception and desktop impersonation after pairing. If you select a cloud model or connected service, that provider receives the data needed to perform its feature.

Do not claim “unbreakable,” “truly secure,” or “zero trust” without a formal, independently reviewed threat model and a security audit.

## Security and product improvements to prioritize

### P0 — prevent accidental loss of remote access

The Offline screen's `Pairing` button currently calls `unpair()`, which clears the stored credential and Keystore key immediately. A seemingly harmless recovery action can therefore destroy a working pairing.

Required UX:

1. Rename the action to **Pair a different computer** or **Replace this pairing**.
2. Explain that the existing desktop will stop accepting this phone if the user continues.
3. Require confirmation.
4. Prefer a transactional replacement: retain the existing pair until the replacement pairing succeeds, then revoke/remove the old one.

Relevant source: `MainActivity.kt` and `AnodexViewModel.kt`.

### P0 — make notifications actionable

The event notification currently shows a title/body but no content intent/deep link. A notification that says a run needs an answer must open the exact pending approval, agent plan, failed task, or conversation.

Required behavior:

- Tap “needs approval” → open the exact approval card.
- Tap “agent plan ready” → open the exact run's plan review.
- Tap failure/completion → open the relevant conversation/task detail.
- If the item was answered elsewhere, open a truthful resolved state rather than a blank destination.

### P1 — step-up protection for consequential actions

The saved connection credential is intentionally available after normal phone unlock, so background/reconnect behavior remains frictionless. Add an **optional biometric or device-credential gate** for:

- approving destructive tools;
- approving sensitive tools;
- starting a build-capable agent run;
- unpairing/replacing a desktop;
- enabling a public remote route or changing trusted routes.

Do not put a biometric prompt in front of every reconnect or ordinary chat turn. The right boundary is a remote action that can alter the desktop or reveal sensitive data.

### P1 — give public remote access a deliberate setup and audit trail

If “Reach this computer from anywhere” is a supported feature, make its trust boundaries visible:

- distinguish Home network, Private VPN, and Secure remote route in the host screen;
- state whether the connection uses a direct route, a user-provided public address, or a private VPN;
- show paired devices on the desktop with name, last seen, and a revoke control;
- record a small remote activity log: device, timestamp, route type, and consequential action—not full private content by default;
- rate-limit unauthenticated pairing/authentication attempts on the desktop and keep the desktop firewall posture explicit.

### P2 — make phone persistence a user choice

The current “live-only” behavior is a coherent privacy default. It should become the default, not a permanent restriction.

Offer an opt-in cache with separate toggles:

| Data class | Suggested default | Why |
| --- | --- | --- |
| Connection status and run metadata | off or minimal | useful for offline context; may reveal project names/activity |
| Recent conversation text | off | private content, but valuable for offline reading |
| Opened workspace files | off | high sensitivity; avoid accidental source-code replication |
| Attachments | off | can be large and sensitive |
| Notification history | off | potentially reveals activity on the lock screen or phone |

Requirements if any cache is added:

- explain what is stored before enabling it;
- encrypt it at rest using a Keystore-backed key;
- exclude it from backups;
- expose “clear cached Anodex data” and per-category storage size;
- clear it on unpair, with the user warned about the consequence;
- never silently turn it on after an update.

### P2 — make cloud-data boundaries visible at the moment they matter

Do not ask for repetitive confirmations before every normal cloud-model request. Do make the choice clear and sticky:

- show a `Local` or `Cloud` label next to the active model in the mobile header and model picker;
- on first selection of a cloud provider, explain that request content goes to that provider and link to the relevant policy/account controls;
- show the provider name, not only a model name, where ambiguity is possible;
- offer a project-level or conversation-level reminder when an otherwise local workflow changes to a cloud model;
- clearly label tools that send content to third parties (web search, email, uploads, external connectors), regardless of model choice;
- preserve the user's chosen model and disclosure state across the desktop and phone, so the two UIs never disagree about the active boundary.

## The product model to preserve

The phone should not become an inferior desktop IDE. Its best role is:

1. **Notice** — what changed, failed, finished, or needs me?
2. **Decide** — approve/reject safely with enough context.
3. **Direct** — start a bounded task, send a concise instruction, stop work.
4. **Inspect** — read the resulting chat, diff, agent plan, task, mail, or file.

Direct editing of large code files, exhaustive desktop settings, and high-density project administration should remain desktop-first unless a specific mobile use case proves otherwise.

## UI direction: make it look better by making it feel more operational

The current aesthetic is already disciplined: near-black or warm off-white surfaces, a blue/cyan/violet brand ramp, restrained radius, readable chat typography, and an intentionally non-generic desktop-family look. Do not replace it with neon gradients, glassmorphism, or oversized rounded cards. The opportunity is sharper hierarchy and stronger “remote operations console” character.

### 1. Add a compact "Now" layer

Keep Chat as the default screen. Above it—or as a pull-down sheet from the host header—show only urgent/current items:

- one pending tool approval;
- one plan waiting for review;
- a running agent with progress and Stop;
- a failed/succeeded scheduled task;
- unread email only when it is actually actionable.

This removes the need to hunt through Chat, Agents, Scheduler, and Email just to answer “what needs me?” It should be a concise operational layer, not a generic dashboard.

### 2. Make the active desktop/project visually explicit

The connected state should read like a live control link at a glance:

- show the computer name and a clear route pill (`Same Wi-Fi`, `Private VPN`, or `Secure remote`);
- show the active project as a secondary chip on Chat and Agents;
- retain the small model/context meter, but make it a tap target that opens the host details;
- use green only for a truly live connection, amber for reconnecting, and red only for an actually offline state.

The user should never wonder which desktop or project a phone action will affect.

### 3. Treat approvals as a distinct visual event

The existing card gets the safety mechanics right. Strengthen the information design:

- a concise risk label at the top: `Changes files`, `Runs command`, `Sends data`, or `Reads sensitive data`;
- an affected-scope line: project, path, command/destination, and reversibility;
- a visibly separate “Review details” affordance when the diff exceeds the card;
- a single compact countdown ring or bar rather than several competing emphasis treatments;
- preserve Deny-first ordering and make the destructive Allow action visually deliberate rather than simply blue.

### 4. Improve the drawer without reinstating a full tab bar

The drawer is sensible because the composer needs bottom-edge space. Improve it with:

- a small `NOW` section before navigation/recents when something needs attention;
- richer recents: a quiet state icon plus one metadata line, rather than titles alone;
- last-three surface switching (for example: the active chat, active agent, current workspace);
- a visible connection route and project in the desktop footer;
- notification counts only for action-needed states, not general activity.

### 5. Make model locality visible without making it scary

Model selection is a privacy boundary as well as a capability choice. Make that visible with calm, factual microcopy:

- `Qwen3-Coder-30B · Local on STUDIO-PC`
- `GPT-5 · Cloud via [provider]`

Use a small neutral label next to the model name, not a warning color for all cloud use. Reserve amber/red for genuine risk conditions, such as a tool about to send an attachment or a cloud request in a conversation/project explicitly marked sensitive. A model/provider detail sheet can explain route, account, retention controls when known, and how to switch back to a local model.

### 6. Make list screens feel less like independent mini-apps

Agents, Scheduler, Workspace, and Email should share a common screen grammar:

- a compact context line under the title (`STUDIO-PC · Universe Sandbox`);
- one clear primary object per row;
- status as a colored label/dot plus plain-language text, never color alone;
- the same spacing rhythm, row height, and empty-state composition;
- progressive detail: glance in the list, open a sheet/page for the full record.

This will make the app feel like one remote system rather than five desktop views placed in a drawer.

### 7. Give empty and offline states more brand presence

The copy is already unusually good. Visually, use the Anodex mark and one compact connection diagram:

`Phone  — encrypted link —  STUDIO-PC`

It makes the ownership model instantly legible. For Offline, change the middle state to a broken/trying link and show the known route. Do not add decorative illustrations that imply the desktop has cloud synchronization it does not have.

### 8. Use the brand ramp sparingly and intentionally

The blue/cyan/violet ramp should signal an important transition, such as:

- successful first pairing;
- a desktop becoming reachable again;
- a just-completed agent run;
- a short launch/empty-state moment.

Routine cards, buttons, and lists should remain neutral with blue as the primary interaction color. The palette already avoids neon; keep that restraint.

### 9. Accessibility is visual quality

- Preserve the current 48dp hit targets.
- Do not rely on pulsing motion to communicate “waiting”; always pair it with text and a static color/shape cue.
- Validate light mode as deliberately as dark mode; the warm light palette is a differentiator.
- Test at Android large-font settings, especially approval cards, drawer rows, and the chat composer.
- Ensure the contrast of muted text and code blocks remains readable on both themes.

## Suggested delivery order

1. Correct privacy/network/model language everywhere: pairing, host, offline, Settings, README, desktop Remote settings, and model picker.
2. Fix destructive pairing replacement and actionable notification deep links.
3. Create the unified `Now / Needs your attention` layer.
4. Add optional step-up authentication and desktop paired-device management/audit visibility.
5. Decide the opt-in phone-data policy before introducing local chat/file caching, and make local/cloud provider disclosure consistent across desktop and phone.
6. Apply the visual-system improvements screen by screen, beginning with Chat, approvals, Agents, and the drawer.

## Acceptance criteria for the revised product story

- A user can understand that “local models” describes where inference runs, not whether the phone is on home Wi-Fi.
- A user can understand that selecting a cloud model sends the needed request data to that provider, while the desktop↔phone link remains separately encrypted and authenticated.
- A user can understand that an encrypted direct remote route may cross the internet without using Anodex servers as a content relay.
- Marketing never claims that bytes do not reach the phone when the phone is displaying them.
- Every notification about something needing a decision opens exactly that decision.
- No recovery-labeled button destroys a pairing without clear warning and confirmation.
- A user can identify the target desktop, active project, route type, and action risk before executing a consequential action.
- Any future phone cache is opt-in, encrypted at rest, discoverable, and clearable.
