package com.timelock.blocker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class LockManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "timelock_lock_manager_pref"
        private const val KEY_LOCK_UNTIL_EPOCH = "lock_until_epoch_ms"
        private const val KEY_LOCK_DURATION_MS = "lock_duration_ms"
        private const val KEY_LOCK_START_EPOCH = "lock_start_epoch_ms"

        const val DURATION_30_MIN = 30 * 60 * 1000L
        const val DURATION_1_HOUR = 60 * 60 * 1000L
        const val DURATION_8_HOURS = 8 * 60 * 60 * 1000L
    }

    fun isLockActive(): Boolean {
        val lockUntil = prefs.getLong(KEY_LOCK_UNTIL_EPOCH, 0L)
        val now = System.currentTimeMillis()
        return lockUntil > now
    }

    fun getRemainingMillis(): Long {
        val lockUntil = prefs.getLong(KEY_LOCK_UNTIL_EPOCH, 0L)
        val now = System.currentTimeMillis()
        val diff = lockUntil - now
        return if (diff > 0) diff else 0L
    }

    fun startLock(durationMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        val targetEpoch = now + durationMillis

        prefs.edit()
            .putLong(KEY_LOCK_UNTIL_EPOCH, targetEpoch)
            .putLong(KEY_LOCK_START_EPOCH, now)
            .putLong(KEY_LOCK_DURATION_MS, durationMillis)
            .commit()

        val serviceIntent = Intent(context, TimeLockVpnService::class.java).apply {
            action = TimeLockVpnService.ACTION_START_LOCK
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        return true
    }

    fun checkAndExpireIfTimePassed(): Boolean {
        val lockUntil = prefs.getLong(KEY_LOCK_UNTIL_EPOCH, 0L)
        val now = System.currentTimeMillis()

        if (lockUntil > 0 && now >= lockUntil) {
            prefs.edit()
                .putLong(KEY_LOCK_UNTIL_EPOCH, 0L)
                .putLong(KEY_LOCK_START_EPOCH, 0L)
                .putLong(KEY_LOCK_DURATION_MS, 0L)
                .commit()

            val stopIntent = Intent(context, TimeLockVpnService::class.java).apply {
                action = TimeLockVpnService.ACTION_STOP_LOCK_EXPIRED
            }
            context.startService(stopIntent)
            return true
        }
        return false
    }
}