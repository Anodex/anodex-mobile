package dev.anodex.mobile.connection

/**
 * Whether this build is behind the desktop it is paired to.
 *
 * Neither end asks GitHub. The repository is private, so checking its releases from
 * an installed app needs a credential — and a credential inside a distributed app is
 * not a secret, because the app has to be able to unlock it. That was decided for the
 * desktop's own updater and it applies unchanged here.
 *
 * So the desktop says what phone build it shipped alongside, and this compares. "Up
 * to date" therefore means *matched to this computer*, which is the more useful
 * meaning anyway: the two talk over a versioned protocol, and a phone running ahead
 * of the machine it drives is not obviously a good thing.
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
