package dev.anodex.mobile.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.anodex.mobile.MainActivity
import dev.anodex.mobile.widget.AskWidget

/**
 * "Ask Anodex" in the Quick Settings shade.
 *
 * Pull down, tap, and a new chat is open with the keyboard up — from any app,
 * without going home first. The same quick action the widget's "Ask Anodex…" sends.
 */
class AskTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AskWidget.EXTRA_QUICK_ACTION, AskWidget.ACTION_NEW_CHAT)

        // Android 14 refuses a bare intent here and wants a PendingIntent; older
        // versions have only the intent form.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_ASK,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_ASK = 0x7153
    }
}
