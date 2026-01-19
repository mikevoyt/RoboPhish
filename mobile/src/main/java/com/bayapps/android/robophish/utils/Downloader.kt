package com.bayapps.android.robophish.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import com.bayapps.android.robophish.model.ParseUtils
import org.json.JSONObject
import timber.log.Timber

class Downloader(
    private val context: Context,
    showData: JSONObject
) {
    private val downloadCompleteIntentName = DownloadManager.ACTION_DOWNLOAD_COMPLETE
    private val downloadCompleteIntentFilter = IntentFilter(downloadCompleteIntentName)

    constructor(context: Context, showId: String, showData: JSONObject) : this(context, showData)

    private val downloadCompleteReceiver: BroadcastReceiver by lazy {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
                if (!downloadIds.contains(id)) {
                    Timber.v("Ingnoring unrelated download %s", id)
                    return
                }

                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor: Cursor = downloadManager.query(query)
                cursor.use {
                    if (!it.moveToFirst()) {
                        Timber.e("Empty row")
                        return
                    }

                    val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (DownloadManager.STATUS_SUCCESSFUL != it.getInt(statusIndex)) {
                        Timber.w("Download Failed")
                        return
                    }

                    val uriIndex = it.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val downloadedPackageUriString = it.getString(uriIndex)
                    Timber.d("downloaded %s", downloadedPackageUriString)
                }
            }
        }
    }

    init {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                downloadCompleteReceiver,
                downloadCompleteIntentFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(downloadCompleteReceiver, downloadCompleteIntentFilter)
        }

        val show = ParseUtils.parseShow(showData)
        if (show != null) {
            val title = "${show.dateSimple()}: ${show.venueName}, ${show.location}"
            val firstTrack = show.tracks.firstOrNull()
            if (firstTrack != null) {
                val url = firstTrack.url

                val request = DownloadManager.Request(Uri.parse(url))
                request.setTitle(title)
                request.setDescription("Downloading $title")
                Timber.d("downloading %s", url)

                request.setVisibleInDownloadsUi(true)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalFilesDir(context, null, url)

                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val id = downloadManager.enqueue(request)
                downloadIds.add(id)
            }
        }
    }

    companion object {
        private val downloadIds = mutableListOf<Long>()
    }
}
