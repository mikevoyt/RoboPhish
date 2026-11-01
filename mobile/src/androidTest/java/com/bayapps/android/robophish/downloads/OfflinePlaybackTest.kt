package com.bayapps.android.robophish.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ui.DownloadsActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CI disables Wi-Fi and cellular before these tests. No API key or catalog is needed. */
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = DownloadRepository.get(context)
    private val store get() = DownloadStore.get(context)

    @Before fun setup() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse("Offline suite requires disconnected networking", com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        repository.remove(store.getAll())
    }
    @After fun cleanup() {
        if (android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        context.stopService(Intent(context, com.bayapps.android.robophish.MusicService::class.java))
        repository.remove(store.getAll())
    }

    @Test fun offlineCollectionPlaysAndSeeksThroughMediaSessionWithoutCatalog() {
        listOf(fixture("2", 2), fixture("1", 1)).forEach(store::upsert)
        ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
            await {
                var ready = false
                scenario.onActivity { ready = it.mediaBrowser != null && it.findViewById<View>(R.id.download_play) != null }
                ready
            }
            screenshot("offline-ready.png")
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.downloads_list).scrollToPosition(1) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<View>(R.id.download_play).performClick() }
            await {
                var playing = false
                scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true && it.mediaBrowser?.currentMediaItem?.mediaId?.endsWith("|1") == true }
                playing
            }
            scenario.onActivity { activity ->
                val player = requireNotNull(activity.mediaBrowser)
                assertEquals(2, player.mediaItemCount)
                assertTrue(player.currentMediaItem!!.mediaId.endsWith("|1"))
                assertTrue(player.mediaMetadata.extras!!.getBoolean(OfflineMedia.OFFLINE_ONLY))
                assertTrue(activity.supportFragmentManager.findFragmentById(R.id.fragment_playback_controls)!!.isVisible)
                player.seekTo(1500)
            }
            await {
                var sought = false
                scenario.onActivity { sought = (it.mediaBrowser?.currentPosition ?: 0) >= 1500 }
                sought
            }
            scenario.onActivity { it.mediaBrowser!!.seekToNextMediaItem() }
            await {
                var advanced = false
                scenario.onActivity { advanced = it.mediaBrowser?.currentMediaItem?.mediaId?.endsWith("|2") == true && it.mediaBrowser?.isPlaying == true }
                advanced
            }
            scenario.onActivity { it.mediaBrowser!!.pause() }
            scenario.recreate()
            await { var connected = false; scenario.onActivity { connected = it.mediaBrowser != null }; connected }
            screenshot("offline-playing.png")
        }
    }

    @Test fun normalCatalogPlaybackPrefersSavedFileToUnreachableStream() {
        store.upsert(fixture("1", 1))
        ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
            await { var connected = false; scenario.onActivity { connected = it.mediaBrowser != null }; connected }
            scenario.onActivity { activity ->
                val remote = androidx.media3.common.MediaItem.Builder()
                    .setMediaId("__TRACKS_BY_SHOW__/1997-12-06|1")
                    .setUri("https://example.invalid/should-never-be-requested.mp3")
                    .build()
                activity.mediaBrowser!!.setMediaItem(remote)
                activity.mediaBrowser!!.prepare()
                activity.mediaBrowser!!.play()
            }
            await {
                var local = false
                scenario.onActivity { local = it.mediaBrowser?.isPlaying == true &&
                    it.mediaBrowser?.mediaMetadata?.extras?.getBoolean(OfflineMedia.OFFLINE_ONLY) == true }
                local
            }
            scenario.onActivity { it.mediaBrowser!!.pause() }
        }
    }

    @Test fun missingFileBecomesRetryableOnReconciliation() {
        val entry = fixture("1", 1)
        store.upsert(entry)
        assertTrue(OfflineMedia.isPlayable(entry))
        OfflineMedia.file(entry)!!.delete()
        val reconciled = repository.reconcile().single()
        assertEquals(DownloadStatus.FAILED, reconciled.status)
        assertEquals(DownloadReconciler.MISSING_FILE, reconciled.failureReason)
        assertFalse(OfflineMedia.isPlayable(reconciled))
    }

    @Test fun collectionRemainsPlayableAtTwoHundredPercentText() {
        val originalScale = context.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            await { context.resources.configuration.fontScale >= 1.9f }
            store.upsert(fixture("1", 1))
            ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity {
                        it.findViewById<RecyclerView>(R.id.downloads_list).scrollToPosition(1)
                        ready = it.mediaBrowser != null && it.findViewById<View>(R.id.download_play)?.isShown == true
                    }
                    ready
                }
                instrumentation.waitForIdleSync()
                screenshot("offline-large-text.png")
                scenario.onActivity { it.findViewById<View>(R.id.download_play).performClick() }
                await { var playing = false; scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true }; playing }
                scenario.onActivity { it.mediaBrowser!!.pause() }
            }
        } finally {
            shell("settings put system font_scale $originalScale")
            await { kotlin.math.abs(context.resources.configuration.fontScale - originalScale) < 0.01f }
        }
    }

    @Test fun persistenceRoundTripRetainsSetlistAndRetryMetadata() {
        val entry = fixture("1", 1).copy(setName = "Set 2", sourceUrl = "https://example.invalid/audio.mp3")
        store.upsert(entry)
        val json = context.getSharedPreferences("downloads_store", Context.MODE_PRIVATE).getString("entries", null)!!
        val restored = DownloadJson.decode(org.json.JSONArray(json).getJSONObject(0))
        assertEquals(entry, restored)
        assertTrue(OfflineMedia.isPlayable(restored))
    }

    @Test fun interruptedAdoptionRecoversBeforeAndAfterAtomicMove() {
        listOf(false, true).forEachIndexed { index, alreadyMoved ->
            val original = fixture("${index + 1}", index + 1).let { if (index == 0) it.copy(trackId = null) else it }
            val source = OfflineMedia.file(original)!!
            val destination = File(File(source.parentFile, "saved").apply { mkdirs() }, source.name)
            val journal = original.copy(status = DownloadStatus.DOWNLOADING,
                transferUri = original.fileUri, fileUri = Uri.fromFile(destination).toString())
            // Simulate a process interruption on either side of the rename.
            if (alreadyMoved) assertTrue(source.renameTo(destination))
            store.upsert(journal)
            repository.reconcile()
            val recovered = store.find(original.trackId ?: original.key)!!
            assertEquals(1, store.getAll().size)
            assertEquals(original.key, recovered.key)
            assertEquals(DownloadStatus.COMPLETED, recovered.status)
            assertNull(recovered.transferUri)
            assertNull(recovered.downloadId)
            assertFalse(source.exists())
            assertTrue(OfflineMedia.isPlayable(recovered))
            repository.remove(listOf(recovered))
            assertFalse(destination.exists())
        }
    }

    @Test fun lateLegacyMetadataCannotResurrectRemovedDownload() {
        val legacy = fixture("1", 1).copy(status = DownloadStatus.FAILED, sourceUrl = null)
        store.upsert(legacy)
        repository.remove(listOf(legacy))
        val recoveredItem = androidx.media3.common.MediaItem.Builder()
            .setMediaId("__TRACKS_BY_SHOW__/1997-12-06|1")
            .setUri("https://example.invalid/late-metadata.mp3").build()
        assertEquals(0, repository.retry(listOf(legacy), listOf(recoveredItem)).added)
        assertTrue(store.getAll().isEmpty())
    }

    @Test fun legacyNullAndBadShowIdMigrateSafely() {
        val json = org.json.JSONObject("""{"title":"Tweezer","trackId":"1","showId":"123|1","status":"COMPLETED","fileUri":null,"downloadId":null,"durationMs":null}""")
        val decoded = DownloadJson.decode(json)
        assertEquals("123", decoded.showId)
        assertNull(decoded.fileUri)
        assertNull(decoded.downloadId)
        assertNull(decoded.durationMs)
        assertFalse(OfflineMedia.isPlayable(decoded))
    }

    @Test fun emptyAndExpandedCollectionRender() {
        ActivityScenario.launch(DownloadsActivity::class.java).use {
            instrumentation.waitForIdleSync()
            screenshot("offline-empty.png")
        }
        listOf(fixture("1", 1), fixture("2", 2).copy(status = DownloadStatus.FAILED, failureReason = 1006)).forEach(store::upsert)
        ActivityScenario.launch(DownloadsActivity::class.java).use { scenario ->
            await { var found = false; scenario.onActivity { found = it.findViewById<View>(R.id.download_expand) != null }; found }
            scenario.onActivity { it.findViewById<View>(R.id.download_expand).performClick() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.findViewById<RecyclerView>(R.id.downloads_list).scrollToPosition(1) }
            instrumentation.waitForIdleSync()
            screenshot("offline-partial-setlist.png")
        }
    }

    private fun fixture(id: String, order: Int): DownloadEntry {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "test_offline_$id.mp3")
        instrumentation.context.assets.open("offline-tone.mp3").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return DownloadEntry(trackId = id, showId = "1997-12-06", title = if (order == 1) "Tweezer" else "Isabella",
            album = "The Palace of Auburn Hills", venue = "The Palace of Auburn Hills", showDate = "1997-12-06",
            durationMs = 12_000, setName = "Set 2", trackNumber = order,
            fileUri = Uri.fromFile(file).toString(), status = DownloadStatus.COMPLETED,
            bytesDownloaded = file.length(), totalBytes = file.length())
    }

    private fun await(timeout: Long = 10_000, condition: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        while (!condition()) {
            assertTrue("Timed out waiting for offline playback/UI", SystemClock.elapsedRealtime() - start < timeout)
            SystemClock.sleep(100)
        }
    }

    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        // The test runner uninstalls the app afterward; keep evidence in the shell-owned
        // Downloads directory prepared by CI rather than in app-specific storage.
        shell("screencap -p /sdcard/Download/robophish-offline/$name")
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes() // Wait for the command to finish before changing the UI.
        }
    }
}
