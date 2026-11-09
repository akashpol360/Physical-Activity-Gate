package com.physicalactivitygate

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            webViewClient = WebViewClient()
            settings.javaScriptEnabled = false
            loadDataWithBaseURL(
                "https://physicalactivitygate.local/",
                assets.open("privacy_policy.html").bufferedReader().use { it.readText() },
                "text/html", "UTF-8", null
            )
        }
        setContentView(webView)
    }
}
