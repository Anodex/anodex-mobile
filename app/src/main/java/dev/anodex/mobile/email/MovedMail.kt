package dev.anodex.mobile.email

/**
 * What the undo strip says about mail that has just been moved.
 *
 * One message is named, because the name is the whole reason the strip is worth
 * reading: "Archived" on its own tells somebody what happened, and "Archived
 * *this*" tells them whether it was the one they meant. That is the difference
 * between a notice and an undo.
 *
 * Several are counted instead. Five subjects will not fit on a strip and
 * truncating four of them to make room communicates less than a number does --
 * and anyone who has just selected five rows knows which five.
 *
 * The verb is passed in rather than derived, because "Archived" and "Deleted"
 * are not interchangeable words to somebody deciding whether to reach for Undo
 * in the six seconds they have.
 */
fun movedMailText(verb: String, threads: List<EmailThread>): String = when (threads.size) {
    0 -> verb
    1 -> "$verb “${threads[0].subject}”."
    else -> "$verb ${threads.size} messages."
}
