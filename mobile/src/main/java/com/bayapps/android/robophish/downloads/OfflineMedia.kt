package com.bayapps.android.robophish.downloads

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.MediaIDHelper
import java.io.File

object OfflineMedia {
    const val OFFLINE_ONLY = "robophish.OFFLINE_ONLY"

    fun file(entry: DownloadEntry): File? = runCatching {
        val uri = Uri.parse(entry.fileUri ?: return null)
        if (uri.scheme != "file") return null
        File(uri.path ?: return null)
    }.getOrNull()

    fun isPlayable(entry: DownloadEntry): Boolean {
        if (entry.status != DownloadStatus.COMPLETED) return false
        val file = file(entry) ?: return false
        return file.isFile && file.canRead() && file.length() > 0 &&
            (entry.totalBytes <= 0 || file.length() == entry.totalBytes)
    }

    fun toMediaItem(entry: DownloadEntry): MediaItem {
        val uri = Uri.parse(requireNotNull(entry.fileUri))
        return MediaItem.Builder()
            .setMediaId(MediaIDHelper.createMediaID(entry.trackId ?: entry.key,
                MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW, entry.showId ?: "offline"))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(entry.title).setArtist(entry.artist).setAlbumTitle(entry.venue ?: entry.album)
                .setDescription(entry.showDate).setSubtitle(entry.showDate)
                .setTrackNumber(entry.trackNumber).setIsPlayable(true).setIsBrowsable(false)
                // Built-in artwork is always available; offline playback must never fetch art.
                .setExtras(Bundle().apply {
                    putBoolean(OFFLINE_ONLY, true)
                    putLong(MusicProvider.METADATA_DURATION_MS, entry.durationMs ?: 0)
                    putString(MusicProvider.METADATA_SET_NAME, entry.setName)
                }).build()).build()
    }
}
