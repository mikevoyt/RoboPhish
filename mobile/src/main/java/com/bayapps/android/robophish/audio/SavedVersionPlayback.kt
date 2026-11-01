package com.bayapps.android.robophish.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.bayapps.android.robophish.audio.offline.EnhancementRepository
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.OfflineMedia

/** Resolves a ready, explicitly selected copy. Never schedules or waits for processing. */
object SavedVersionPlayback {
    const val ENHANCED = "com.bayapps.android.robophish.audio.offline.SAVED_ENHANCED"
    const val ORIGINAL_URI = "com.bayapps.android.robophish.audio.offline.ORIGINAL_URI"

    fun resolve(context: Context, original: DownloadEntry): MediaItem {
        val item = OfflineMedia.toMediaItem(original)
        val enhanced = EnhancementRepository.get(context).resolve(original) ?: return item
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putBoolean(ENHANCED, true)
            putString(ORIGINAL_URI, original.fileUri)
        }
        return item.buildUpon()
            .setUri(enhanced).setMimeType(MimeTypes.AUDIO_WAV)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(enhanced).build())
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun isEnhanced(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.getBoolean(ENHANCED) == true

    fun originalUri(item: MediaItem): Uri? = item.mediaMetadata.extras?.getString(ORIGINAL_URI)?.let(Uri::parse)

    /** A container change must reset the extractor; WAV byte offsets cannot be used for an MP3. */
    fun fallback(item: MediaItem): MediaItem? {
        if (!isEnhanced(item)) return null
        val uri = originalUri(item) ?: return null
        if (uri.scheme != "file") return null
        val source = uri.path?.let { java.io.File(it) } ?: return null
        if (!source.isFile || !source.canRead() || source.length() <= 0) return null
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            remove(ENHANCED)
            remove(ORIGINAL_URI)
        }
        return item.buildUpon().setUri(uri).setMimeType(null)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }
}
