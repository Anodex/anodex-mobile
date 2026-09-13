package dev.anodex.mobile.chat

import android.graphics.BitmapFactory
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The pictures somebody gave their own personalities at the computer, held on the phone.
 *
 * Every custom personality used to draw as initials here, whatever face it had on the
 * desktop, because the desktop stored the picture as a path on its own disk. It now
 * sends a key per personality and the thumbnail on request, and this keeps them.
 *
 * On disk, in the app's cache, named by key. A picture changes rarely and is drawn on
 * every reply, so fetching it once per connection would be paying for the same bytes
 * every time the phone reconnects — and a cached copy is what lets the settings list
 * show faces while the computer is still coming up. The cache is the OS's to clear;
 * losing it costs one fetch.
 *
 * This is a picture of a personality, not the user's data. Nothing in the handoff's
 * "the phone holds no conversations" rule is touched by keeping it.
 */
class PersonalityPictures(private val dir: File) {

    private val _byId = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())

    /** Decoded pictures, by personality id. A personality with none is simply absent. */
    val byId: StateFlow<Map<String, ImageBitmap>> = _byId.asStateFlow()

    /**
     * Bring the held pictures in line with [personalities].
     *
     * Reads from disk what is already there, asks [fetch] for what is not, and drops
     * pictures for personalities that no longer have one. A failed fetch leaves that
     * personality on its initials, which is what it showed before any of this existed.
     */
    suspend fun sync(
        personalities: List<Personality>,
        fetch: suspend (id: String) -> ByteArray?,
    ) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val next = mutableMapOf<String, ImageBitmap>()

        for (personality in personalities) {
            val key = personality.image?.let(::cacheFileName) ?: continue
            val file = File(dir, key)

            val bytes = if (file.isFile) {
                runCatching { file.readBytes() }.getOrNull()
            } else {
                runCatching { fetch(personality.id) }.getOrNull()
                    ?.also { fetched -> runCatching { file.writeBytes(fetched) } }
            } ?: continue

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                // Written by a fetch that returned something undecodable. Removed so the
                // next sync asks again rather than trusting a bad copy for ever.
                file.delete()
                continue
            }
            next[personality.id] = bitmap.asImageBitmap()
        }

        // Anything on disk no current personality names is a picture that was replaced
        // or removed at the computer.
        val wanted = personalities.mapNotNull { it.image?.let(::cacheFileName) }.toSet()
        dir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }

        _byId.value = next
    }
}

/**
 * The key as a file name, or null when it is not safe to be one.
 *
 * The key comes from the computer. It is a UUID and an extension in practice, but a
 * value that could name a file outside the cache — a separator, `..` — is refused
 * rather than trusted, since it is about to be handed to `File`.
 */
internal fun cacheFileName(key: String): String? =
    key.takeIf { it.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,127}""")) && ".." !in it }

/**
 * Pictures by personality id, for [dev.anodex.mobile.ui.components.PersonalityAvatar].
 *
 * A local rather than a parameter, because the avatar is drawn from a transcript row
 * and a settings row alike, and threading a map through every screen between them
 * would touch a dozen signatures to deliver one lookup.
 */
val LocalPersonalityPictures = staticCompositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }
