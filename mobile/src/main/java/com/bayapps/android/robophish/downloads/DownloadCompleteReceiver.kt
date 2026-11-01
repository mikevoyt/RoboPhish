package com.bayapps.android.robophish.downloads

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DownloadRepository.get(context).reconcile()
            } catch (e: Exception) {
                Timber.w(e, "Could not reconcile downloads; will retry when collection opens")
            } finally {
                pending.finish()
            }
        }
    }
}
