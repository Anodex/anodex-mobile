package dev.anodex.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app will accept as "here is a newer build, download it".
 *
 * This is the one place the app takes a URL out of a network response and then hands
 * what it finds there to the system installer, so the checks that matter are the ones
 * that refuse. A release that cannot be read is not an error worth showing anybody —
 * it is simply nothing to offer — which is why almost every failure here is null.
 */
class ReleasesTest {

    private fun release(
        tag: String = "v0.23.0-preview.31",
        assetName: String = "anodex-0.23.0.apk",
        url: String = "https://github.com/Anodex/anodex-mobile/releases/download/v0.23.0/a.apk",
        size: Long = 9920526,
    ) = """
        {
          "tag_name": "$tag",
          "body": "Install straight over 0.22.0.",
          "assets": [
            { "name": "$assetName", "size": $size, "browser_download_url": "$url" }
          ]
        }
    """.trimIndent()

    @Test
    fun `reads the version, the notes and the APK`() {
        val parsed = Releases.parseLatest(release())!!

        assertEquals("0.23.0", parsed.version)
        assertEquals("v0.23.0-preview.31", parsed.tag)
        assertEquals(9920526, parsed.apkBytes)
        assertTrue(parsed.notes.startsWith("Install straight over"))
    }

    @Test
    fun `the preview suffix is a label, not part of the version`() {
        // The desktop reports "0.23.0" and compares numerically. A tag that kept its
        // suffix would never match it, so every release would look like an update.
        assertEquals("0.23.0", Releases.versionOf("v0.23.0-preview.31"))
        assertEquals("0.23.0", Releases.versionOf("0.23.0"))
        assertNull(Releases.versionOf("nightly"))
    }

    @Test
    fun `a release with no APK attached is nothing to offer`() {
        // Real case: the release is published before CI has uploaded the build.
        val json = """{ "tag_name": "v0.23.0", "assets": [] }"""
        assertNull(Releases.parseLatest(json))
    }

    @Test
    fun `an asset that is not an APK is ignored`() {
        assertNull(Releases.parseLatest(release(assetName = "checksums.txt")))
    }

    @Test
    fun `a download pointed somewhere other than GitHub is refused`() {
        // The whole point of the check: the URL arrives in a network response, and
        // what is at the end of it gets handed to the system installer.
        assertNull(Releases.parseLatest(release(url = "https://example.com/a.apk")))
    }

    @Test
    fun `a lookalike host does not pass for GitHub`() {
        // The host is compared exactly, not by suffix or substring. This one ends
        // with nothing that matters and starts with everything that reassures.
        assertFalse(Releases.isAllowedAssetUrl("https://github.com.example.com/a.apk"))
        assertFalse(Releases.isAllowedAssetUrl("https://notgithub.com/a.apk"))
        assertTrue(Releases.isAllowedAssetUrl("https://github.com/Anodex/anodex-mobile/x.apk"))
        assertTrue(Releases.isAllowedAssetUrl("https://objects.githubusercontent.com/x.apk"))
    }

    @Test
    fun `plain http is refused even on GitHub`() {
        assertFalse(Releases.isAllowedAssetUrl("http://github.com/a.apk"))
    }

    @Test
    fun `garbage is nothing to offer, not a crash`() {
        assertNull(Releases.parseLatest(""))
        assertNull(Releases.parseLatest("not json"))
        assertNull(Releases.parseLatest("[]"))
        assertNull(Releases.parseLatest("""{ "message": "Not Found" }"""))
    }
}
