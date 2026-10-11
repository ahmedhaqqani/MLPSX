package com.goldorb.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goldorb.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Restarts background monitoring after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (context.container.settings.current().monitoring) MonitorService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
