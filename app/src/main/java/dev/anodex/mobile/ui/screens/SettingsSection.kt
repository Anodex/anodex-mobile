package dev.anodex.mobile.ui.screens

import dev.anodex.mobile.ui.components.AnodexIcon

/**
 * The parts of Settings, as the desktop divides them.
 *
 * One screen per concern rather than one long scroll. The desktop already made this
 * split and people move between the two — a setting that lives under "AI & Models"
 * on the computer should not be three rows below the version number on the phone.
 *
 * Two of these do not have anything to change yet, and say so rather than being
 * hidden. A section that exists on the computer and is simply missing here reads as
 * the phone being unfinished; one that explains where the setting lives reads as an
 * answer. `settings:` and `memory:` are both denied to a phone deliberately — see
 * `channelPolicy.ts` — so opening them is a decision about the protocol, not an
 * afternoon's UI work.
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
        summary = "Your name and account, at the computer",
    ),
    APPEARANCE(
        label = "Appearance",
        icon = AnodexIcon.PALETTE,
        summary = "How this app looks",
    ),
    MEMORY(
        label = "Memory",
        icon = AnodexIcon.MEMORY,
        summary = "What Anodex remembers, at the computer",
    ),
    AI_MODELS(
        label = "AI & Models",
        icon = AnodexIcon.CPU,
        summary = "How Anodex answers, and which model runs",
    ),
    REMOTE(
        label = "Remote",
        icon = AnodexIcon.SMARTPHONE,
        summary = "This phone and the computer it drives",
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
