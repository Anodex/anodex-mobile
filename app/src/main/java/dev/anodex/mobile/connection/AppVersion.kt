package dev.anodex.mobile.connection

/**
 * Whether this build is behind the desktop it is paired to.
 *
 * One of two signals, and the one that does not need the internet. The desktop says
 * what phone build it shipped alongside; this compares. "Up to date" therefore means
 * *matched to this computer*, which is the more useful meaning between two halves of
 * one product talking over a versioned protocol — a phone running ahead of the
 * machine it drives is not obviously a good thing.
 *
 * The other signal is [dev.anodex.mobile.update.Releases], which reads GitHub for
 * what actually exists and is the only one available before the phone has connected
 * to anything. Both are kept because they answer different questions.
 *
 * Fails quiet throughout. A notice nobody asked for has a low tolerance for being
 * wrong: telling someone to update when they need not sends them to reinstall what
 * they already have, and teaches them to ignore the banner — after which the one time
 * it is right, it is useless too.
 */
fun isUpdateAvailable(installed: String, expected: String): Boolean {
    val here = parseVersion(installed) ?: return false
    val there = parseVersion(expected) ?: return false

    for (index in 0..2) {
        if (here[index] != there[index]) return here[index] < there[index]
    }
    return false
}

/**
 * `major.minor.patch`, ignoring any `-preview.3` suffix.
 *
 * Not a semver library, deliberately: these versions are ours, they are always three
 * numbers, and a dependency whose job is to compare three integers is one to justify
 * rather than one to add. The suffix is a label and does not order anything.
 */
private fun parseVersion(value: String): List<Int>? {
    val match = Regex("""^(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:[-+].*)?$""")
        .matchEntire(value.trim()) ?: return null

    return match.groupValues.drop(1).map { it.toInt() }
}
