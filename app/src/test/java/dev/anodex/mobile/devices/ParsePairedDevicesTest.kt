package dev.anodex.mobile.devices

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsePairedDevicesTest {

    @Test
    fun `devices arrive with which one is this phone`() {
        val devices = parsePairedDevices(
            Json.parseToJsonElement(
                """[{"deviceId":"a","name":"Pixel","pairedAtEpochMs":1,"lastSeenEpochMs":20,"isThisDevice":true},
                   {"deviceId":"b","name":"S7","pairedAtEpochMs":2,"lastSeenEpochMs":10,"isThisDevice":false}]""",
            ),
        )

        assertEquals(listOf("Pixel", "S7"), devices.map { it.name })
        assertEquals(listOf(true, false), devices.map { it.isThisDevice })
        assertEquals(20L, devices[0].lastSeenEpochMs)
    }

    @Test
    fun `devices say whether they are connected, and a computer that does not say means no`() {
        val devices = parsePairedDevices(
            Json.parseToJsonElement(
                """[{"deviceId":"a","name":"Pixel","connected":true},{"deviceId":"b","name":"S7"}]""",
            ),
        )

        assertEquals(listOf(true, false), devices.map { it.connected })
    }

    @Test
    fun `a connected device says how it reaches the computer, when the computer says`() {
        val devices = parsePairedDevices(
            Json.parseToJsonElement(
                """[{"deviceId":"a","connected":true,"route":"internet"},
                   {"deviceId":"b","connected":true,"route":null},
                   {"deviceId":"c","connected":true,"route":"home"}]""",
            ),
        )

        assertEquals(
            listOf("Connected now · from outside", "Connected now", "Connected now · home network"),
            devices.map { it.connectedLabel },
        )
    }

    @Test
    fun `a computer without the channel, or a malformed row, is no devices rather than a failure`() {
        assertTrue(parsePairedDevices(Json.parseToJsonElement("""{"ok":false}""")).isEmpty())
        assertEquals(1, parsePairedDevices(Json.parseToJsonElement("""[{"name":"no id"},{"deviceId":"x"}]""")).size)
    }
}
