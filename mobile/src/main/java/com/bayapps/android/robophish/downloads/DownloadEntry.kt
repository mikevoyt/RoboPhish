package com.bayapps.android.robophish.downloads

/** Persisted independently of the online catalog, so a cold start needs no network. */
enum class DownloadStatus { QUEUED, DOWNLOADING, WAITING, COMPLETED, FAILED }

data class DownloadEntry(
    val downloadId: Long? = null,
    val trackId: String? = null,
    val showId: String? = null,
    val title: String,
    val artist: String = "Phish",
    val album: String? = null,
    val showDate: String? = null,
    val venue: String? = null,
    val location: String? = null,
    val durationMs: Long? = null,
    val artUrl: String? = null,
    val fileUri: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val addedAt: Long = System.currentTimeMillis(),
    val sourceUrl: String? = null,
    val trackNumber: Int = 0,
    val setName: String? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val failureReason: Int = 0
) {
    val key: String get() = trackId ?: "legacy:$downloadId:$fileUri"
    val showKey: String get() = showId ?: showDate ?: album ?: key
    val isActive: Boolean get() = status == DownloadStatus.QUEUED ||
        status == DownloadStatus.DOWNLOADING || status == DownloadStatus.WAITING
    val progress: Int? get() = when {
        status == DownloadStatus.COMPLETED -> 100
        totalBytes > 0 -> ((bytesDownloaded.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 99)
        else -> null
    }
}

data class DownloadShow(val key: String, val tracks: List<DownloadEntry>) {
    val first: DownloadEntry get() = tracks.first()
    val ready: List<DownloadEntry> get() = tracks.filter { it.status == DownloadStatus.COMPLETED }
    val active: Boolean get() = tracks.any { it.isActive }
    val failed: Boolean get() = tracks.any { it.status == DownloadStatus.FAILED }
    val complete: Boolean get() = tracks.isNotEmpty() && ready.size == tracks.size
    val bytes: Long get() = tracks.sumOf { it.bytesDownloaded.coerceAtLeast(0) }
    val durationMs: Long get() = ready.sumOf { it.durationMs ?: 0 }
    // Track-weighted progress remains meaningful before the server supplies every file size.
    val progress: Int get() = if (tracks.isEmpty()) 0 else tracks.sumOf { it.progress ?: 0 } / tracks.size

    companion object {
        fun group(entries: List<DownloadEntry>): List<DownloadShow> = entries
            .groupBy { it.showKey }
            .map { (key, tracks) -> DownloadShow(key, tracks.sortedWith(
                compareBy<DownloadEntry> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { it.trackId?.toLongOrNull() ?: Long.MAX_VALUE }
                    .thenBy { it.addedAt }
            )) }
            .sortedByDescending { it.tracks.maxOf { track -> track.addedAt } }
    }
}
