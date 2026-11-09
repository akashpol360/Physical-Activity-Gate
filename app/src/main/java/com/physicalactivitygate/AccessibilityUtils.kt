package com.physicalactivitygate

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.physicalactivitygate.service.AppAccessAccessibilityService

/**
 * Android only lets the user enable an Accessibility Service manually in
 * system Settings — an app can never flip it on for itself. This checks
 * whether that manual step has actually been done, so the UI can tell the
 * user plainly instead of silently failing to block anything.
 */
object AccessibilityUtils {
    fun isServiceEnabled(context: Context): Boolean {
        val expected = "${context.packageName}/${AppAccessAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
