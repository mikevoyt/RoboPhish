package com.bayapps.android.robophish.ui

import android.os.Bundle
import com.bayapps.android.robophish.R

class DownloadsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.downloads_placeholder)
        initializeToolbar()
    }
}
