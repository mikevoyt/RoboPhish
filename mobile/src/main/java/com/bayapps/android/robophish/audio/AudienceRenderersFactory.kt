package com.bayapps.android.robophish.audio

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

class AudienceRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                                enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(AudienceAudioProcessor(AudienceAudioSettings.get(context))))
            .build()
}
