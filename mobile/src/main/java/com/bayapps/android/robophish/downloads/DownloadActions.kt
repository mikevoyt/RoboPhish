package com.bayapps.android.robophish.downloads

import android.content.Intent
import android.text.format.Formatter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.ui.DownloadsActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DownloadActions {
    fun download(activity: FragmentActivity, items: List<MediaItem>, showId: String? = null,
                 venue: String? = null, date: String? = null, location: String? = null) {
        val playable = items.filter { it.mediaMetadata.isPlayable == true }
        if (playable.isEmpty()) return
        val repository = DownloadRepository.get(activity)
        val enqueue = {
            activity.lifecycleScope.launch {
                val message = try {
                    val result = withContext(Dispatchers.IO) { repository.enqueue(playable, showId, venue, date, location) }
                    when {
                        result.failed > 0 -> activity.getString(R.string.offline_enqueue_failed, result.failed)
                        result.added > 0 -> activity.resources.getQuantityString(R.plurals.offline_queued, result.added, result.added)
                        else -> activity.getString(R.string.offline_already_saved)
                    }
                } catch (_: Exception) { activity.getString(R.string.offline_operation_failed) }
                Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
                    .setAction(R.string.offline_view) { activity.startActivity(Intent(activity, DownloadsActivity::class.java)) }.show()
            }
            Unit
        }
        if (playable.size == 1) { enqueue(); return }
        val estimated = playable.sumOf { (it.mediaMetadata.extras?.getLong(MusicProvider.METADATA_DURATION_MS) ?: 0) * 40 }
        AlertDialog.Builder(activity).setTitle(R.string.download_show)
            .setMessage(activity.getString(R.string.offline_confirm_download, playable.size,
                Formatter.formatShortFileSize(activity, estimated),
                activity.getString(if (repository.wifiOnly) R.string.offline_on_wifi else R.string.offline_on_any_network)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.offline_download) { _, _ -> enqueue() }.show()
    }
}
