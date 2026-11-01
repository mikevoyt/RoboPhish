package com.bayapps.android.robophish.downloads

import org.junit.Assert.*
import org.junit.Test

class DownloadShowTest {
    private fun track(id: String, order: Int, show: String = "1", status: DownloadStatus = DownloadStatus.COMPLETED) =
        DownloadEntry(title = id, trackId = id, showId = show, trackNumber = order, status = status, bytesDownloaded = 100, durationMs = 60_000)

    @Test fun completionOrderNeverChangesSetlistOrder() {
        val show = DownloadShow.group(listOf(track("103", 3), track("101", 1), track("102", 2))).single()
        assertEquals(listOf("101", "102", "103"), show.ready.map { it.trackId })
    }
    @Test fun sameVenueOnDifferentNightsStaysSeparate() {
        assertEquals(2, DownloadShow.group(listOf(track("1", 1, "a").copy(album = "MSG"), track("2", 1, "b").copy(album = "MSG"))).size)
    }
    @Test fun partialShowOnlyQueuesCompletedSongsInOriginalOrder() {
        val show = DownloadShow.group(listOf(track("1", 1), track("2", 2, status = DownloadStatus.FAILED), track("3", 3))).single()
        assertEquals(listOf("1", "3"), show.ready.map { it.trackId })
        assertFalse(show.complete)
        assertTrue(show.failed)
        assertEquals(120_000L, show.durationMs)
    }
    @Test fun legacyTracksUseNumericIdOrderRatherThanDownloadCompletionTime() {
        val show = DownloadShow.group(listOf(track("100", 0), track("99", 0))).single()
        assertEquals(listOf("99", "100"), show.tracks.map { it.trackId })
    }
    @Test fun progressIncludesCompletedTracksAndDoesNotRequireKnownFileSizes() {
        val show = DownloadShow.group(listOf(track("1", 1), track("2", 2, status = DownloadStatus.QUEUED))).single()
        assertEquals(50, show.progress)
        assertTrue(show.active)
        assertFalse(show.complete)
    }
    @Test fun retriesDoNotReorderCollection() {
        val oldShow = track("1", 1, "a").copy(addedAt = 100)
        val newShow = track("2", 1, "b").copy(addedAt = 200)
        assertEquals(listOf("b", "a"), DownloadShow.group(listOf(oldShow, newShow)).map { it.key })
    }
}
