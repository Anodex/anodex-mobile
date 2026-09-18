package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.ui.components.AnodexIcon

/**
 * The parts of Settings, as the desktop divides them.
 *
 * One screen per concern rather than one long scroll. The desktop already made this
 * split and people move between the two — a setting that lives under "AI & Models"
 * on the computer should not be three rows below the version number on the phone.
 *
 * Sections that cannot change anything yet say so rather than being hidden. A
 * section that exists on the computer and is simply missing here reads as the phone
 * being unfinished; one that explains where the setting lives reads as an answer.
 *
 * What that note used to say was that `settings:` and `memory:` are "denied to a
 * phone deliberately — see `channelPolicy.ts`", and that opening them was a
 * decision about the protocol. Checked against the desktop on 2026-09-17: it is
 * not true, and may never have been. `decideRemoteChannel` refuses three things —
 * editing the connection, the terminal, and critical thinking — plus a handful of
 * native pickers that would open a window on an empty desk. `settings:get`,
 * `settings:update` and every `memory:` channel are allowed.
 *
 * So these are not protocol decisions waiting on a negotiation. They are screens
 * nobody has written yet, and the difference matters: the first reads as a rule
 * and stops people, the second reads as a list and gets done.
 */
enum class SettingsSection(
    val label: String,
    val icon: AnodexIcon,
    /** The one-line summary under the label on the index. */
    val summary: String,
) {
    PROFILE(
        label = "Profile",
        icon = AnodexIcon.USER,
        summary = "You, and what you have been doing",
    ),
    APPEARANCE(
        label = "Appearance",
        icon = AnodexIcon.PALETTE,
        summary = "How this app looks",
    ),
    MEMORY(
        label = "Memory",
        icon = AnodexIcon.MEMORY,
        summary = "What Anodex remembers, and what you tell it to",
    ),
    AI_MODELS(
        label = "AI & Models",
        icon = AnodexIcon.CPU,
        summary = "How Anodex answers, and which model runs",
    ),
    NOTIFICATIONS(
        label = "Notifications",
        icon = AnodexIcon.ALERT,
        summary = "What the computer may interrupt you for",
    ),
    REMOTE(
        label = "Remote",
        icon = AnodexIcon.SMARTPHONE,
        summary = "This phone and the computer it drives",
    ),
    ARCHIVE(
        label = "Archive",
        icon = AnodexIcon.ARCHIVE,
        summary = "What you put away, and the only place to throw it out",
    ),
    DIAGNOSTICS(
        label = "Diagnostics",
        icon = AnodexIcon.ACTIVITY,
        summary = "What went wrong, and how to say so",
    ),
    ABOUT(
        label = "About",
        icon = AnodexIcon.INFO,
        summary = "Version and updates",
    ),
}

/** How the app picks between the light and dark palettes. */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("System", "Follow the phone's own setting"),
    DARK("Dark", "Always dark"),
    LIGHT("Light", "Always light"),
}
