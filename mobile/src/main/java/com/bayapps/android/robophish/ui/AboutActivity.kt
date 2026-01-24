package com.bayapps.android.robophish.ui

import android.os.Bundle
import android.webkit.WebView
import com.bayapps.android.robophish.BuildConfig
import com.bayapps.android.robophish.R

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_screen)

        val webView = findViewById<WebView>(R.id.about_webview)
        webView.settings.javaScriptEnabled = true
        webView.settings.defaultTextEncodingName = "utf-8"
        val rawHtml = assets.open("about.html").bufferedReader().use { it.readText() }
        val html = rawHtml
            .replace("{{versionName}}", BuildConfig.VERSION_NAME)
            .replace("{{versionCode}}", BuildConfig.VERSION_CODE.toString())
            .replace("{{gitSha}}", BuildConfig.GIT_SHA)
            .replace("{{buildTime}}", BuildConfig.BUILD_TIME_UTC)
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)

        initializeToolbar()
    }
}
