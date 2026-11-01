package com.bayapps.android.robophish.downloads

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.MediaIDHelper
import java.io.File
import java.util.UUID

/** Android owns the transfers: they survive activity/service death and network changes. */
class DownloadRepository private constructor(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("offline_settings", Context.MODE_PRIVATE)
    val store = DownloadStore.get(context)
    val wifiOnly: Boolean get() = prefs.getBoolean("wifi_only", true)

    data class EnqueueResult(val added: Int, val skipped: Int, val failed: Int)

    @Synchronized fun enqueue(
        items: List<MediaItem>, showId: String?, venue: String?, date: String?, location: String?
    ): EnqueueResult {
        reconcile()
        var added = 0; var skipped = 0; var failed = 0
        items.filter { it.mediaMetadata.isPlayable == true }.distinctBy { it.mediaId }.forEachIndexed { index, item ->
            val id = MediaIDHelper.extractMusicIDFromMediaID(item.mediaId)
            if (id.isNullOrBlank()) { failed++; return@forEachIndexed }
            val existing = store.find(id)
            if (existing != null && (existing.isActive || OfflineMedia.isPlayable(existing))) {
                skipped++; return@forEachIndexed
            }
            val metadata = item.mediaMetadata
            val source = metadata.extras?.getString(MusicProvider.METADATA_STREAM_URL)
                ?: item.requestMetadata.mediaUri?.toString() ?: item.localConfiguration?.uri?.toString()
            val entry = DownloadEntry(
                trackId = id, showId = MediaIDHelper.getHierarchy(item.mediaId).getOrNull(1)
                    ?: showId?.substringBefore('|'),
                title = metadata.title?.toString() ?: "Phish", artist = metadata.artist?.toString() ?: "Phish",
                album = metadata.albumTitle?.toString(), venue = metadata.albumTitle?.toString() ?: venue,
                showDate = metadata.description?.toString() ?: date, location = location,
                durationMs = metadata.extras?.getLong(MusicProvider.METADATA_DURATION_MS),
                artUrl = metadata.artworkUri?.toString(), sourceUrl = source,
                trackNumber = metadata.trackNumber ?: (index + 1),
                setName = metadata.extras?.getString(MusicProvider.METADATA_SET_NAME)
                    ?: metadata.extras?.getString(MusicProvider.METADATA_SET)?.let { setLabel(it) },
                addedAt = existing?.addedAt ?: System.currentTimeMillis()
            )
            if (start(entry, existing)) added++ else failed++
        }
        return EnqueueResult(added, skipped, failed)
    }

    @Synchronized fun retry(entries: List<DownloadEntry>): EnqueueResult {
        reconcile()
        var added = 0; var skipped = 0; var failed = 0
        entries.forEach { stale ->
            val current = store.getAll().firstOrNull { it.key == stale.key } ?: return@forEach
            if (current.isActive || OfflineMedia.isPlayable(current)) skipped++
            else if (start(current, current)) added++ else failed++
        }
        return EnqueueResult(added, skipped, failed)
    }

    private fun start(entry: DownloadEntry, previous: DownloadEntry?): Boolean {
        var pending = entry.copy(downloadId = null, fileUri = null, status = DownloadStatus.QUEUED,
            bytesDownloaded = 0, totalBytes = -1, failureReason = 0)
        try {
            val uri = Uri.parse(entry.sourceUrl ?: throw IllegalArgumentException("Missing source"))
            require(uri.scheme == "https" || uri.scheme == "http")
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: throw IllegalStateException("Storage unavailable")
            check(directory.exists() || directory.mkdirs())
            // Reserve room for the queued tracks as well as the next file (320 kbps estimate).
            val reserved = store.getAll().filter { it.isActive && it.key != entry.key }.sumOf {
                ((if (it.totalBytes > 0) it.totalBytes else (it.durationMs ?: 300_000) * 40) - it.bytesDownloaded).coerceAtLeast(0)
            }
            val estimated = (entry.durationMs ?: 300_000) * 40
            if (directory.usableSpace < reserved + estimated + 32L * 1024 * 1024) {
                previous?.let { removeTransfer(it) }
                store.upsert(pending.copy(status = DownloadStatus.FAILED, failureReason = DownloadManager.ERROR_INSUFFICIENT_SPACE))
                return false
            }
            previous?.let { removeTransfer(it) }
            val name = "offline_${UUID.randomUUID()}.mp3"
            val destination = File(directory, name)
            pending = pending.copy(fileUri = Uri.fromFile(destination).toString())
            // Write the destination before enqueue, so reconciliation can recover a lost job ID.
            store.upsert(pending)
            val request = DownloadManager.Request(uri)
                .setTitle(entry.title)
                .setDescription(listOfNotNull(entry.showDate, entry.venue).joinToString(" · "))
                .setMimeType("audio/mpeg")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, name)
            val id = manager.enqueue(request)
            store.upsert(pending.copy(downloadId = id))
            return true
        } catch (_: Exception) {
            store.upsert(pending.copy(status = DownloadStatus.FAILED, failureReason = DownloadManager.ERROR_UNKNOWN))
            return false
        }
    }

    @Synchronized fun reconcile(): List<DownloadEntry> {
        val entries = store.getAll()
        if (entries.isEmpty()) return entries
        val rows = mutableMapOf<Long, DownloadSnapshot>()
        // A single binder query, not one query per song. Only this app's jobs are returned.
        manager.query(DownloadManager.Query())?.use { cursor ->
            while (cursor.moveToNext()) {
                fun long(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val id = long(DownloadManager.COLUMN_ID)
                rows[id] = DownloadSnapshot(id, long(DownloadManager.COLUMN_STATUS).toInt(),
                    long(DownloadManager.COLUMN_REASON).toInt(),
                    long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                    cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
            }
        }
        val updated = entries.map { entry ->
            val row = entry.downloadId?.let { rows[it] } ?: rows.values.firstOrNull {
                entry.downloadId == null && entry.fileUri != null && it.uri == entry.fileUri
            }
            val candidate = if (row?.uri != null) entry.copy(fileUri = row.uri) else entry
            val completeCandidate = candidate.copy(status = DownloadStatus.COMPLETED,
                totalBytes = row?.totalBytes?.takeIf { it > 0 } ?: entry.totalBytes)
            DownloadReconciler.reconcile(candidate, row, OfflineMedia.isPlayable(completeCandidate))
        }
        store.replaceAll(updated)
        return updated
    }

    @Synchronized fun remove(entries: List<DownloadEntry>) {
        entries.forEach { stale ->
            store.getAll().firstOrNull { it.key == stale.key }?.let { entry ->
                removeTransfer(entry)
                store.delete(entry)
            }
        }
    }

    private fun removeTransfer(entry: DownloadEntry) {
        entry.downloadId?.let { manager.remove(it) }
        OfflineMedia.file(entry)?.let { file ->
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.canonicalFile
            if (directory != null && file.canonicalFile.parentFile == directory && file.exists()) {
                check(file.delete()) { "Unable to remove downloaded file" }
            }
        }
    }

    @Synchronized fun setWifiOnly(value: Boolean) {
        check(prefs.edit().putBoolean("wifi_only", value).commit())
        // Android does not expose editing a Request. Requeue active files; keep completed ones.
        reconcile().filter { it.isActive }.forEach { start(it, it) }
    }

    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context).also { instance = it }
        }
        private fun setLabel(set: String): String = when (set.uppercase()) {
            "E" -> "Encore"; "S" -> "Soundcheck"; else -> "Set $set"
        }
    }
}
