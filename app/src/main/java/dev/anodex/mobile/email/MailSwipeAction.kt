package dev.anodex.mobile.email

/**
 * What a swipe does to a message, per direction.
 *
 * Both directions used to archive, which is what Gmail ships. That was the safe
 * default rather than the right one: a gesture that can only ever do one thing
 * wastes half of itself, and the owner asked for the two acts they actually use
 * — throw it away one way, file it the other.
 *
 * So it is a setting. The reason it is a setting rather than a fixed pair is
 * that people disagree about which side means what, strongly, and there is no
 * answer that is correct for everybody. Muscle memory built in another mail app
 * is worth more than any argument made here.
 *
 * [NOTHING] is not padding. A swipe that does something irreversible-ish is a
 * gesture a sleeve can start, and somebody who has been bitten once should be
 * able to switch a direction off entirely rather than pick the lesser of two
 * actions they did not want.
 */
enum class MailSwipeAction(val label: String, val detail: String) {
    ARCHIVE(
        "Archive",
        "Out of the inbox, still in All Mail. Undone from the strip that appears.",
    ),
    DELETE(
        "Delete",
        "To your trash, recoverable everywhere. Undone from the strip that appears.",
    ),
    NOTHING(
        "Nothing",
        "The row stays where it is.",
    ),
}
