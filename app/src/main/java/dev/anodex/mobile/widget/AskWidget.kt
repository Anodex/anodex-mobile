package dev.anodex.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import dev.anodex.mobile.MainActivity
import dev.anodex.mobile.R
import dev.anodex.mobile.notify.Notifications

/**
 * "Ask Anodex…" on the home screen, with photos and a camera beside it.
 *
 * One tap to a new chat, or to a photo to ask about — taken or chosen — without
 * finding the app first. Both open the app the way a notification does — onto the
 * one existing copy — and say what was asked for; the app does the rest once it is
 * in front.
 *
 * A dot on the mark says whether the computer is connected. Stretched taller, the
 * widget also lists the conversations used most recently. Both come from
 * [WidgetState], which the app keeps up to date.
 */
class AskWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        render(context, manager, widgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle,
    ) {
        render(context, manager, intArrayOf(widgetId))
    }

    companion object {
        const val EXTRA_QUICK_ACTION = "dev.anodex.mobile.quickAction"
        const val ACTION_NEW_CHAT = "new-chat"
        const val ACTION_CAMERA = "camera"
        const val ACTION_PHOTOS = "photos"

        /** From the app icon's long-press shortcuts. */
        const val ACTION_TEMPORARY = "temporary"

        /** Tall enough, in dp, for the list of recent conversations under the bar. */
        private const val LARGE_MIN_HEIGHT_DP = 150

        private val RECENT_ROWS = intArrayOf(R.id.widget_recent_1, R.id.widget_recent_2, R.id.widget_recent_3)

        fun render(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
            val connection = WidgetState.connection(context)
            val recents = WidgetState.recents(context)

            for (id in widgetIds) {
                val small = views(context, R.layout.widget_ask, connection, recents)
                val large = views(context, R.layout.widget_ask_large, connection, recents)

                val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The launcher picks between them as the widget is resized, with
                    // no round trip through the app.
                    RemoteViews(
                        mapOf(
                            SizeF(180f, 40f) to small,
                            SizeF(180f, LARGE_MIN_HEIGHT_DP.toFloat()) to large,
                        ),
                    )
                } else {
                    val height = manager.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                    if (height >= LARGE_MIN_HEIGHT_DP) large else small
                }
                manager.updateAppWidget(id, chosen)
            }
        }

        private fun views(
            context: Context,
            layout: Int,
            connection: WidgetConnection,
            recents: List<WidgetRecent>,
        ) = RemoteViews(context.packageName, layout).apply {
            setOnClickPendingIntent(R.id.widget_ask, quickAction(context, ACTION_NEW_CHAT))
            setOnClickPendingIntent(R.id.widget_photos, quickAction(context, ACTION_PHOTOS))
            setOnClickPendingIntent(R.id.widget_camera, quickAction(context, ACTION_CAMERA))

            val (dot, description) = when (connection) {
                WidgetConnection.CONNECTED -> R.drawable.widget_dot_connected to R.string.widget_status_connected
                WidgetConnection.RECONNECTING -> R.drawable.widget_dot_reconnecting to R.string.widget_status_reconnecting
                WidgetConnection.OFFLINE -> R.drawable.widget_dot_offline to R.string.widget_status_offline
            }
            setImageViewResource(R.id.widget_status, dot)
            setContentDescription(R.id.widget_status, context.getString(description))

            if (layout == R.layout.widget_ask_large) {
                RECENT_ROWS.forEachIndexed { index, row ->
                    val recent = recents.getOrNull(index)
                    if (recent == null) {
                        setViewVisibility(row, View.GONE)
                    } else {
                        setViewVisibility(row, View.VISIBLE)
                        setTextViewText(row, recent.title)
                        setOnClickPendingIntent(row, openConversation(context, recent.id, index))
                    }
                }
                setViewVisibility(R.id.widget_recent_empty, if (recents.isEmpty()) View.VISIBLE else View.GONE)
            }
        }

        private fun quickAction(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_QUICK_ACTION, action)
            return PendingIntent.getActivity(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** Opens a conversation the way tapping its notification does. */
        private fun openConversation(context: Context, conversationId: String, row: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Notifications.EXTRA_CONVERSATION_ID, conversationId)
            // One request code per row, or every row would open the same chat.
            return PendingIntent.getActivity(
                context,
                REQUEST_RECENT + row,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val REQUEST_RECENT = 0x5E0
    }
}
