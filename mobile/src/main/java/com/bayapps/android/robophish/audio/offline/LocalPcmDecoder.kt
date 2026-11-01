package com.bayapps.android.robophish.audio.offline

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/** Full-file, bounded-memory decoder. Only local files enter this path. */
internal class LocalPcmDecoder {
    data class Format(val sampleRate: Int, val channels: Int, val durationUs: Long)
    data class Result(val format: Format, val frames: Long)

    fun decode(source: File, checkCancelled: () -> Unit, onFormat: (Format) -> Unit,
               onPcm: (ShortArray, Int) -> Unit, onProgress: (Int) -> Unit): Result {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            check(source.isFile && source.canRead() && source.length() > 0) { "Original recording is unavailable" }
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("Recording contains no decodable audio")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val format = Format(input.getInteger(MediaFormat.KEY_SAMPLE_RATE), input.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else 0)
            require(format.sampleRate in 22050..96000 && format.channels in 1..2) {
                "Offline processing supports mono and stereo recordings at 22050–96000 Hz"
            }
            val delay = input.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
            val padding = input.intOrZero(MediaFormat.KEY_ENCODER_PADDING)
            require(delay in 0..format.sampleRate && padding in 0..format.sampleRate) { "Invalid recording trim metadata" }
            val trimmer = PcmGaplessTrimmer(format.channels, delay, padding)
            // Own the trimming explicitly; codecs that implement these keys receive zero trim.
            input.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0)
            input.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0)
            input.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(requireNotNull(input.getString(MediaFormat.KEY_MIME)))
            codec.configure(input, null, null, 0); codec.start(); started = true
            onFormat(format)
            val info = MediaCodec.BufferInfo()
            var endedInput = false
            var endedOutput = false
            var samples = ShortArray(0)
            var lastActivity = SystemClock.elapsedRealtime()
            while (!endedOutput) {
                checkCancelled()
                if (!endedInput) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index)); buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            endedInput = true
                        } else {
                            require(size <= buffer.capacity()) { "Encoded audio frame exceeds codec capacity" }
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        require(output.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                            output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels) { "Audio format changed during decoding" }
                        require(!output.containsKey(MediaFormat.KEY_PCM_ENCODING) ||
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT) { "Decoder did not provide PCM16 audio" }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                require(info.size % (format.channels * 2) == 0 && info.size <= 4 * 1024 * 1024) { "Invalid PCM decoder frame" }
                                val output = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                output.position(info.offset); output.limit(info.offset + info.size)
                                val count = info.size / 2
                                if (samples.size < count) samples = ShortArray(count)
                                output.asShortBuffer().get(samples, 0, count)
                                trimmer.accept(samples, count) { data, n -> checkCancelled(); onPcm(data, n) }
                                if (format.durationUs > 0) onProgress((info.presentationTimeUs * 100 / format.durationUs).toInt().coerceIn(0, 99))
                            }
                            endedOutput = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { codec.releaseOutputBuffer(index, false) }
                        lastActivity = SystemClock.elapsedRealtime()
                    }
                }
                if (SystemClock.elapsedRealtime() - lastActivity > 30_000) throw IOException("Audio decoder stopped making progress")
            }
            val frames = trimmer.finish()
            // MP3 duration can be a bitrate estimate (notably VBR without a seek header).
            // Use it only for progress. Download byte validation and exact two-pass frame
            // counts guard completeness without rejecting valid tapes on an estimated duration.
            onProgress(100)
            return Result(format, frames)
        } finally {
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaFormat.intOrZero(key: String) = if (containsKey(key)) getInteger(key) else 0
}
