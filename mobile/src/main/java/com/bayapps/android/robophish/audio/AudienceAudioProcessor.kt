package com.bayapps.android.robophish.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/** Retains a live pass-through stage so a toggle never rebuilds or restarts the player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudienceAudioProcessor(private val settings: AudienceAudioSettings) : BaseAudioProcessor() {
    private var engine: AudienceDsp? = null
    private var samples = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate in 8000..192000 && inputAudioFormat.channelCount in 1..8)
            inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush() {
        engine = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate in 8000..192000 && inputAudioFormat.channelCount in 1..8)
            AudienceDsp(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        val output = replaceOutputBuffer(bytes)
        val dsp = engine
        if (dsp == null) {
            output.put(inputBuffer)
        } else {
            val count = bytes / 2
            if (samples.size < count) samples = ShortArray(count)
            for (i in 0 until count) samples[i] = inputBuffer.short
            val current = settings.current
            dsp.process(samples, samples, count, current.enabled && !settings.compareOriginal,
                current.amount, current.mode)
            for (i in 0 until count) output.putShort(samples[i])
        }
        output.flip()
    }

    override fun onReset() {
        engine = null
        samples = ShortArray(0)
    }
}
