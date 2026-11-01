package com.bayapps.android.robophish.audio

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.audio.offline.EnhancementRepository
import com.bayapps.android.robophish.audio.offline.EnhancementStatus
import com.bayapps.android.robophish.audio.offline.PcmWavWriter
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.downloads.OfflineMedia
import com.bayapps.android.robophish.ui.SavedAudioActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Real decode/analyze/render job followed by actual Media3 playback, with radios disconnected. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedVersionPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun savedCopyIsExplicitPlaysOfflineAndFallsBackWithoutChangingOriginal() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse(com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC))
        directory.mkdirs()
        val source = File(directory, "saved_version_fixture.mp3")
        instrumentation.context.assets.open("offline-coloration.mp3").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val originalHash = digest(source)
        val entry = DownloadEntry(trackId = "saved-version-test", showId = "saved-version-show", title = "Saved version test",
            fileUri = Uri.fromFile(source).toString(), status = DownloadStatus.COMPLETED,
            bytesDownloaded = source.length(), totalBytes = source.length())
        val leadSource = File(directory, "saved_version_queue_lead.wav")
        val leadFrames = 22050L * 70
        PcmWavWriter(leadSource, 22050, 1, leadFrames).use { writer ->
            val tone = ShortArray(2205) { (1000 * kotlin.math.sin(2 * Math.PI * 440 * it / 22050)).toInt().toShort() }
            repeat(700) { writer.write(tone, tone.size) }
            writer.finish(leadFrames)
        }
        val lead = entry.copy(trackId = "saved-version-queue-lead", title = "Original queue lead",
            fileUri = Uri.fromFile(leadSource).toString(), bytesDownloaded = leadSource.length(),
            totalBytes = leadSource.length(), durationMs = 70_000)
        val repository = EnhancementRepository.get(context)
        val settings = AudienceAudioSettings.get(context)
        val before = settings.current
        try {
            DownloadStore.get(context).upsert(entry)
            DownloadStore.get(context).upsert(lead)
            repository.remove(entry.key)
            repository.enqueue(entry)
            assertEquals(Uri.fromFile(source), SavedVersionPlayback.resolve(context, entry).localConfiguration!!.uri)
            assertFalse("Queuing processing never selects the copy", SavedVersionPlayback.isEnhanced(SavedVersionPlayback.resolve(context, entry)))
            await("render READY", 120_000, { repository.store.find(entry.key).toString() }) {
                val current = repository.store.find(entry.key)
                assertNotEquals("Background processing failed: ${current?.error}", EnhancementStatus.FAILED, current?.status)
                current?.status == EnhancementStatus.READY
            }
            val copy = requireNotNull(repository.store.find(entry.key))
            val copyFile = File(requireNotNull(Uri.parse(copy.fileUri).path))
            assertTrue(copyFile.length() > 44)
            assertEquals(copy.outputBytes, copyFile.length())
            assertFalse("Completion must not opt the listener in", copy.preferEnhanced)
            assertEquals(originalHash, digest(source))
            assertTrue(repository.select(entry.key, true))
            val enhancedItem = SavedVersionPlayback.resolve(context, entry)
            assertTrue(SavedVersionPlayback.isEnhanced(enhancedItem))
            assertEquals(Uri.fromFile(copyFile), enhancedItem.localConfiguration!!.uri)
            settings.update(enabled = true, amount = 1f, mode = AudienceDsp.Mode.CLARITY)
            ActivityScenario.launch(SavedAudioActivity::class.java).use { scenario ->
                await("controller connection", diagnostics = { playerSnapshot(scenario) }) {
                    var connected = false; scenario.onActivity { connected = it.mediaBrowser != null }; connected
                }
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply { setMediaItem(OfflineMedia.toMediaItem(entry)); prepare(); play() }
                }
                await("initial enhanced playback", diagnostics = { playerSnapshot(scenario) }) {
                    var playing = false; scenario.onActivity { playing = it.mediaBrowser?.isPlaying == true }; playing
                }
                scenario.onActivity { activity ->
                    assertTrue(SavedVersionPlayback.isEnhanced(activity.mediaBrowser!!.currentMediaItem))
                    assertTrue(settings.playingSavedEnhancement)
                }
                // The real PCM stage must not apply the user's live EQ a second time.
                val processor = AudienceAudioProcessor(settings)
                processor.configure(AudioFormat(44100, 2, C.ENCODING_PCM_16BIT))
                processor.flush()
                processor.queueInput(androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER)
                val samples = shortArrayOf(12000, -9000, 300, -300, 0, 0, 32760, -32760)
                val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
                samples.forEach(buffer::putShort)
                buffer.flip()
                processor.queueInput(buffer)
                val output = processor.output.order(ByteOrder.nativeOrder())
                assertArrayEquals(samples, ShortArray(samples.size) { output.short })
                processor.reset()
                // Stop releases the initially played WAV. Resolve the new queue while IDLE,
                // without preparing it, so the selected copy provably has no open descriptor.
                // A playing 70s mono lead does NOT guarantee this: Media3's 1MiB progressive
                // load checks can reach its EOF and preload the next file on fast emulators.
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply { stop(); clearMediaItems() }
                }
                await("stop and release initial enhanced file", diagnostics = {
                    "${playerSnapshot(scenario)}, openCopyDescriptors=${openDescriptors(copyFile)}"
                }) {
                    var stopped = false
                    scenario.onActivity { stopped = it.mediaBrowser!!.playbackState == Player.STATE_IDLE &&
                        it.mediaBrowser!!.mediaItemCount == 0 }
                    stopped && openDescriptors(copyFile).isEmpty()
                }
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply {
                        setMediaItems(listOf(OfflineMedia.toMediaItem(lead), OfflineMedia.toMediaItem(entry)))
                    }
                }
                await("resolved idle queue", diagnostics = { playerSnapshot(scenario) }) {
                    var queued = false
                    scenario.onActivity { activity ->
                        val player = activity.mediaBrowser!!
                        queued = player.playbackState == Player.STATE_IDLE && player.currentMediaItemIndex == 0 &&
                            player.mediaItemCount == 2 && SavedVersionPlayback.isEnhanced(player.getMediaItemAt(1))
                    }
                    queued
                }
                assertTrue("The queued WAV must be unopened before deletion: ${openDescriptors(copyFile)}",
                    openDescriptors(copyFile).isEmpty())
                recordSnapshot("before deleting unopened copy", scenario)
                repository.remove(entry.key)
                assertFalse("Selected copy is physically absent before prepare", copyFile.exists())
                assertTrue("Removing the copy preserves original", OfflineMedia.isPlayable(entry))
                assertEquals(originalHash, digest(source))
                assertFalse(SavedVersionPlayback.isEnhanced(SavedVersionPlayback.resolve(context, entry)))
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply { seekTo(1, 2000L); prepare(); play() }
                }
                await("deleted selected copy falls back to original", diagnostics = { playerSnapshot(scenario) }) {
                    var originalPlaying = false
                    scenario.onActivity { originalPlaying = it.mediaBrowser?.isPlaying == true &&
                        it.mediaBrowser?.currentMediaItemIndex == 1 &&
                        !SavedVersionPlayback.isEnhanced(it.mediaBrowser?.currentMediaItem) }
                    originalPlaying
                }
                recordSnapshot("original playing after missing-copy recovery", scenario)
                assertFalse(settings.playingSavedEnhancement)
                scenario.onActivity {
                    assertTrue("Fallback retains the requested time position", it.mediaBrowser!!.currentPosition >= 1900)
                    assertEquals("Fallback keeps the queue", 2, it.mediaBrowser!!.mediaItemCount)
                    it.mediaBrowser!!.pause()
                }
            }
        } finally {
            repository.remove(entry.key)
            settings.update(enabled = before.enabled, amount = before.amount, mode = before.mode)
            context.stopService(Intent(context, MusicService::class.java))
            DownloadStore.get(context).delete(entry)
            DownloadStore.get(context).delete(lead)
            source.delete()
            leadSource.delete()
        }
    }

    private fun digest(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun playerSnapshot(scenario: ActivityScenario<SavedAudioActivity>): String {
        var snapshot = "controller unavailable"
        scenario.onActivity { activity ->
            activity.mediaBrowser?.let { player ->
                snapshot = "state=${player.playbackState}, playing=${player.isPlaying}, playWhenReady=${player.playWhenReady}, " +
                    "index=${player.currentMediaItemIndex}, count=${player.mediaItemCount}, position=${player.currentPosition}, " +
                    "buffered=${player.bufferedPosition}, mediaId=${player.currentMediaItem?.mediaId}, " +
                    "enhanced=${SavedVersionPlayback.isEnhanced(player.currentMediaItem)}, " +
                    "error=${player.playerError?.errorCode}:${player.playerError?.message}"
            }
        }
        return snapshot
    }

    private fun recordSnapshot(stage: String, scenario: ActivityScenario<SavedAudioActivity>) {
        val evidence = "$stage: ${playerSnapshot(scenario)}"
        android.util.Log.i("SavedVersionPlaybackTest", evidence)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$evidence\n") })
    }

    private fun openDescriptors(file: File): List<String> {
        val descriptors = requireNotNull(File("/proc/self/fd").listFiles()) { "Cannot verify own open file descriptors" }
        var readable = 0
        val matches = descriptors.mapNotNull { fd ->
            runCatching {
                val target = Os.readlink(fd.absolutePath).removeSuffix(" (deleted)")
                readable++
                // The random generation filename also matches FUSE's alternative mount spelling.
                if (File(target).name == file.name) "${fd.name}:$target" else null
            }.getOrNull() // An unrelated descriptor can close while enumerating.
        }
        check(readable > 0) { "Cannot inspect own file descriptors to establish the test precondition" }
        return matches
    }

    private fun await(stage: String, timeout: Long = 15_000, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= until) fail("Timed out during $stage: ${diagnostics()}")
            SystemClock.sleep(100)
        }
    }
}
