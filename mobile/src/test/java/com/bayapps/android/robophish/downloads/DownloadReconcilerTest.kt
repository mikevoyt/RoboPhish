package com.bayapps.android.robophish.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadReconcilerTest {
    private val entry = DownloadEntry(title = "Tweezer", trackId = "12", showId = "1997-12-06", downloadId = 7)
    private fun row(status: Int, reason: Int = 0) = DownloadSnapshot(7, status, reason, 500, 1000, "file:///music/12.mp3")

    @Test fun completionBroadcastIsNotRequiredToDiscoverFinishedDownload() {
        val recovered = DownloadReconciler.reconcile(entry, row(8), true)
        assertEquals(DownloadStatus.COMPLETED, recovered.status)
        assertEquals(100, recovered.progress)
        assertEquals("file:///music/12.mp3", recovered.fileUri)
    }
    @Test fun successfulSystemJobWithMissingOrTruncatedFileIsNotPlayable() {
        val recovered = DownloadReconciler.reconcile(entry, row(8), false)
        assertEquals(DownloadStatus.FAILED, recovered.status)
        assertEquals(DownloadReconciler.MISSING_FILE, recovered.failureReason)
    }
    @Test fun queuedJobRemovedBySystemBecomesRetryable() {
        assertEquals(DownloadStatus.FAILED, DownloadReconciler.reconcile(entry, null, false).status)
    }
    @Test fun completedFileSurvivesSystemHistoryCleanup() {
        val complete = entry.copy(status = DownloadStatus.COMPLETED)
        assertEquals(complete, DownloadReconciler.reconcile(complete, null, true))
    }
    @Test fun missingCompletedFileIsDetectedAfterColdStart() {
        assertEquals(DownloadStatus.FAILED,
            DownloadReconciler.reconcile(entry.copy(status = DownloadStatus.COMPLETED), null, false).status)
    }
    @Test fun missingFileDoesNotCountTowardUsedStorage() {
        val missing = entry.copy(status = DownloadStatus.COMPLETED, bytesDownloaded = 1000)
        assertEquals(0L, DownloadReconciler.reconcile(missing, null, false).bytesDownloaded)
        assertEquals(0L, DownloadReconciler.reconcile(missing, row(8), false).bytesDownloaded)
    }
    @Test fun wifiWaitRetainsReasonAndProgress() {
        val waiting = DownloadReconciler.reconcile(entry, row(4, 3), false)
        assertEquals(DownloadStatus.WAITING, waiting.status)
        assertTrue(waiting.isActive)
        assertEquals(3, waiting.failureReason)
        assertEquals(50, waiting.progress)
    }
    @Test fun transientNetworkFailureStaysActiveRatherThanRequiringManualRetry() {
        val waiting = DownloadReconciler.reconcile(entry, row(4, 1), false)
        assertEquals(DownloadStatus.WAITING, waiting.status)
        assertTrue(waiting.isActive)
    }
    @Test fun terminalFailureRetainsStorageReason() {
        val failed = DownloadReconciler.reconcile(entry, row(16, 1006), false)
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertFalse(failed.isActive)
        assertEquals(1006, failed.failureReason)
    }
    @Test fun unknownContentLengthDoesNotProduceFakePercentage() {
        assertNull(DownloadReconciler.reconcile(entry, row(2).copy(totalBytes = -1), false).progress)
    }
    @Test fun hundredPercentBytesIsNotCompleteUntilSystemConfirmsSuccess() {
        val downloading = DownloadReconciler.reconcile(entry, row(2).copy(bytes = 1000), true)
        assertEquals(DownloadStatus.DOWNLOADING, downloading.status)
        assertEquals(99, downloading.progress)
    }
    @Test fun lostIdCanBeRecoveredFromDestinationMatch() {
        val recovered = DownloadReconciler.reconcile(entry.copy(downloadId = null), row(2), false)
        assertEquals(7L, recovered.downloadId)
    }
    @Test fun missingRowDoesNotEraseFailureReason() {
        val failure = entry.copy(status = DownloadStatus.FAILED, failureReason = 1006)
        assertEquals(failure, DownloadReconciler.reconcile(failure, null, false))
    }
}
