package dev.anodex.mobile.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.anodex.mobile.MainActivity
import dev.anodex.mobile.R

/**
 * "Ask Anodex…" on the home screen, with photos and a camera beside it.
 *
 * One tap to a new chat, or to a photo to ask about — taken or chosen — without
 * finding the app first.
 * Both open the app the way a notification does — onto the one existing copy — and
 * say what was asked for; the app does the rest once it is in front.
 */
class AskWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_ask).apply {
            setOnClickPendingIntent(R.id.widget_ask, launch(context, ACTION_NEW_CHAT))
            setOnClickPendingIntent(R.id.widget_photos, launch(context, ACTION_PHOTOS))
            setOnClickPendingIntent(R.id.widget_camera, launch(context, ACTION_CAMERA))
        }
        manager.updateAppWidget(widgetIds, views)
    }

    private fun launch(context: Context, action: String): PendingIntent {
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

    companion object {
        const val EXTRA_QUICK_ACTION = "dev.anodex.mobile.quickAction"
        const val ACTION_NEW_CHAT = "new-chat"
        const val ACTION_CAMERA = "camera"
        const val ACTION_PHOTOS = "photos"
    }
}
