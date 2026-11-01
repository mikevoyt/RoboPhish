package com.bayapps.android.robophish.audio.offline

import java.io.File
import java.security.MessageDigest

/** Two streaming decode passes: estimate a track profile, then render the entire recording. */
internal class OfflineAudioRenderer {
    data class Result(val sourceSha256: String, val outputBytes: Long, val frames: Long,
                      val summary: String, val changed: Boolean)

    fun render(source: File, destination: File, checkCancelled: () -> Unit,
               progress: (EnhancementStatus, Int) -> Unit): Result {
        val initialSize = source.length()
        val initialModified = source.lastModified()
        val hash = sha256(source, checkCancelled)
        lateinit var analyzer: OfflineRestorationProfile.Analyzer
        val first = LocalPcmDecoder().decode(source, checkCancelled,
            { analyzer = OfflineRestorationProfile.Analyzer(it.sampleRate, it.channels) },
            { data, count -> analyzer.accept(data, count) },
            { progress(EnhancementStatus.ANALYZING, it * 40 / 100) })
        checkCancelled()
        val profile = analyzer.finish()
        check(profile.analyzedFrames == first.frames) { "Audio profile has an incomplete frame count" }
        check(profile.algorithmVersion == EnhancementRepository.ALGORITHM_VERSION) { "Unsupported processing profile" }
        if (profile.passthrough) {
            check(source.length() == initialSize && source.lastModified() == initialModified &&
                sha256(source, checkCancelled) == hash) { "Original recording changed during analysis" }
            checkCancelled()
            return Result(hash, 0, first.frames,
                "No clear correction found. The original recording was retained; no extra audio file was saved.", false)
        }
        PcmWavWriter(destination, first.format.sampleRate, first.format.channels, first.frames).use { writer ->
            val dsp = OfflineRestorationDsp(first.format.sampleRate, first.format.channels, profile)
            val sink = OfflineRestorationDsp.PcmSink { data, count -> checkCancelled(); writer.write(data, count) }
            val second = LocalPcmDecoder().decode(source, checkCancelled,
                { check(it.sampleRate == first.format.sampleRate && it.channels == first.format.channels) { "Original format changed" } },
                { data, count -> dsp.process(data, count, sink) },
                { progress(EnhancementStatus.PROCESSING, 40 + it * 59 / 100) })
            dsp.finish(sink)
            check(dsp.clippedSampleCount == 0L) { "Processed recording failed its clipping check" }
            check(dsp.inputFrames == dsp.outputFrames) { "Processed recording has an incomplete frame count" }
            check(first.frames == second.frames) { "Original frame count changed during processing" }
            check(source.length() == initialSize && source.lastModified() == initialModified &&
                sha256(source, checkCancelled) == hash) { "Original recording changed during processing" }
            checkCancelled()
            val bytes = writer.finish(first.frames)
            return Result(hash, bytes, first.frames, profile.summary, true)
        }
    }

    private fun sha256(file: File, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val count = input.read(bytes)
                if (count == -1) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
