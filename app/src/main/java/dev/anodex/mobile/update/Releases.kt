package dev.anodex.mobile.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A build of this app published on GitHub, and where to get it. */
data class Release(
    /** `0.23.0`, with the tag's `v` and `-preview.31` stripped. */
    val version: String,
    /** The tag as published, for showing and for the release page link. */
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkBytes: Long,
)

/**
 * The releases of `Anodex/anodex-mobile`, read straight from GitHub.
 *
 * Possible because the repository is public: the releases endpoint of a public repo
 * is anonymous, and so is the asset download. Neither needs a credential, which
 * matters because a credential shipped inside a distributed binary is not a secret —
 * the app has to be able to unlock it, so anyone holding the app can follow the same
 * path. That constraint is why the handshake carries a version at all, and it is why
 * this could not have been built while the repos were private.
 *
 * The two signals do different jobs and both are kept. The desktop's is authoritative
 * about *what this computer expects*, which is the version pairing is tested against.
 * GitHub's is authoritative about *what exists*, and it is the only one available
 * before the phone has ever connected.
 */
object Releases {
    /**
     * Pinned, not composed from a setting.
     *
     * The one URL this app will ever download an executable from, so it is a constant
     * in the source rather than anything a response could redirect. Assets are checked
     * against this host too — a release body cannot point the installer elsewhere.
     */
    const val LATEST_URL = "https://api.github.com/repos/Anodex/anodex-mobile/releases?per_page=20"

    const val RELEASES_PAGE = "https://github.com/Anodex/anodex-mobile/releases/latest"

    /** Where an APK may come from. Anything else is treated as no release at all. */
    private val ALLOWED_ASSET_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest published release, or null if the answer is unusable.
     *
     * Reads the release *list*, not `/releases/latest`. That endpoint means "the newest
     * release that is not a prerelease and not a draft" — so with every build published
     * as a preview it answered 404, the app read that as "nothing to offer", and the
     * update banner could never appear on any version. It failed silently and
     * correctly, which is exactly why it went unnoticed through three releases.
     *
     * The list is newest-first and includes previews, so the first entry with a usable
     * APK is the answer regardless of how a release happens to be flagged. Drafts are
     * skipped: they are not published, and an anonymous caller cannot see them anyway.
     *
     * Unusable still covers more than malformed — a release with no APK attached, or an
     * asset hosted somewhere other than GitHub. Those are "nothing to offer", a state
     * this app already handles, rather than an error worth showing somebody who did not
     * ask for an update.
     */
    fun parseLatest(body: String): Release? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null

        // Accepts a bare object too, so a caller pointed at a single-release endpoint
        // still works. The list is what this asks for; the object is what it used to.
        val candidates = when (parsed) {
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(parsed)
            else -> return null
        }

        return candidates.asSequence()
            .filter { it["draft"]?.jsonPrimitive?.contentOrNull != "true" }
            .mapNotNull { releaseOf(it) }
            .firstOrNull()
    }

    private fun releaseOf(root: JsonObject): Release? {
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = versionOf(tag) ?: return null

        val asset = (root["assets"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: return null

        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!isAllowedAssetUrl(url)) return null

        return Release(
            version = version,
            tag = tag,
            notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            apkUrl = url,
            apkBytes = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    /**
     * `v0.23.0-preview.31` to `0.23.0`.
     *
     * The numeric part is what orders releases and what the desktop reports; the
     * preview suffix is a label. A tag that is not shaped like a version at all
     * returns null rather than a guess — being told to update to something
     * unparseable is worse than not being told.
     */
    fun versionOf(tag: String): String? {
        val match = Regex("""^v?(\d{1,6}\.\d{1,6}\.\d{1,6})(?:[-+].*)?$""").find(tag.trim())
        return match?.groupValues?.get(1)
    }

    /**
     * HTTPS, and a GitHub host.
     *
     * The check is on the host *exactly*, not on a suffix: `github.com.example.com`
     * ends with nothing that matters and starts with everything that reassures, which
     * is the whole trick. Parsed rather than pattern-matched for the same reason.
     */
    fun isAllowedAssetUrl(url: String): Boolean {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        return parsed.host?.lowercase() in ALLOWED_ASSET_HOSTS
    }
}
