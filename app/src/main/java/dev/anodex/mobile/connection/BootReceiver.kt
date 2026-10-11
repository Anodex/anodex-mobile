package dev.anodex.mobile.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.anodex.mobile.pairing.PairedHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts listening for the computer again after the phone restarts or the app is updated.
 *
 * Without it nothing of the app ran until somebody opened it, so every notification sent in the
 * meantime went nowhere — measured on a Galaxy S7: restarted, then a reminder sent to the phone,
 * and the computer reported the phone was not connected. Only when something is paired: a phone
 * that was never paired has nothing to listen to and gets no ongoing notification.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val host = PairedHostStore(appContext).paired.first() ?: return@launch
                ConnectionService.start(appContext, host.identity.displayName, connected = false)
            } finally {
                pending.finish()
            }
        }
    }
}
