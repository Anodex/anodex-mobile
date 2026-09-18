package dev.anodex.mobile.settings

import dev.anodex.mobile.transport.unwrap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The setting that decides whether the computer asks before it acts.
 *
 * Two things are pinned here, and both are failures that would be silent.
 *
 * The first is the envelope. `settings:get` and `settings:update` answer with
 * `AppSettings` itself, not the `ok(value)` union most channels use --
 * `protocol/anodex-protocol.json` records both as `{"$ref": "AppSettings"}`.
 * Unwrapping out of habit yields null, and a null permission mode draws a screen
 * with nothing selected and no error, because nothing failed. `settings:get-profile`
 * shipped with exactly that bug; see `ProfileEnvelopeTest`.
 *
 * The second is the wire value for "Edits". The desktop stores `full` and labels it
 * "Edits: allow file edits and checks, ask commands". Sending `edits` because that
 * is what the button says would be rejected or, worse, quietly ignored -- and the
 * phone would then show a mode the computer is not in.
 */
class PermissionModeTest {

    /** What the computer sends back: the settings themselves, no wrapper. */
    private val rawSettings: JsonObject = buildJsonObject {
        put("general", buildJsonObject {
            put("permissionMode", "untethered")
            put("confirmDestructive", true)
        })
    }

    @Test
    fun `unwrapping the settings answer yields nothing`() {
        // The defect, stated directly. Not an error condition -- a correct function
        // given the wrong input, which is why it fails without saying anything.
        assertNull(rawSettings.unwrap())
    }

    @Test
    fun `reading it directly finds the mode`() {
        val general = rawSettings["general"] as JsonObject
        assertEquals("\"untethered\"", general["permissionMode"]?.toString())
    }

    @Test
    fun `every mode the desktop can store is one this phone can show`() {
        // The three values in `PermissionMode` in `settings.types.ts`. A computer
        // holding a mode this phone does not know draws an empty selection, which
        // reads as "not set" rather than "not understood".
        for (wire in listOf("ask", "full", "untethered")) {
            assertNotNull("no phone-side mode for \"$wire\"", PermissionMode.fromWire(wire))
        }
    }

    @Test
    fun `Edits is stored as full, whatever the button says`() {
        // The rename that must not happen by accident. The label moved on; the
        // stored value did not, and changing it from a phone screen would be a
        // migration of somebody's settings file.
        assertEquals("full", PermissionMode.EDITS.wire)
        assertEquals("Edits", PermissionMode.EDITS.label)
    }

    @Test
    fun `an unknown mode is nothing rather than the safest guess`() {
        // The tempting default is ASK, because it is the one that asks. It is also
        // the one claim somebody would act on without checking -- a phone that
        // cannot read the setting must not say the computer will ask first.
        assertNull(PermissionMode.fromWire("something-added-later"))
        assertNull(PermissionMode.fromWire(null))
    }
}
