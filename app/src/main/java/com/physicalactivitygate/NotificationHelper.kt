package com.physicalactivitygate

import android.app.*
import android.content.*
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.physicalactivitygate.data.AppDatabase
import com.physicalactivitygate.data.Repository
import com.physicalactivitygate.service.AppAccessAccessibilityService

object NotificationHelper {
    private const val CHANNEL = "access_expiry"
    const val ACTION_FORCE_CHECK = "com.physicalactivitygate.FORCE_CHECK"

    fun scheduleExpiry(context: Context, packageName: String, whenMillis: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // --- Warning notification: 60 seconds before expiry ---
        val warningAt = whenMillis - 60_000L
        if (warningAt > System.currentTimeMillis()) {
            val warningIntent = Intent(context, ExpiryReceiver::class.java)
                .putExtra("package", packageName)
                .putExtra("type", "warning")
                .putExtra("expiresAt", whenMillis)
            val warningPi = PendingIntent.getBroadcast(
                context, packageName.hashCode() + 1, warningIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setAlarmCompat(context, alarm, warningAt, warningPi)
        }

        // --- Expiry: revoke + force-close + "expired" notification ---
        val expiryIntent = Intent(context, ExpiryReceiver::class.java)
            .putExtra("package", packageName)
            .putExtra("type", "expired")
            .putExtra("expiresAt", whenMillis)
        val expiryPi = PendingIntent.getBroadcast(
            context, packageName.hashCode(), expiryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarmCompat(context, alarm, whenMillis, expiryPi)
    }

    fun cancelExpiry(context: Context, packageName: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf(packageName.hashCode() + 1, packageName.hashCode()).forEach { requestCode ->
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, ExpiryReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return@forEach
            alarm.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun setAlarmCompat(context: Context, alarm: AlarmManager, atMillis: Long, pendingIntent: PendingIntent) {
        // Exact alarms can be restricted on Android 12+. Use the exact path when
        // allowed, but never lose the reminder completely if the user/device
        // has not granted exact-alarm access.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            } else {
                alarm.setExact(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent)
        }
    }

    fun notifyWarning(context: Context, packageName: String) {
        notify(context, packageName,
            id = packageName.hashCode() + 1,
            title = "Access about to expire",
            text = "Your access to a protected app expires in 1 minute. Finish up soon.",
            priority = NotificationCompat.PRIORITY_DEFAULT
        )
    }

    fun notifyExpired(context: Context, packageName: String) {
        notify(context, packageName,
            id = packageName.hashCode(),
            title = "Access expired",
            text = "Your access to a protected app has expired. Complete another activity to continue.",
            priority = NotificationCompat.PRIORITY_HIGH
        )
    }

    private fun notify(context: Context, packageName: String, id: Int, title: String, text: String, priority: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Access expiry", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        nm.notify(id, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()
        )
    }

    // Keep old name for any call sites that still use it
    fun notifyExpiry(context: Context, packageName: String) = notifyExpired(context, packageName)
}

class ExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("package").orEmpty()
        val type = intent.getStringExtra("type") ?: "expired"
        val expectedExpiry = intent.getLongExtra("expiresAt", -1L)
        if (packageName.isBlank()) return
        if (expectedExpiry <= 0L) return

        val repo = Repository(AppDatabase.get(context))
        val currentGrant = kotlinx.coroutines.runBlocking { repo.currentAuthorization(packageName) }
        if (currentGrant == null || currentGrant.expiresAt != expectedExpiry) return

        when (type) {
            "warning" -> {
                // Just show the warning notification — do NOT revoke yet, the
                // user still has 60 seconds of legitimate access remaining.
                val notificationsEnabled = kotlinx.coroutines.runBlocking { repo.getSettings().notifications }
                if (notificationsEnabled && System.currentTimeMillis() < expectedExpiry) {
                    NotificationHelper.notifyWarning(context, packageName)
                }
            }
            "expired" -> {
                if (System.currentTimeMillis() < expectedExpiry) return
                kotlinx.coroutines.runBlocking { repo.revoke(packageName) }
                // 2. Honor the app-level notifications setting when an alarm
                //    from an earlier session fires.
                val notificationsEnabled = kotlinx.coroutines.runBlocking { repo.getSettings().notifications }
                if (notificationsEnabled) NotificationHelper.notifyExpired(context, packageName)
                // 3. Force the gate to appear immediately even if the user
                //    is actively inside the protected app (no window-state
                //    event would fire naturally in that case).
                context.sendBroadcast(
                    Intent(NotificationHelper.ACTION_FORCE_CHECK)
                        .setPackage(context.packageName)
                        .putExtra("package", packageName)
                )
            }
        }
    }
}
