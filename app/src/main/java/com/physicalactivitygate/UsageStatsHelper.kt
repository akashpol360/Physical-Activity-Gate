package com.physicalactivitygate

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

object UsageStatsHelper {
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun todayMinutes(context: Context, packageName: String): Int {
        if (!hasUsageAccess(context)) return 0
        val now = System.currentTimeMillis(); val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        return ((stats.firstOrNull { it.packageName == packageName }?.totalTimeInForeground ?: 0L) / 60_000L).toInt()
    }
}
