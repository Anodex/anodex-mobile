package dev.anodex.mobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.anodex.mobile.connection.isUpdateAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where the app is in the business of updating itself. */
sealed interface UpdateState {
    /** Nothing to do, as far as anybody knows. */
    data object Idle : UpdateState

    /** A newer build exists. Nothing has been downloaded. */
    data class Available(val release: Release) : UpdateState

    data class Downloading(val release: Release, val fraction: Float) : UpdateState

    /** Downloaded and verified. Android's installer takes it from here. */
    data class Ready(val release: Release, val file: File) : UpdateState

    data class Failed(val release: Release?, val message: String) : UpdateState
}

/**
 * Finding, fetching and handing over a newer build of this app.
 *
 * A sideloaded Android app cannot replace itself. What it can do is download the new
 * APK and ask the system installer to take over, which is the last step here — the
 * user sees Android's own "update this app?" screen and taps it. That is the honest
 * ceiling for an app distributed outside a store, and it is worth doing because the
 * alternative is a person finding a GitHub page on a phone and driving a browser
 * download by hand.
 *
 * ## What is kept
 *
 * The install is an update in place, not a fresh one: same package, same signing key,
 * so Android keeps the app's data directory. The paired device key lives in there
 * (in the Android Keystore, wrapped), so **pairing survives** — which is the entire
 * reason this exists rather than telling people to uninstall and re-pair.
 *
 * ## What is checked before anything is offered
 *
 * The download URL must be HTTPS on a GitHub host, checked against a fixed set
 * rather than trusted from the response. And the downloaded file must be signed by
 * the same certificate as the app that is running: Android would refuse a mismatch
 * anyway, but refusing here means the user is told *why* instead of watching the
 * system installer fail with nothing useful to say.
 */
class Updater(private val context: Context) {

    /**
     * For asking GitHub a question. A whole-call ceiling is right here: the answer is
     * a few kilobytes, and one that has not arrived in half a minute is not coming.
     */
    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * For fetching ten megabytes, which is a different problem.
     *
     * Deliberately **no** `callTimeout`. That bounds the entire call including the
     * body, so 60 seconds meant any connection slower than about 1.4 Mbps failed
     * every single time, whatever GitHub was doing. The bound that belongs here is
     * on *stalling* — if no bytes arrive for a minute the link is dead — not on how
     * long a large file is allowed to take in total.
     */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Ask GitHub what the newest build is.
     *
     * Returns null when there is nothing newer, when the network is not there, or
     * when the answer cannot be read. All three are the same thing to a caller who
     * did not ask for an update: silence.
     */
    suspend fun check(installedVersion: String): Release? = withContext(Dispatchers.IO) {
        val body = runCatching {
            client.newCall(Request.Builder().url(Releases.LATEST_URL).build()).execute().use {
                if (!it.isSuccessful) return@runCatching null
                it.body?.string()
            }
        }.getOrNull() ?: return@withContext null

        val release = Releases.parseLatest(body) ?: return@withContext null
        release.takeIf { isUpdateAvailable(installedVersion, it.version) }
    }

    /**
     * Fetch the APK, reporting progress as it goes.
     *
     * Written to the app's own cache rather than shared storage: nothing else on the
     * phone has any business reading a half-downloaded executable, and the cache is
     * cleaned up by the system if space runs short.
     */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            if (!Releases.isAllowedAssetUrl(release.apkUrl)) {
                return@withContext Result.failure(IllegalArgumentException("That download isn't from GitHub."))
            }

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }

            // Named for the version rather than reused, so a failed download of one
            // build can never be mistaken for a finished download of another.
            val target = File(dir, "anodex-${release.version}.apk")
            val partial = File(dir, "anodex-${release.version}.apk.part")

            runCatching {
                // Anything left from a previous attempt, including other versions:
                // there is no reason to keep an installer the user did not run.
                dir.listFiles()?.forEach { if (it != target) it.delete() }

                fetch(release.apkUrl).use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        error(describeFailure(response.code))
                    }

                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkBytes
                    var read = 0L

                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                                read += count
                                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }

                // Only becomes the real file once it is whole. A partial APK under the
                // final name is an installer that fails halfway with no explanation.
                check(partial.renameTo(target)) { "Could not finish the download." }

                if (!isSignedLikeThisApp(target)) {
                    target.delete()
                    error("That build is signed by a different key, so it can't update this app.")
                }

                onProgress(1f)
                target
            }.onFailure { partial.delete() }
        }

    /**
     * Ask for the file, and try again if the answer was a shrug.
     *
     * A ten-megabyte download over a phone connection meets transient failures as a
     * matter of course, and GitHub itself returns the odd 502 or 504 — one did, which
     * is what sent somebody here. Giving up on the first of those turns a working
     * update into a dead button.
     *
     * Only failures worth repeating are repeated. A 404 means the asset is not there
     * and asking four more times will not put it there.
     */
    private fun fetch(url: String): Response {
        var lastFailure: Exception? = null

        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_BACKOFF_MS * attempt)

            val response = try {
                downloadClient.newCall(Request.Builder().url(url).build()).execute()
            } catch (e: IOException) {
                lastFailure = e
                return@repeat
            }

            if (response.isSuccessful || !isWorthRetrying(response.code)) return response
            response.close()
            lastFailure = IOException(describeFailure(response.code))
        }

        throw lastFailure ?: IOException("The download failed.")
    }

    /**
     * Say which kind of failure it was.
     *
     * "The download failed" is true of every one of these and useful for none: a
     * server having a moment and a file that does not exist need completely different
     * things from the person reading it.
     */
    private fun describeFailure(code: Int): String = when {
        code in 500..599 -> "GitHub is having trouble right now. Try again in a minute."
        code == 404 -> "That build is no longer on GitHub."
        code == 429 -> "Too many requests to GitHub. Try again shortly."
        else -> "The download failed (HTTP $code)."
    }

    /** Worth another go: a server stumble, a rate limit, or a timeout. */
    private fun isWorthRetrying(code: Int): Boolean =
        code in 500..599 || code == 408 || code == 429

    /**
     * Hand the file to Android's installer.
     *
     * `FileProvider`, because a `file://` URI to another app has been illegal since
     * Android 7 and throws rather than degrading.
     */
    fun install(file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)

        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Started from outside an activity when the app is backgrounded.
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Whether this device will even let the app ask.
     *
     * From Android 8 installing is a per-app permission the user grants in system
     * settings, and there is no prompt for it from here — the app can only send them
     * to the right page. Knowing in advance is what lets the UI say so before the
     * download rather than after.
     */
    fun canRequestInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** The system page where the user turns that permission on. */
    fun installPermissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Whether the downloaded APK carries this app's own signing certificate.
     *
     * Compared as digests of the certificate bytes rather than by trusting a name:
     * the certificate is the identity. A build signed by anything else cannot update
     * this app — Android enforces that — so catching it here only changes whether the
     * user is told something useful.
     */
    private fun isSignedLikeThisApp(apk: File): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val downloaded = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return false
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)

        val a = signatureDigests(downloaded)
        val b = signatureDigests(installed)
        a.isNotEmpty() && a == b
    }.getOrDefault(false)

    private fun signatureDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().filterNotNull()
            .map { digest.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            .toSet()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** Enough to ride out a stumble, few enough that a real failure is prompt. */
        const val DOWNLOAD_ATTEMPTS = 4

        /** Multiplied by the attempt, so the gaps widen rather than hammering. */
        const val RETRY_BACKOFF_MS = 1_500L
    }
}
