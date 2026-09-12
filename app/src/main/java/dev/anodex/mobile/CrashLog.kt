package dev.anodex.mobile

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash where the user can read it back.
 *
 * There is no Android toolchain on the machine this app is built from, so there is
 * no `adb logcat` and no debugger — the only channel for a stack trace is the person
 * holding the phone. Without this, a crash arrives as "the app closes when I open
 * it", and the fix is a guess.
 *
 * That is not hypothetical. The foreground service added in v0.12 declared a service
 * type whose prerequisite permissions the app does not hold, so `startForeground`
 * threw on Android 14, the process died, and every relaunch reconnected and did it
 * again. The report available was that it closed itself.
 *
 * One file, overwritten each time. A crash history would need managing; the last one
 * is what matters, and the next launch is when it can be read.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"

    /**
     * Where a crash goes once it has been shown.
     *
     * Dismissing used to delete it, which meant the one artefact worth attaching
     * to a bug report existed only on the screen that interrupted you, and only
     * until you tapped past it. Kept here instead so Diagnostics can offer it
     * later, when somebody has the patience to file the report.
     */
    private const val SEEN_FILE_NAME = "previous-crash.txt"

    /**
     * Record uncaught exceptions from every thread, then let the system carry on.
     *
     * The default handler is always called afterwards. Swallowing it would leave the
     * process alive in whatever broken state caused the crash, which is worse than
     * dying — and it would hide the crash from Android's own reporting too.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Wrapped: a failure while writing the log must not replace the real
            // exception with a confusing one from the crash handler itself.
            runCatching { write(appContext, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash, or null if the app has not crashed since it was installed. */
    fun read(context: Context): String? =
        runCatching {
            val file = file(context)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        }.getOrNull()

    /**
     * Called once the user has seen it, so the crash screen does not appear again.
     *
     * The report is moved rather than deleted: it stops interrupting, and stays
     * available to Diagnostics for as long as it is the most recent one.
     */
    fun clear(context: Context) {
        runCatching {
            val fresh = file(context)
            if (fresh.exists()) {
                val seen = seenFile(context)
                seen.delete()
                if (!fresh.renameTo(seen)) fresh.delete()
            }
        }
    }

    /** The last crash this app recorded, shown or not. Null if it has never crashed. */
    fun lastRecorded(context: Context): String? =
        read(context) ?: runCatching {
            seenFile(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** Throw the kept copy away. Only Diagnostics offers this, and only deliberately. */
    fun forget(context: Context) {
        runCatching {
            file(context).delete()
            seenFile(context).delete()
        }
    }

    private fun seenFile(context: Context): File = File(context.filesDir, SEEN_FILE_NAME)

    private fun write(context: Context, threadName: String, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // The device and version are here because the crashes worth catching this way
        // are the version-dependent ones — a system API that behaves differently on
        // one Android release is exactly what cannot be reproduced from a desk.
        file(context).writeText(
            buildString {
                appendLine("Anodex Mobile crash")
                appendLine("when: $at")
                appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("thread: $threadName")
                appendLine()
                append(stack.toString())
            }
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
