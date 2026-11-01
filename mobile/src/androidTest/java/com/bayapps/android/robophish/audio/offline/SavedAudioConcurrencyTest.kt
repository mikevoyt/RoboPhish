package com.bayapps.android.robophish.audio.offline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.audio.AudienceAudioSettings
import com.bayapps.android.robophish.audio.SavedVersionPlayback
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.downloads.OfflineMedia
import com.bayapps.android.robophish.ui.SavedAudioActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Measures one actual emulator run; the observed time is not a general latency guarantee. */
@RunWith(AndroidJUnit4::class)
class SavedAudioConcurrencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun originalStartsDuringRealRenderAndKeepsPlayingAfterCancellation() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
        assertFalse(com.bayapps.android.robophish.utils.NetworkHelper.isOnline(context))
        val key = "concurrent-render-${UUID.randomUUID()}"
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC))
        directory.mkdirs()
        val source = File(directory, "$key.wav")
        val repository = EnhancementRepository.get(context)
        val settings = AudienceAudioSettings.get(context)
        val priorSettings = settings.current
        var download: DownloadEntry? = null
        var queued: EnhancementEntry? = null
        try {
            // Five minutes / ~53 MB on emulator storage. Reuse a one-second block so fixture
            // construction is bounded in memory and no large binary enters the repository.
            val sampleRate = 44100
            val frames = sampleRate * 300L
            val random = Random(642)
            val block = ShortArray(sampleRate * 2)
            // A known +15dB broad coloration makes this a genuine changed-output job.
            // Ordinary recordings may correctly abstain after analysis with no render pass.
            val gain = Math.pow(10.0, 15 / 40.0)
            val omega = 2 * Math.PI * 315 / sampleRate
            val alpha = Math.sin(omega) / (2 * 1.2)
            val a0 = 1 + alpha / gain
            val b0 = (1 + alpha * gain) / a0
            val b1 = -2 * Math.cos(omega) / a0
            val b2 = (1 - alpha * gain) / a0
            val a1 = b1
            val a2 = (1 - alpha / gain) / a0
            var z1 = 0.0
            var z2 = 0.0
            for (frame in 0 until sampleRate) {
                val x = (random.nextDouble() - 0.5) * 0.25
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                assertTrue("Synthetic source must not clip", kotlin.math.abs(y) < 0.95)
                block[2 * frame] = Math.round(y * 32768).toInt().toShort()
                block[2 * frame + 1] = Math.round(y * 0.86 * 32768).toInt().toShort()
            }
            val fixtureProbe = OfflineRestorationProfile.Analyzer(sampleRate, 2)
            repeat(8) { fixtureProbe.accept(block, block.size) }
            assertFalse("Concurrency fixture must warrant a render", fixtureProbe.finish().passthrough)
            PcmWavWriter(source, sampleRate, 2, frames).use { writer ->
                repeat(300) { writer.write(block, block.size) }
                writer.finish(frames)
            }
            val originalHash = digest(source)
            val original = DownloadEntry(trackId = key, showId = key, title = "Concurrent processing test",
                fileUri = Uri.fromFile(source).toString(), status = DownloadStatus.COMPLETED,
                durationMs = 300_000, bytesDownloaded = source.length(), totalBytes = source.length())
            download = original
            DownloadStore.get(context).upsert(original)
            settings.update(enabled = false)
            ActivityScenario.launch(SavedAudioActivity::class.java).use { scenario ->
                await(15_000) {
                    var connected = false
                    scenario.onActivity { connected = it.mediaBrowser != null }
                    connected
                }
                val job = repository.enqueue(original)
                queued = job
                await(120_000) {
                    val state = repository.store.find(key)
                    assertNotEquals("Processing failed: ${state?.error}", EnhancementStatus.FAILED, state?.status)
                    assertNotEquals("Synthetic coloration should require a render", EnhancementStatus.UNCHANGED, state?.status)
                    assertNotEquals("Render finished before concurrency could be observed", EnhancementStatus.READY, state?.status)
                    state?.status == EnhancementStatus.PROCESSING && repository.part(job).exists()
                }
                assertNull("No unfinished copy is resolved", repository.resolve(original))
                val startedAt = SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    activity.mediaBrowser!!.apply {
                        setMediaItem(OfflineMedia.toMediaItem(original)); prepare(); play()
                    }
                }
                var stateAtPlayback: EnhancementStatus? = null
                var positionAtStart = 0L
                await(20_000) {
                    var playing = false
                    scenario.onActivity { activity ->
                        val player = activity.mediaBrowser!!
                        playing = player.isPlaying
                        if (playing) {
                            assertFalse(SavedVersionPlayback.isEnhanced(player.currentMediaItem))
                            assertEquals(OfflineMedia.toMediaItem(original).mediaId, player.currentMediaItem!!.mediaId)
                            positionAtStart = player.currentPosition
                            stateAtPlayback = repository.store.find(key)?.status
                        }
                    }
                    playing
                }
                val observedStartupMs = SystemClock.elapsedRealtime() - startedAt
                assertTrue("Original must begin while the render is still active", stateAtPlayback in
                    setOf(EnhancementStatus.ANALYZING, EnhancementStatus.PROCESSING))
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "SavedAudioConcurrency: observed original startup ${observedStartupMs}ms; render=$stateAtPlayback\n")
                })
                assertTrue("Cancellation is exercised on an active job", repository.store.find(key)!!.isActive)
                repository.cancel(key)
                assertEquals(EnhancementStatus.CANCELLED, repository.store.find(key)!!.status)
                await(15_000) {
                    val info = WorkManager.getInstance(context).getWorkInfoById(UUID.fromString(job.generation))
                        .get(5, TimeUnit.SECONDS)
                    info?.state == WorkInfo.State.CANCELLED && !repository.part(job).exists()
                }
                // Observe progression after cancellation and worker cleanup, rather than merely
                // reading the player's old isPlaying value at the moment cancel was requested.
                var positionAfterCancel = 0L
                await(10_000) {
                    var advanced = false
                    scenario.onActivity { activity ->
                        val player = activity.mediaBrowser!!
                        assertNull(player.playerError)
                        positionAfterCancel = player.currentPosition
                        advanced = player.isPlaying && positionAfterCancel >= positionAtStart + 500
                    }
                    advanced
                }
                assertEquals(originalHash, digest(source))
                assertTrue(OfflineMedia.isPlayable(original))
                assertNull(repository.resolve(original))
                assertFalse(repository.output(job).exists())
                assertNull(repository.store.find(key)!!.fileUri)
                val evidence = JSONObject().apply {
                    put("sourceFrames", frames); put("sourceBytes", source.length())
                    put("observedStartupMs", observedStartupMs); put("renderStateAtPlaybackStart", stateAtPlayback!!.name)
                    put("positionAtStartMs", positionAtStart); put("positionAfterCancellationMs", positionAfterCancel)
                    put("finalJobState", "CANCELLED"); put("sourceHashUnchanged", true)
                    put("partialExists", false); put("publishedCopyExists", false)
                    put("scope", "One emulator isPlaying observation; not a general startup latency or underrun measurement")
                }
                File(context.getExternalFilesDir(null), "saved-audio-concurrency.json").writeText(evidence.toString(2))
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SavedAudioConcurrency evidence: $evidence\n") })
                scenario.onActivity { it.mediaBrowser!!.pause() }
            }
        } finally {
            repository.cancel(key)
            repository.remove(key)
            settings.update(enabled = priorSettings.enabled, amount = priorSettings.amount, mode = priorSettings.mode)
            context.stopService(Intent(context, MusicService::class.java))
            download?.let { DownloadStore.get(context).delete(it) }
            queued?.let { repository.part(it).delete(); repository.output(it).delete() }
            source.delete()
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count == -1) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            assertTrue("Timed out waiting for concurrent processing/playback", SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(20)
        }
    }
}
