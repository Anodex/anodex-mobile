package dev.anodex.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How the widget's dot reads. */
enum class WidgetConnection { CONNECTED, RECONNECTING, OFFLINE }

/** One recent conversation, as the larger widget lists it. */
@Serializable
data class WidgetRecent(val id: String, val title: String)

/**
 * What the home screen widget shows, kept where the launcher can read it.
 *
 * A widget is drawn by the launcher, often while the app's process is not running, so
 * it cannot ask the app anything. The app writes here as things change — whether the
 * computer is connected, and the few conversations most recently used — and redraws
 * the widget.
 *
 * The recent titles are the one thing this app keeps about your conversations on the
 * phone. Only titles, only a handful, and only so the widget has something to show;
 * the conversations themselves stay on the computer.
 */
object WidgetState {

    private const val PREFS = "anodex.widget"
    private const val KEY_CONNECTION = "connection"
    private const val KEY_RECENTS = "recents"

    /** How many conversations the larger widget has room for. */
    const val RECENT_LIMIT = 3

    private val json = Json { ignoreUnknownKeys = true }

    fun connection(context: Context): WidgetConnection =
        runCatching {
            WidgetConnection.valueOf(prefs(context).getString(KEY_CONNECTION, null).orEmpty())
        }.getOrDefault(WidgetConnection.OFFLINE)

    fun recents(context: Context): List<WidgetRecent> =
        runCatching {
            json.decodeFromString<List<WidgetRecent>>(prefs(context).getString(KEY_RECENTS, null) ?: "[]")
        }.getOrDefault(emptyList())

    /** Record the connection, redrawing the widget only when it actually changed. */
    fun saveConnection(context: Context, connection: WidgetConnection) {
        if (connection(context) == connection) return
        prefs(context).edit().putString(KEY_CONNECTION, connection.name).apply()
        refresh(context)
    }

    /** Record the most recent conversations, redrawing only when the list changed. */
    fun saveRecents(context: Context, recents: List<WidgetRecent>) {
        val trimmed = recents.take(RECENT_LIMIT)
        if (recents(context) == trimmed) return
        prefs(context).edit().putString(KEY_RECENTS, json.encodeToString(trimmed)).apply()
        refresh(context)
    }

    /** Redraw every placed widget from what is stored. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AskWidget::class.java))
        if (ids.isEmpty()) return
        AskWidget.render(context, manager, ids)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
