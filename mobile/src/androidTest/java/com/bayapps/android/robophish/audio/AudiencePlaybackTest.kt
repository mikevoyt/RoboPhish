package com.bayapps.android.robophish.audio

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import androidx.appcompat.widget.SwitchCompat
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.downloads.OfflineMedia
import com.bayapps.android.robophish.ui.AudienceAudioActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the actual Media3 PCM stage and UI while a local MP3 is playing. */
@RunWith(AndroidJUnit4::class)
class AudiencePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun liveControlsKeepOfflineMp3PlayingWithoutReload() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse(com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        val settings = AudienceAudioSettings.get(context)
        val original = settings.current
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "audio_controls_fixture.mp3")
        instrumentation.context.assets.open("offline-tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val entry = DownloadEntry(trackId = "audio-test", showId = "audio-test-show", title = "Audio control test",
            fileUri = Uri.fromFile(file).toString(), status = DownloadStatus.COMPLETED,
            bytesDownloaded = file.length(), totalBytes = file.length())
        val interruptions = AtomicInteger()
        val transitions = AtomicInteger()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) interruptions.incrementAndGet() }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { transitions.incrementAndGet() }
        }
        try {
            DownloadStore.get(context).upsert(entry)
            settings.update(enabled = false, amount = 0.6f, mode = AudienceDsp.Mode.RUMBLE)
            ActivityScenario.launch(AudienceAudioActivity::class.java).use { scenario ->
                await { var connected = false; scenario.onActivity { connected = it.mediaBrowser != null }; connected }
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply {
                        setMediaItem(OfflineMedia.toMediaItem(entry)); prepare(); play()
                    }
                }
                await { var playing = false; scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true }; playing }
                var start = 0L
                scenario.onActivity {
                    start = it.mediaBrowser!!.currentPosition
                    it.mediaBrowser!!.addListener(listener)
                    it.findViewById<SwitchCompat>(R.id.audio_enabled).performClick()
                }
                assertTrue(settings.current.enabled)
                SystemClock.sleep(300)
                screenshot("audio-controls.png")
                scenario.onActivity {
                    it.findViewById<View>(R.id.audio_clarity).performClick()
                    it.findViewById<ScrollView>(R.id.audio_scroll).smoothScrollTo(0, it.findViewById<View>(R.id.audio_compare).bottom)
                    it.findViewById<Button>(R.id.audio_compare).performClick()
                }
                assertEquals(AudienceDsp.Mode.CLARITY, settings.current.mode)
                assertTrue(settings.compareOriginal)
                SystemClock.sleep(300)
                screenshot("audio-original-comparison.png")
                scenario.onActivity { it.findViewById<Button>(R.id.audio_compare).performClick() }
                assertFalse(settings.compareOriginal)
                SystemClock.sleep(300)
                scenario.onActivity { activity ->
                    val player = activity.mediaBrowser!!
                    assertTrue(player.isPlaying)
                    assertTrue("Position advances through live changes", player.currentPosition > start + 500)
                    assertEquals(1, player.mediaItemCount)
                    assertEquals(OfflineMedia.toMediaItem(entry).mediaId, player.currentMediaItem!!.mediaId)
                    player.removeListener(listener)
                    player.pause()
                }
                assertEquals("No playback interruptions during controls", 0, interruptions.get())
                assertEquals("No media reload during controls", 0, transitions.get())
            }
        } finally {
            settings.compareOriginal = false
            settings.update(enabled = original.enabled, amount = original.amount, mode = original.mode)
            context.stopService(Intent(context, MusicService::class.java))
            DownloadStore.get(context).delete(entry)
            file.delete()
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            assertTrue("Timed out waiting for audio playback", SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(100)
        }
    }

    private fun screenshot(name: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/robophish-offline/$name")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
