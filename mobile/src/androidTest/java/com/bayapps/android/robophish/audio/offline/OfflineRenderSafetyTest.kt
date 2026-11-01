package com.bayapps.android.robophish.audio.offline

import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.ui.SavedAudioActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OfflineRenderSafetyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun vbrWithoutSeekHeaderDecodesEntireKnownDuration() {
        emulatorOnly()
        val original = File(context.cacheDir, "vbr-${UUID.randomUUID()}.mp3")
        val output = File(context.cacheDir, "vbr-${UUID.randomUUID()}.part")
        try {
            // Deterministic 8s fixture: 3s 440Hz tone then 5s seeded noise, libmp3lame
            // quality 2 with -write_xing 0. Estimated container duration is not authoritative.
            instrumentation.context.assets.open("offline-vbr-no-xing.mp3").use { input ->
                original.outputStream().use { input.copyTo(it) }
            }
            val result = OfflineAudioRenderer().render(original, output, {}, { _, _ -> })
            assertTrue("Full 8s recording including codec priming", result.frames in 352_000L..360_000L)
            if (result.changed) {
                assertEquals(44 + result.frames * 4, result.outputBytes)
                assertEquals(result.outputBytes, output.length())
                val header = ByteArray(4)
                output.inputStream().use { assertEquals(4, it.read(header)) }
                assertEquals("RIFF", String(header, Charsets.US_ASCII))
            } else {
                assertEquals(0, result.outputBytes)
                assertFalse("Abstention does not write a duplicate WAV", output.exists())
            }
        } finally { original.delete(); output.delete() }
    }

    @Test fun realWorkerAbstentionKeepsOriginalAndStoresNoAudioCopy() {
        emulatorOnly()
        val original = fixture()
        val repository = EnhancementRepository.get(context)
        val key = "abstention-${UUID.randomUUID()}"
        val download = DownloadEntry(trackId = key, title = "Unchanged original", fileUri = Uri.fromFile(original).toString(),
            status = DownloadStatus.COMPLETED, totalBytes = original.length())
        val originalHash = sha256(original)
        try {
            DownloadStore.get(context).upsert(download)
            ActivityScenario.launch(SavedAudioActivity::class.java).use {
                val job = repository.enqueue(download)
                val deadline = SystemClock.elapsedRealtime() + 120_000
                while (repository.store.find(key)?.isActive == true) {
                    assertTrue("Timed out waiting for real analysis", SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20)
                }
                val result = requireNotNull(repository.store.find(key))
                assertEquals("${result.status}: ${result.error}", EnhancementStatus.UNCHANGED, result.status)
                assertEquals(100, result.progress)
                assertEquals(0, result.outputBytes)
                assertNull(result.fileUri)
                assertFalse(result.preferEnhanced)
                assertEquals(originalHash, result.sourceSha256)
                assertEquals(originalHash, sha256(original))
                assertFalse(repository.part(job).exists())
                assertFalse(repository.output(job).exists())
                assertFalse("An unchanged result cannot be selected as an enhancement", repository.select(key, true))
                assertNull(repository.resolve(download))
                assertEquals("Repeated queueing reuses the same completed analysis", job.generation,
                    repository.enqueue(download).generation)
            }
        } finally { repository.remove(key); DownloadStore.get(context).delete(download); original.delete() }
    }

    @Test fun malformedOriginalNeverPublishesAPlayableFile() {
        emulatorOnly()
        val original = File(context.cacheDir, "invalid-${UUID.randomUUID()}.mp3")
        val output = File(context.cacheDir, "invalid-${UUID.randomUUID()}.part")
        try {
            original.writeBytes(ByteArray(4096) { (it % 251).toByte() })
            val before = original.readBytes()
            var failed = false
            try { OfflineAudioRenderer().render(original, output, {}, { _, _ -> }) }
            catch (_: Exception) { failed = true }
            assertTrue("Malformed audio must fail", failed)
            assertFalse("No playable output", output.exists())
            assertArrayEquals(before, original.readBytes())
        } finally { original.delete(); output.delete() }
    }

    @Test fun cancellationDuringAnalysisLeavesOriginalIntact() {
        emulatorOnly()
        val original = fixture()
        val output = File(context.cacheDir, "cancel-${UUID.randomUUID()}.part")
        try {
            val before = original.readBytes()
            var checks = 0
            var cancelled = false
            try {
                OfflineAudioRenderer().render(original, output, { if (++checks > 4) throw CancellationException() }, { _, _ -> })
            } catch (_: CancellationException) { cancelled = true }
            assertTrue(cancelled)
            assertFalse("Cancelled analysis produces no saved audio", output.exists())
            assertArrayEquals(before, original.readBytes())
        } finally { original.delete(); output.delete() }
    }

    @Test fun removedOrCancelledGenerationCannotPublishOrResurrect() {
        emulatorOnly()
        val original = fixture()
        val repository = EnhancementRepository.get(context)
        val key = "render-safety-${UUID.randomUUID()}"
        val download = DownloadEntry(trackId = key, title = "Render safety", fileUri = Uri.fromFile(original).toString(),
            status = DownloadStatus.COMPLETED, totalBytes = original.length())
        val entry = EnhancementEntry(key, download.title, UUID.randomUUID().toString(),
            original.canonicalFile.toURI().toString(), original.length(), original.lastModified(), EnhancementRepository.ALGORITHM_VERSION)
        try {
            DownloadStore.get(context).upsert(download)
            repository.store.put(entry)
            repository.cancel(key)
            repository.part(entry).writeBytes(ByteArray(100))
            assertFalse(repository.publish(entry, "hash", 100))
            assertEquals(EnhancementStatus.CANCELLED, repository.store.find(key)!!.status)
            repository.remove(key)
            assertFalse(repository.publish(entry, "hash", 100))
            repository.progress(entry, EnhancementStatus.PROCESSING, 99)
            assertNull(repository.store.find(key))
            assertTrue(original.isFile)
            assertFalse(repository.output(entry).exists())
        } finally {
            repository.remove(key); DownloadStore.get(context).delete(download); original.delete()
        }
    }

    @Test fun journalRoundTripAndStaleGenerationUpdateAreSafe() {
        emulatorOnly()
        val store = EnhancementStore.get(context)
        val key = "journal-${UUID.randomUUID()}"
        val first = EnhancementEntry(key, "test", UUID.randomUUID().toString(), "file:/not-used", 100, 200,
            EnhancementRepository.ALGORITHM_VERSION, sourceSha256 = "abc", status = EnhancementStatus.READY,
            progress = 100, fileUri = "file:/not-used.wav", outputBytes = 1000, preferEnhanced = true)
        try {
            assertEquals(first, EnhancementStore.decode(EnhancementStore.encode(first)))
            val second = first.copy(generation = UUID.randomUUID().toString(), preferEnhanced = false)
            store.put(second)
            assertFalse(store.update(key, first.generation) { it.copy(preferEnhanced = true) })
            assertEquals(second, store.find(key))
        } finally { store.remove(key) }
    }

    private fun fixture(): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "render-${UUID.randomUUID()}.mp3")
        file.parentFile!!.mkdirs()
        instrumentation.context.assets.open("offline-tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun emulatorOnly() {
        require(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic fixtures require an emulator" }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
