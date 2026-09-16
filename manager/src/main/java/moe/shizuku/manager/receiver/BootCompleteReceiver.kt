package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.WatchdogService

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Root start now runs on a background thread; hold goAsync() until it
        // actually finishes so the system doesn't demote/kill the process
        // while root grant + the starter command are still in flight.
        val pendingResult = goAsync()
        ShizukuReceiverStarter.start(context, onRootStartFinished = { pendingResult.finish() })
        if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)
    }
}