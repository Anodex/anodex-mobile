package dev.anodex.mobile.profile

import dev.anodex.mobile.transport.AnodexSocket
import dev.anodex.mobile.transport.unwrap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Who this is, and what they have done.
 *
 * Two reads that the phone's Profile screen is built from. Both are reads of
 * numbers the computer already keeps — nothing here is measured on the phone, and
 * nothing here is writable from it.
 */
data class UserProfile(
    val displayName: String,
    /** Base64 PNG/JPEG, or null. Rendered at the size of a thumbnail, never full bleed. */
    val avatarBase64: String?,
    val planTier: String,
    val accountStatus: String,
)

/** How many times a tool has been reached for, ever. */
data class ToolUse(val name: String, val count: Int)

/** One day's generation activity, local time. */
data class DayOfUse(val date: String, val tokens: Long, val generations: Int)

/**
 * The lifetime picture.
 *
 * Every field is nullable-safe rather than defaulted at the parse site, because a
 * zero and an absence look identical once they reach a screen — and this app has
 * already shipped one meter showing a percentage nobody measured.
 */
data class UsageProfile(
    val lifetimeTokens: Long,
    val lifetimeGenerations: Int,
    val sessionCount: Int,
    val currentStreakDays: Int,
    val longestStreakDays: Int,
    val peakDay: DayOfUse?,
    val peakHour: Int?,
    val longestGenerationMs: Long,
    val favouriteModelName: String?,
    /** Oldest first, one per day that has ever had activity. */
    val dailyActivity: List<DayOfUse>,
    /** Most reached for, first. */
    val mostUsedTools: List<ToolUse>,
) {
    /** Nothing recorded yet, as distinct from a profile that failed to load. */
    val isEmpty: Boolean get() = lifetimeGenerations == 0 && lifetimeTokens == 0L

    /**
     * The tail of [dailyActivity], padded to [days] with the days that had none.
     *
     * The computer only stores days that had activity, so a gap in the list is a
     * day of silence rather than missing data — plotting the list as-is would draw
     * a fortnight off work as a continuous line.
     */
    fun recentDays(days: Int, today: java.time.LocalDate): List<DayOfUse> {
        val byDate = dailyActivity.associateBy { it.date }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong()).toString()
            byDate[date] ?: DayOfUse(date, 0L, 0)
        }
    }
}

/**
 * Reads the profile and the usage numbers from the computer.
 *
 * `settings:get-profile` exists specifically for this: the rest of `settings:` is
 * denied to a phone, because that blob carries the permission mode and the model
 * directory. A name and an avatar carry neither.
 */
class ProfileReader(private val socket: AnodexSocket) {

    suspend fun user(): UserProfile? {
        // Not unwrapped. `settings:get-profile` answers with the profile itself, the
        // way `models:get-state` does — `protocol/anodex-protocol.json` records it as
        // `{"$ref": "ProfileSettings"}` rather than a Result union, and that artifact
        // is the place to check before assuming.
        //
        // Unwrapping it anyway is what shipped: `unwrap` finds no `ok: true`, returns
        // null, and the screen shows an em dash where a name should be — with no
        // error, because nothing failed. The context ring had this exact bug in the
        // other direction, which is why `unwrap` is applied per channel and says so.
        val fields = socket.invoke(CHANNEL_PROFILE)?.asObject() ?: return null
        return UserProfile(
            displayName = fields.string("displayName")?.takeIf { it.isNotBlank() } ?: "You",
            avatarBase64 = fields.string("avatarBase64")?.takeIf { it.isNotBlank() },
            planTier = fields.string("planTier").orEmpty(),
            accountStatus = fields.string("accountStatus").orEmpty(),
        )
    }

    suspend fun usage(): UsageProfile? {
        // This one *is* wrapped — `stats:get-usage-profile` returns `ok(...)`. The two
        // reads on this screen differ, which is the whole reason to check rather than
        // match the neighbour.
        val fields = socket.invoke(CHANNEL_USAGE).unwrap()?.asObject() ?: return null
        return UsageProfile(
            lifetimeTokens = fields.long("lifetimeTokens") ?: 0L,
            lifetimeGenerations = fields.int("lifetimeGenerations") ?: 0,
            sessionCount = fields.int("sessionCount") ?: 0,
            currentStreakDays = fields.int("currentStreakDays") ?: 0,
            longestStreakDays = fields.int("longestStreakDays") ?: 0,
            peakDay = (fields["peakDay"] as? JsonObject)?.let {
                DayOfUse(
                    date = it.string("date").orEmpty(),
                    tokens = it.long("tokens") ?: 0L,
                    generations = it.int("generations") ?: 0,
                )
            },
            // Deliberately not `?: 0`. Hour zero is midnight, and a profile with no
            // activity at all would otherwise claim the user works at midnight.
            peakHour = fields.int("peakHour"),
            longestGenerationMs = fields.long("longestGenerationDurationMs") ?: 0L,
            favouriteModelName = (fields["favoriteModel"] as? JsonObject)?.string("modelName"),
            dailyActivity = (fields["dailyActivity"] as? JsonArray).orEmpty().mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val date = o.string("date") ?: return@mapNotNull null
                DayOfUse(date, o.long("tokens") ?: 0L, o.int("generations") ?: 0)
            },
            mostUsedTools = (fields["mostUsedTools"] as? JsonArray).orEmpty().mapNotNull { row ->
                val o = row as? JsonObject ?: return@mapNotNull null
                val name = o.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ToolUse(name, o.int("count") ?: 0)
            },
        )
    }

    private companion object {
        const val CHANNEL_PROFILE = "settings:get-profile"
        const val CHANNEL_USAGE = "stats:get-usage-profile"
    }
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
