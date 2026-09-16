package dev.anodex.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A crash report kept on the phone outlives the update that fixed the crash, so it has
 * to say which build it happened on. Seen on the test phone: the launch crash fixed in
 * 0.80.5 was still shown as the last crash while running 0.80.9, with nothing in the
 * report to tell the two apart.
 */
class CrashReportTest {

    @Test
    fun `the report names the app build, the phone and the thread`() {
        val report = crashReport(
            at = "2026-09-15 03:57:34",
            app = "0.80.9 (8009)",
            device = "Google sdk_gphone64_x86_64",
            android = "15 (API 35)",
            threadName = "main",
            stack = "java.lang.IllegalStateException: boom\n\tat dev.anodex.mobile.Thing.go\n",
        )

        assertEquals(
            """
            Anodex Mobile crash
            when: 2026-09-15 03:57:34
            app: 0.80.9 (8009)
            device: Google sdk_gphone64_x86_64
            android: 15 (API 35)
            thread: main

            java.lang.IllegalStateException: boom
            ${'\t'}at dev.anodex.mobile.Thing.go
            """.trimIndent() + "\n",
            report,
        )
    }
}
