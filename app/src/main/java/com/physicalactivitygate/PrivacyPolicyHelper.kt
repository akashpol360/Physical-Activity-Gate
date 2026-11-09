package com.physicalactivitygate

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the bundled privacy policy (assets/privacy_policy.html) through a
 * FileProvider content:// URI so the user's browser/viewer can render it.
 * Falls back to the in-app PrivacyPolicyActivity if no app can handle it.
 */
object PrivacyPolicyHelper {
    private const val ASSET_NAME = "privacy_policy.html"

    fun open(context: Context) {
        val opened = runCatching {
            val dir = File(context.cacheDir, "privacy").apply { mkdirs() }
            val file = File(dir, ASSET_NAME)
            context.assets.open(ASSET_NAME).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            if (e !is ActivityNotFoundException) android.util.Log.w("PrivacyPolicyHelper", "FileProvider open failed", e)
        }.isSuccess

        if (!opened) {
            context.startActivity(
                Intent(context, PrivacyPolicyActivity::class.java).apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
