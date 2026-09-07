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
    const val LATEST_URL = "https://api.github.com/repos/Anodex/anodex-mobile/releases/latest"

    const val RELEASES_PAGE = "https://github.com/Anodex/anodex-mobile/releases/latest"

    /** Where an APK may come from. Anything else is treated as no release at all. */
    private val ALLOWED_ASSET_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest published release, or null if the answer is unusable.
     *
     * Unusable covers more than malformed: a release with no APK attached, a draft, or
     * an asset hosted somewhere other than GitHub. All of those are "there is nothing
     * to offer", which is a state this app already handles, rather than an error worth
     * showing somebody who did not ask for an update.
     */
    fun parseLatest(body: String): Release? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null

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
