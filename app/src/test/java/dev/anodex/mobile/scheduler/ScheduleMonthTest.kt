package dev.anodex.mobile.scheduler

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The computer's `ScheduleMonthWire`, as `scheduler:month` sends it. */
class ScheduleMonthTest {

    private val wire = """
        {"year":2026,"month":9,
         "days":[{"day":16,"entries":[{"key":"t1:next:1","taskId":"t1","name":"Weekly review","at":1792180800000,
                   "reminder":false,"repeats":"Every Fri at 4:00 PM","state":"upcoming"}]}],
         "every":[{"taskId":"t2","name":"Water the plants","reminder":true,"cadence":"Every day","hour":18,"minute":0}],
         "everyByDay":[{"day":16,"entries":[{"key":"t2:next:2","taskId":"t2","name":"Water the plants","at":1792195200000,
                   "reminder":true,"repeats":"Every day at 6:00 PM","state":"upcoming"}]}]}
    """.trimIndent()

    @Test
    fun `reads a month as the computer worked it out`() {
        val month = parseMonth(Json.parseToJsonElement(wire))!!
        assertEquals(2026, month.year)
        assertEquals(9, month.month)
        assertEquals("Weekly review", month.days[16]!!.single().name)
        assertEquals("Every Fri at 4:00 PM", month.days[16]!!.single().repeats)
        assertEquals("Every day", month.every.single().cadence)
        assertEquals(18, month.every.single().hour)
        assertEquals(true, month.everyByDay[16]!!.single().reminder)
    }

    @Test
    fun `a computer without the channel gives nothing, and a broken entry is dropped`() {
        assertNull(parseMonth(null))
        val broken = parseMonth(Json.parseToJsonElement("""{"year":2026,"month":9,"days":[{"day":3,"entries":[{"name":"no id"}]}],"every":[],"everyByDay":[]}"""))!!
        assertEquals(emptyList<MonthEntry>(), broken.days[3])
    }
}
