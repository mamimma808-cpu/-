package com.timelock.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d("BootReceiver", "Device boot detected. Checking TimeLock status...")
            val lockManager = LockManager(context)

            if (lockManager.isLockActive()) {
                Log.i("BootReceiver", "Lock is still active! Reviving TimeLockVpnService...")
                val serviceIntent = Intent(context, TimeLockVpnService::class.java).apply {
                    this.action = TimeLockVpnService.ACTION_START_LOCK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                lockManager.checkAndExpireIfTimePassed()
            }
        }
    }
}