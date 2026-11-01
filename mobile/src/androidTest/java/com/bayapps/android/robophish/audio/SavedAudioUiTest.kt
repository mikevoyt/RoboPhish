package com.bayapps.android.robophish.audio

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.audio.offline.EnhancementEntry
import com.bayapps.android.robophish.audio.offline.EnhancementRepository
import com.bayapps.android.robophish.audio.offline.EnhancementStatus
import com.bayapps.android.robophish.audio.offline.PcmWavWriter
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.ui.SavedAudioActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/** Real controls and MediaSession; synthetic files are allowed only on disconnected emulators. */
@RunWith(AndroidJUnit4::class)
class SavedAudioUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun versionChoicePersistsWithoutInterruptingCurrentPlayback() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse("Offline suite requires disconnected networking",
            com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        val repository = EnhancementRepository.get(context)
        val downloads = DownloadStore.get(context)
        val source = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "saved_audio_ui_original.mp3")
        instrumentation.context.assets.open("offline-tone.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        val sourceHash = sha256(source)
        val original = DownloadEntry(trackId = "saved-audio-ui", showId = "saved-audio-ui-show",
            title = "Saved version UI test", showDate = "1997-12-06", venue = "Offline test venue",
            durationMs = 12_000, fileUri = Uri.fromFile(source).toString(), status = DownloadStatus.COMPLETED,
            bytesDownloaded = source.length(), totalBytes = source.length(), addedAt = Long.MAX_VALUE)
        val entry = EnhancementEntry(original.key, original.title, UUID.randomUUID().toString(),
            source.canonicalFile.toURI().toString(), source.length(), source.lastModified(), EnhancementRepository.ALGORITHM_VERSION)
        val output = repository.output(entry)
        val pcm = ShortArray(44_100 * 2) { sample -> (sin(2 * PI * 440 * (sample / 2) / 44_100) * 4_000).toInt().toShort() }
        PcmWavWriter(output, 44_100, 2, 44_100L * 12).use { writer ->
            repeat(12) { writer.write(pcm, pcm.size) }
            writer.finish(44_100L * 12)
        }
        try {
            downloads.upsert(original)
            repository.store.put(entry.copy(status = EnhancementStatus.READY, progress = 100,
                fileUri = Uri.fromFile(output).toString(), sourceSha256 = sourceHash, outputBytes = output.length()))
            ActivityScenario.launch(SavedAudioActivity::class.java).use { scenario ->
                awaitRow(scenario)
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply { stop(); clearMediaItems() }
                    assertEquals(original.title, row(activity).findViewById<TextView>(R.id.saved_audio_track_title).text.toString())
                    assertTrue(row(activity).findViewById<RadioButton>(R.id.saved_audio_choose_original).isChecked)
                }
                screenshot("saved-audio-ready.png")
                scenario.onActivity { row(it).findViewById<View>(R.id.saved_audio_choose_enhanced).performClick() }
                await { repository.store.find(original.key)?.preferEnhanced == true }
                scenario.onActivity { activity ->
                    assertNull("Choosing a future version must not start playback", activity.mediaBrowser!!.currentMediaItem)
                }
                await {
                    var ready = false
                    scenario.onActivity { activity -> ready = row(activity).findViewById<TextView>(R.id.saved_audio_play).text.toString() ==
                        activity.getString(R.string.saved_audio_play_enhanced) && row(activity).findViewById<View>(R.id.saved_audio_play).isEnabled }
                    ready
                }
                scenario.onActivity { row(it).findViewById<View>(R.id.saved_audio_play).performClick() }
                await {
                    var playing = false
                    scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true && SavedVersionPlayback.isEnhanced(it.mediaBrowser?.currentMediaItem) }
                    playing
                }
                scenario.onActivity { row(it).findViewById<View>(R.id.saved_audio_choose_original).performClick() }
                await { repository.store.find(original.key)?.preferEnhanced == false }
                scenario.onActivity {
                    assertTrue("Preference changes must leave current playback alone", it.mediaBrowser!!.isPlaying)
                    assertTrue(SavedVersionPlayback.isEnhanced(it.mediaBrowser?.currentMediaItem))
                    it.mediaBrowser!!.pause()
                }
                scenario.recreate()
                awaitRow(scenario)
                scenario.onActivity { activity ->
                    assertTrue("Version preference survives recreation", row(activity).findViewById<RadioButton>(R.id.saved_audio_choose_original).isChecked)
                    assertEquals(activity.getString(R.string.saved_audio_play_original),
                        row(activity).findViewById<TextView>(R.id.saved_audio_play).text.toString())
                    row(activity).findViewById<View>(R.id.saved_audio_play).performClick()
                }
                await {
                    var playing = false
                    scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true && !SavedVersionPlayback.isEnhanced(it.mediaBrowser?.currentMediaItem) }
                    playing
                }
                scenario.onActivity { it.mediaBrowser!!.pause() }
            }
            assertEquals("Version choices never rewrite the source", sourceHash, sha256(source))
            assertTrue(output.isFile)
        } finally {
            context.stopService(Intent(context, MusicService::class.java))
            repository.remove(original.key)
            downloads.delete(original)
            source.delete()
        }
    }

    @Test fun unchangedResultOffersOnlyOriginalAndClearingKeepsTheDownload() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse("Offline suite requires disconnected networking",
            com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        val repository = EnhancementRepository.get(context)
        val downloads = DownloadStore.get(context)
        val source = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "saved_audio_ui_unchanged.mp3")
        instrumentation.context.assets.open("offline-tone.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        val sourceHash = sha256(source)
        val original = DownloadEntry(trackId = "saved-audio-ui-unchanged", showId = "saved-audio-ui-unchanged-show",
            title = "Original retained UI test", durationMs = 12_000,
            fileUri = Uri.fromFile(source).toString(), status = DownloadStatus.COMPLETED,
            bytesDownloaded = source.length(), totalBytes = source.length(), addedAt = Long.MAX_VALUE)
        val summary = "No clear correction found. The original audio is unchanged."
        val entry = EnhancementEntry(original.key, original.title, UUID.randomUUID().toString(),
            source.canonicalFile.toURI().toString(), source.length(), source.lastModified(), EnhancementRepository.ALGORITHM_VERSION,
            sourceSha256 = sourceHash, status = EnhancementStatus.UNCHANGED, progress = 100, profileSummary = summary)
        try {
            downloads.upsert(original)
            repository.store.put(entry)
            ActivityScenario.launch(SavedAudioActivity::class.java).use { scenario ->
                awaitRow(scenario)
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply { stop(); clearMediaItems() }
                    val track = row(activity)
                    assertEquals(activity.getString(R.string.saved_audio_unchanged),
                        track.findViewById<TextView>(R.id.saved_audio_track_status).text.toString())
                    assertEquals(summary, track.findViewById<TextView>(R.id.saved_audio_track_summary).text.toString())
                    assertEquals(View.GONE, track.findViewById<View>(R.id.saved_audio_version_choice).visibility)
                    assertFalse(track.findViewById<View>(R.id.saved_audio_choose_enhanced).isEnabled)
                    assertEquals(View.GONE, track.findViewById<View>(R.id.saved_audio_track_estimate).visibility)
                    assertEquals(activity.getString(R.string.saved_audio_retry_analysis),
                        track.findViewById<TextView>(R.id.saved_audio_action).text.toString())
                    assertEquals(activity.getString(R.string.saved_audio_play_original),
                        track.findViewById<TextView>(R.id.saved_audio_play).text.toString())
                }
                screenshot("saved-audio-unchanged.png")
                scenario.onActivity { row(it).findViewById<View>(R.id.saved_audio_play).performClick() }
                await {
                    var playing = false
                    scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true && !SavedVersionPlayback.isEnhanced(it.mediaBrowser?.currentMediaItem) }
                    playing
                }
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.pause()
                    row(activity).findViewById<View>(R.id.saved_audio_clear).performClick()
                }
                await { repository.store.find(original.key) == null }
            }
            assertTrue("Clearing analysis must retain the original download", downloads.find(original.key) != null)
            assertEquals(sourceHash, sha256(source))
            assertFalse("An unchanged result must not create a lossless duplicate", repository.output(entry).exists())
        } finally {
            context.stopService(Intent(context, MusicService::class.java))
            repository.remove(original.key)
            downloads.delete(original)
            source.delete()
        }
    }

    private fun awaitRow(scenario: ActivityScenario<SavedAudioActivity>) = await {
        var ready = false
        scenario.onActivity { activity ->
            val list = activity.findViewById<RecyclerView>(R.id.saved_audio_list)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(1, 0)
            ready = activity.mediaBrowser != null && list.findViewHolderForAdapterPosition(1) != null
        }
        ready
    }

    private fun row(activity: SavedAudioActivity): View = activity.findViewById<RecyclerView>(R.id.saved_audio_list)
        .findViewHolderForAdapterPosition(1)!!.itemView

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition()) {
            assertTrue("Timed out waiting for saved audio UI", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100)
        }
    }

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        // Surface/window animations can continue after the main thread becomes idle.
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture saved audio screen" }
        try {
            val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
