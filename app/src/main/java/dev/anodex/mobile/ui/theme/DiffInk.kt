package dev.anodex.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * What a line of a diff means, stripped of where it came from.
 *
 * The phone draws diffs in two places and gets them from two directions. The
 * approval card computes one locally, from the before and after a tool is asking
 * permission to write; the file diff screen is handed one the computer drew, out
 * of a checkpoint. They have their own line types and their own layouts, and that
 * is fine — one is a glance inside a card, the other is a screen to read.
 *
 * What is not fine is each deciding for itself what green means. Green for an
 * addition is a fact about this app, not about either screen, and a rule stated
 * twice is the shape of bug this codebase keeps finding: the two copies stay in
 * step until somebody changes one of them.
 */
enum class DiffTone { ADDED, REMOVED, CONTEXT }

/** The ink a diff line is written in. */
fun AnodexColors.diffInk(tone: DiffTone): Color = when (tone) {
    DiffTone.ADDED -> accentGreenInk
    DiffTone.REMOVED -> dangerInk
    DiffTone.CONTEXT -> textFaint
}

/**
 * The wash behind a diff line, where there is room for one.
 *
 * Only the full screen uses it: inside the approval card the rows sit on an
 * elevated surface already, and a second tint on top of that reads as a defect
 * rather than as meaning. Transparent for context, so unchanged lines stay out of
 * the way of the ones that moved.
 */
fun AnodexColors.diffWash(tone: DiffTone): Color = when (tone) {
    DiffTone.ADDED -> accentGreen.copy(alpha = DIFF_WASH_ALPHA)
    DiffTone.REMOVED -> danger.copy(alpha = DIFF_WASH_ALPHA)
    DiffTone.CONTEXT -> Color.Transparent
}

/** The desktop's `color-mix(… 14%, transparent)`, copied rather than guessed at. */
private const val DIFF_WASH_ALPHA = 0.14f
