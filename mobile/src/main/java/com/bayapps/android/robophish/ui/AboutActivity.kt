package com.bayapps.android.robophish.ui

import android.os.Bundle
import android.webkit.WebView
import com.bayapps.android.robophish.R

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_screen)

        val webView = findViewById<WebView>(R.id.about_webview)
        webView.settings.javaScriptEnabled = true
        webView.loadUrl("file:///android_asset/about.html")

        initializeToolbar()
    }
}
