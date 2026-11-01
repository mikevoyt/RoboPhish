package com.bayapps.android.robophish.downloads

/** Android DownloadManager constants kept here to make the recovery policy testable on the JVM. */
data class DownloadSnapshot(
    val id: Long, val status: Int, val reason: Int, val bytes: Long, val totalBytes: Long, val uri: String?
)

object DownloadReconciler {
    const val MISSING_FILE = -1
    const val MISSING_JOB = -2

    fun reconcile(entry: DownloadEntry, row: DownloadSnapshot?, validFile: Boolean): DownloadEntry {
        if (row == null) {
            return when {
                entry.status == DownloadStatus.COMPLETED && validFile -> entry
                entry.status == DownloadStatus.COMPLETED -> entry.copy(status = DownloadStatus.FAILED, bytesDownloaded = 0, failureReason = MISSING_FILE)
                entry.isActive -> entry.copy(status = DownloadStatus.FAILED, failureReason = MISSING_JOB)
                else -> entry
            }
        }
        val status = when (row.status) {
            1 -> DownloadStatus.QUEUED
            2 -> DownloadStatus.DOWNLOADING
            4 -> DownloadStatus.WAITING
            8 -> if (validFile) DownloadStatus.COMPLETED else DownloadStatus.FAILED
            else -> DownloadStatus.FAILED
        }
        return entry.copy(downloadId = row.id, status = status,
            bytesDownloaded = if (row.status == 8 && !validFile) 0 else row.bytes.coerceAtLeast(0), totalBytes = row.totalBytes,
            fileUri = row.uri ?: entry.fileUri,
            failureReason = if (row.status == 8 && !validFile) MISSING_FILE else row.reason)
    }
}
