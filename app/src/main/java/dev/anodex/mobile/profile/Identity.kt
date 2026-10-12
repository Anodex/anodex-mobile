package dev.anodex.mobile.profile

import androidx.compose.ui.graphics.Color

/**
 * The six avatar gradients, from the mark's own colours.
 *
 * The desktop's `AVATAR_LOOKS` (`src/shared/userIdentity.ts`), in the same order:
 * the person picks one in the computer's welcome and both screens draw it. The
 * design contract does not carry these, so change both together.
 */
val AVATAR_LOOKS: List<Pair<Color, Color>> = listOf(
    Color(0xFF7C5CFF) to Color(0xFF4F8CFF),
    Color(0xFF38BDF8) to Color(0xFF4F8CFF),
    Color(0xFF3CCF7A) to Color(0xFF0EA5A4),
    Color(0xFFF5A623) to Color(0xFFF05A5A),
    Color(0xFFEC4899) to Color(0xFF7C5CFF),
    Color(0xFF64748B) to Color(0xFF334155),
)

/** The look's two colours, falling back to the mark's for an index this app does not know. */
fun avatarLook(index: Int): Pair<Color, Color> = AVATAR_LOOKS.getOrElse(index) { AVATAR_LOOKS[0] }

/** The name a fresh desktop starts with, which is nobody's. */
const val PLACEHOLDER_NAME = "Anodex User"

/**
 * The person's own first name, or null when they have not given one.
 *
 * Null for the placeholder and for this app's "You" stand-in too: a greeting is
 * only worth showing when it is theirs. The desktop's `firstName`.
 */
fun firstNameOf(displayName: String?): String? {
    val name = displayName?.trim().orEmpty()
    if (name.isEmpty() || name == PLACEHOLDER_NAME || name == "You") return null
    return name.split(Regex("\\s+")).first()
}

/** Up to two initials, for an avatar with no photo. Empty when there is no name. */
fun initialsOf(displayName: String?): String {
    if (firstNameOf(displayName) == null) return ""
    return displayName!!.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .take(2).joinToString("") { it.first().uppercase() }
}
