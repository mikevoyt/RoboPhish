package com.bayapps.android.robophish.audio.offline

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.downloads.OfflineMedia
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

class EnhancementRepository private constructor(context: Context) {
    private val context = context.applicationContext
    val store = EnhancementStore.get(this.context)
    private val work get() = WorkManager.getInstance(context)

    init {
        // Recover a crash between journaling and scheduling, without blocking app/playback startup.
        recovery.execute { runCatching { recover() } }
    }

    /** Call mutations on Dispatchers.IO. Only complete local originals are accepted. */
    @Synchronized fun enqueue(download: DownloadEntry, forceAnalysis: Boolean = false): EnhancementEntry {
        require(OfflineMedia.isPlayable(download)) { "Download the original recording first" }
        val source = requireNotNull(OfflineMedia.file(download)).canonicalFile
        val existing = store.find(download.key)
        if (existing != null && matches(existing, download, source) &&
            (existing.isActive || validOutput(existing) != null || (!forceAnalysis &&
                existing.status == EnhancementStatus.UNCHANGED && !existing.sourceSha256.isNullOrEmpty()))) return existing
        existing?.let { cancel(download.key); deleteOutput(it) }
        val entry = EnhancementEntry(download.key, download.title, UUID.randomUUID().toString(),
            source.toURI().toString(), source.length(), source.lastModified(), ALGORITHM_VERSION)
        store.put(entry)
        try { schedule(entry, ExistingWorkPolicy.REPLACE) }
        catch (error: Exception) {
            store.update(entry.trackKey, entry.generation) { it.copy(status = EnhancementStatus.FAILED,
                error = "Could not schedule processing. Retry when the app is available.") }
            throw error
        }
        return store.find(download.key) ?: entry
    }

    @Synchronized fun retry(trackKey: String): EnhancementEntry {
        val download = DownloadStore.get(context).getAll().firstOrNull { it.key == trackKey }
            ?: throw IllegalStateException("Original download is no longer available")
        return enqueue(download, forceAnalysis = true)
    }

    @Synchronized fun cancel(trackKey: String) {
        val entry = store.find(trackKey) ?: return
        if (!entry.isActive) return
        store.update(trackKey, entry.generation) { it.copy(status = EnhancementStatus.CANCELLED,
            error = null, preferEnhanced = false) }
        work.cancelUniqueWork(workName(trackKey))
    }

    @Synchronized fun remove(trackKey: String) {
        val entry = store.find(trackKey) ?: return
        // Invalidate the generation before cancellation or file removal; stale workers cannot publish.
        store.remove(trackKey)
        work.cancelUniqueWork(workName(trackKey))
        try { deleteOutput(entry) }
        catch (error: Exception) {
            // Keep a removable tombstone if storage refused deletion, never a live generation.
            store.put(entry.copy(status = EnhancementStatus.FAILED, preferEnhanced = false,
                error = "Unable to remove the saved version. Check storage and try removing it again."))
            throw error
        }
    }

    @Synchronized fun select(trackKey: String, enhanced: Boolean): Boolean {
        val entry = store.find(trackKey) ?: return !enhanced
        val original = DownloadStore.get(context).getAll().firstOrNull { it.key == trackKey }
        if (enhanced && (original == null || !matches(entry, original) || validOutput(entry) == null)) return false
        store.update(trackKey, entry.generation) { it.copy(preferEnhanced = enhanced) }
        return true
    }

    /** Lightweight file checks only. Hashing/analysis never occurs while resolving playback. */
    fun resolve(download: DownloadEntry): Uri? {
        val entry = store.find(download.key) ?: return null
        if (!entry.preferEnhanced || !matches(entry, download)) return null
        return validOutput(entry)?.let(Uri::fromFile)
    }

    internal fun directory(): File = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)) {
        "Storage unavailable"
    }, "enhanced").also { check(it.isDirectory || it.mkdirs()) { "Enhanced audio storage is unavailable" } }

    internal fun part(entry: EnhancementEntry) = File(directory(), "${entry.generation}.part")
    internal fun output(entry: EnhancementEntry) = File(directory(), "${entry.generation}.wav")

    @Synchronized internal fun current(entry: EnhancementEntry): Boolean =
        store.find(entry.trackKey)?.let { it.generation == entry.generation && it.isActive } == true

    @Synchronized internal fun progress(entry: EnhancementEntry, status: EnhancementStatus, percent: Int) {
        if (current(entry)) store.update(entry.trackKey, entry.generation) {
            it.copy(status = status, progress = percent.coerceIn(0, 99), error = null)
        }
    }

    @Synchronized internal fun publish(entry: EnhancementEntry, sourceHash: String, bytes: Long, summary: String? = null): Boolean {
        if (!current(entry)) return false
        val original = DownloadStore.get(context).getAll().firstOrNull { it.key == entry.trackKey }
        check(original != null && matches(entry, original)) { "Original recording changed during processing" }
        val partial = part(entry)
        val finished = output(entry)
        check(partial.length() == bytes && bytes > 44) { "Processed audio is incomplete" }
        check(partial.renameTo(finished)) { "Unable to save processed recording" }
        // If the process dies before this journal write, the worker reruns and replaces its own output.
        store.update(entry.trackKey, entry.generation) { it.copy(status = EnhancementStatus.READY,
            progress = 100, sourceSha256 = sourceHash, fileUri = Uri.fromFile(finished).toString(),
            outputBytes = bytes, error = null, preferEnhanced = false, profileSummary = summary) }
        return true
    }

    /** A successful abstention is a journal result, never a large duplicate WAV or selected copy. */
    @Synchronized internal fun publishUnchanged(entry: EnhancementEntry, sourceHash: String, summary: String): Boolean {
        if (!current(entry)) return false
        val original = DownloadStore.get(context).getAll().firstOrNull { it.key == entry.trackKey }
        check(original != null && matches(entry, original)) { "Original recording changed during analysis" }
        require(sourceHash.matches(Regex("[0-9a-f]{64}"))) { "Missing source verification" }
        deleteOutput(entry)
        store.update(entry.trackKey, entry.generation) { it.copy(status = EnhancementStatus.UNCHANGED,
            progress = 100, sourceSha256 = sourceHash, fileUri = null, outputBytes = 0,
            error = null, preferEnhanced = false, profileSummary = summary) }
        return true
    }

    @Synchronized internal fun failed(entry: EnhancementEntry, message: String) {
        if (current(entry)) store.update(entry.trackKey, entry.generation) {
            it.copy(status = EnhancementStatus.FAILED, error = message, preferEnhanced = false)
        }
    }

    @Synchronized internal fun interrupted(entry: EnhancementEntry) {
        if (current(entry)) store.update(entry.trackKey, entry.generation) {
            it.copy(status = EnhancementStatus.QUEUED, progress = 0, error = null)
        }
    }

    private fun schedule(entry: EnhancementEntry, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequest.Builder(EnhancementWorker::class.java)
            .setId(UUID.fromString(entry.generation))
            .setInputData(workDataOf("trackKey" to entry.trackKey, "generation" to entry.generation))
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .addTag("saved-audio").build()
        work.enqueueUniqueWork(workName(entry.trackKey), policy, request).result.get()
    }

    @Synchronized private fun recover() {
        val downloads = DownloadStore.get(context).getAll().associateBy { it.key }
        store.getAll().filter { it.status == EnhancementStatus.READY || it.status == EnhancementStatus.UNCHANGED }.forEach { entry ->
            val original = downloads[entry.trackKey]
            if (original == null || !matches(entry, original) || entry.sourceSha256.isNullOrEmpty() ||
                (entry.status == EnhancementStatus.READY && validOutput(entry) == null)) {
                store.update(entry.trackKey, entry.generation) { it.copy(status = EnhancementStatus.FAILED,
                    preferEnhanced = false, error = "The saved version or original is unavailable. Download the original and retry.") }
            }
        }
        store.getAll().filter { it.isActive }.forEach { entry ->
            val info = work.getWorkInfoById(UUID.fromString(entry.generation)).get()
            when (info?.state) {
                // The journal is authoritative. KEEP could retain an obsolete generation if
                // the process died between persisting a replacement and enqueuing it.
                null -> schedule(entry, ExistingWorkPolicy.REPLACE)
                WorkInfo.State.CANCELLED -> store.update(entry.trackKey, entry.generation) {
                    it.copy(status = EnhancementStatus.CANCELLED, preferEnhanced = false)
                }
                WorkInfo.State.FAILED, WorkInfo.State.SUCCEEDED -> failed(entry, "Processing was interrupted. Retry to finish this recording.")
                else -> Unit // WorkManager restarts interrupted workers with this generation.
            }
        }
        // Only delete our unreferenced render files. Active generations retain restartable partials.
        val keep = store.getAll().flatMap { entry ->
            if (entry.isActive) listOf("${entry.generation}.part", "${entry.generation}.wav")
            else if (entry.status == EnhancementStatus.READY) listOf("${entry.generation}.wav") else emptyList()
        }.toSet()
        directory().listFiles()?.filter { it.name !in keep &&
            (it.extension == "wav" || it.extension == "part") && runCatching { UUID.fromString(it.nameWithoutExtension) }.isSuccess }
            ?.forEach { it.delete() }
    }

    private fun matches(entry: EnhancementEntry, download: DownloadEntry, source: File? = OfflineMedia.file(download)): Boolean =
        source != null && OfflineMedia.isPlayable(download) && entry.algorithmVersion == ALGORITHM_VERSION &&
            runCatching { source.canonicalFile.toURI().toString() == entry.sourceUri && source.length() == entry.sourceSize &&
                source.lastModified() == entry.sourceModified }.getOrDefault(false)

    private fun validOutput(entry: EnhancementEntry): File? {
        if (entry.status != EnhancementStatus.READY || entry.sourceSha256.isNullOrEmpty()) return null
        return runCatching {
            val uri = Uri.parse(entry.fileUri ?: return null)
            if (uri.scheme != "file") return null
            val file = File(requireNotNull(uri.path)).canonicalFile
            if (file != output(entry).canonicalFile || !file.canRead() || file.length() != entry.outputBytes || file.length() <= 44) null else file
        }.getOrNull()
    }

    private fun deleteOutput(entry: EnhancementEntry) {
        // Paths are derived from validated UUIDs, never from persisted arbitrary URIs.
        require(UUID.fromString(entry.generation).toString() == entry.generation) { "Invalid render generation" }
        listOf(part(entry), output(entry)).forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Unable to remove enhanced audio file")
        }
    }

    companion object {
        const val ALGORITHM_VERSION = OfflineRestorationProfile.VERSION
        private val recovery = Executors.newSingleThreadExecutor { r -> Thread(r, "audio-job-recovery").apply { isDaemon = true } }
        @Volatile private var instance: EnhancementRepository? = null
        fun get(context: Context): EnhancementRepository = instance ?: synchronized(this) {
            instance ?: EnhancementRepository(context).also { instance = it }
        }
        private fun workName(key: String) = "audio-" + MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
