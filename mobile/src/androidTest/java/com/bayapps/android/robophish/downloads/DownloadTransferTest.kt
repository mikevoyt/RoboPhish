package com.bayapps.android.robophish.downloads

import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bayapps.android.robophish.ui.DownloadsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android DownloadManager transfers against an in-emulator server. */
@RunWith(AndroidJUnit4::class)
class DownloadTransferTest {
    @Test fun backgroundTransferDeduplicatesRetriesAndRemovesFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = DownloadRepository.get(context)
        repository.remove(repository.store.getAll())
        repository.setWifiOnly(false)
        val bytes = ByteArray(64 * 1024) { (it % 127).toByte() }
        val fail = AtomicBoolean(false)
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val output = socket.getOutputStream()
                        if (fail.get()) output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        else {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ".toByteArray())
                            output.write((bytes.size.toString() + "\r\nConnection: close\r\n\r\n").toByteArray())
                            output.write(bytes)
                        }
                        output.flush()
                    }
                } catch (_: Exception) { }
            }
        }
        ActivityScenario.launch(DownloadsActivity::class.java).use {
            try {
                fun item(id: String) = MediaItem.Builder().setMediaId("__TRACKS_BY_SHOW__/test|$id")
                    .setUri("http://127.0.0.1:" + server.localPort + "/$id.mp3")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Transfer fixture")
                        .setIsPlayable(true).setTrackNumber(id.toInt()).build()).build()
                assertEquals(1, repository.enqueue(listOf(item("1")), "test", "Test", "2026-01-01", null).added)
                await { repository.reconcile().firstOrNull()?.status == DownloadStatus.COMPLETED }
                val downloaded = repository.store.getAll().single()
                assertArrayEquals(bytes, OfflineMedia.file(downloaded)!!.readBytes())
                assertEquals(0, repository.enqueue(listOf(item("1")), "test", "Test", null, null).added)
                assertEquals(1, repository.store.getAll().size)
                fail.set(true)
                assertEquals(1, repository.enqueue(listOf(item("2")), "test", "Test", null, null).added)
                await { repository.reconcile().any { e -> e.trackId == "2" && e.status == DownloadStatus.FAILED } }
                fail.set(false)
                val failed = repository.store.getAll().first { e -> e.trackId == "2" }
                assertEquals(1, repository.retry(listOf(failed)).added)
                await { repository.reconcile().all { e -> e.status == DownloadStatus.COMPLETED } }
                assertEquals(2, repository.store.getAll().size)
                val files = repository.store.getAll().mapNotNull(OfflineMedia::file)
                repository.remove(repository.store.getAll())
                assertTrue(repository.store.getAll().isEmpty())
                assertTrue(files.none { file -> file.exists() })
                assertEquals(1, repository.enqueue(listOf(item("3")), "test", "Test", null, null).added)
                val cancelled = repository.store.getAll()
                repository.remove(cancelled)
                android.os.SystemClock.sleep(500)
                assertTrue(repository.reconcile().isEmpty())
                assertTrue(cancelled.mapNotNull(OfflineMedia::file).none { file -> file.exists() })
            } finally {
                server.close()
                executor.shutdownNow()
                repository.remove(repository.store.getAll())
                repository.setWifiOnly(true)
                context.stopService(Intent(context, com.bayapps.android.robophish.MusicService::class.java))
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 30_000
        while (!condition()) {
            assertTrue("DownloadManager transfer timed out", android.os.SystemClock.elapsedRealtime() < until)
            android.os.SystemClock.sleep(200)
        }
    }
}
