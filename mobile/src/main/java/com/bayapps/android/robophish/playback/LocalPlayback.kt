package com.bayapps.android.robophish.playback

import android.content.Context
import android.net.wifi.WifiManager
import android.support.v4.media.session.MediaSessionCompat
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.model.MusicProviderSource
import com.bayapps.android.robophish.utils.MediaIDHelper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import timber.log.Timber
import java.io.IOException

/**
 * A class that implements local media playback using ExoPlayer.
 */
class LocalPlayback(
    private val context: Context,
    private val musicProvider: MusicProvider
) : Playback {

    private val wifiLock: WifiManager.WifiLock = (context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uAmp_lock")

    private var state = android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
    private var callback: Playback.Callback? = null
    @Volatile private var currentPosition = 0
    @Volatile private var currentMediaId: String? = null
    @Volatile private var pendingNextMediaId: String? = null
    private var player: ExoPlayer? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlaybackState(playbackState)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState(player?.playbackState ?: Player.STATE_IDLE)
        }

        override fun onPlayerError(error: PlaybackException) {
            Timber.e(error, "ExoPlayer error")
            callback?.onError("Playback error: ${error.errorCodeName}")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            currentPosition = player?.currentPosition?.toInt() ?: currentPosition
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (mediaItem != null) {
                currentMediaId = mediaItem.mediaId
                currentPosition = 0
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                pendingNextMediaId = null
                callback?.onPlaybackStatusChanged(
                    android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
                )
                callback?.onCompletion()
            }
        }
    }

    override fun supportsGapless(): Boolean = true

    override fun start() {
    }

    override fun stop(notifyListeners: Boolean) {
        state = android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
        if (notifyListeners) {
            callback?.onPlaybackStatusChanged(state)
        }
        currentPosition = getCurrentStreamPosition()
        pendingNextMediaId = null
        releasePlayer()
    }

    override fun setState(state: Int) {
        this.state = state
    }

    override fun getState(): Int = state

    override fun isConnected(): Boolean = player != null

    override fun isPlaying(): Boolean = player?.isPlaying == true

    override fun getCurrentStreamPosition(): Int {
        return player?.currentPosition?.toInt() ?: currentPosition
    }

    override fun updateLastKnownStreamPosition() {
        currentPosition = getCurrentStreamPosition()
    }

    override fun playNext(item: MediaSessionCompat.QueueItem): Boolean {
        createPlayerIfNeeded()
        val mediaId = item.description.mediaId
        if (mediaId == null || mediaId == pendingNextMediaId) {
            return false
        }
        val source = try {
            val track = musicProvider.getMusic(
                MediaIDHelper.extractMusicIDFromMediaID(mediaId)
            ) ?: throw IllegalArgumentException("Invalid mediaId $mediaId")
            track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE)
        } catch (ex: IllegalArgumentException) {
            Timber.e(ex, "Exception queueing next song")
            callback?.onError(ex.message ?: "Playback error")
            return false
        }
        val mediaItem = MediaItem.Builder()
            .setUri(source)
            .setMediaId(mediaId)
            .build()
        player?.addMediaItem(mediaItem)
        pendingNextMediaId = mediaId
        return true
    }

    override fun play(item: MediaSessionCompat.QueueItem) {
        createPlayerIfNeeded()
        val mediaId = item.description.mediaId
        val mediaHasChanged = mediaId != currentMediaId
        if (mediaHasChanged) {
            currentPosition = 0
            currentMediaId = mediaId
            pendingNextMediaId = null
        }

        val source = try {
            val track = musicProvider.getMusic(
                MediaIDHelper.extractMusicIDFromMediaID(item.description.mediaId ?: "")
            ) ?: throw IllegalArgumentException("Invalid mediaId ${item.description.mediaId}")
            track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE)
        } catch (ex: IllegalArgumentException) {
            Timber.e(ex, "Exception playing song")
            callback?.onError(ex.message ?: "Playback error")
            return
        }

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(source)
                .setMediaId(mediaId ?: "")
                .build()
            player?.setMediaItem(mediaItem, currentPosition.toLong())
            player?.prepare()
            player?.playWhenReady = true
            if (!wifiLock.isHeld) {
                wifiLock.acquire()
            }
            state = android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
            callback?.onPlaybackStatusChanged(state)
        } catch (ex: IOException) {
            Timber.e(ex, "Exception playing song")
            callback?.onError(ex.message ?: "Playback error")
            return
        }
    }

    override fun pause() {
        player?.playWhenReady = false
        state = android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
        callback?.onPlaybackStatusChanged(state)
        releaseWifiLock()
    }

    override fun seekTo(position: Int) {
        Timber.d("seekTo called with %s", position)
        currentPosition = position
        player?.seekTo(position.toLong())
    }

    override fun setCallback(callback: Playback.Callback?) {
        this.callback = callback
    }

    override fun setCurrentStreamPosition(pos: Int) {
        currentPosition = pos
        player?.seekTo(pos.toLong())
    }

    override fun setCurrentMediaId(mediaId: String?) {
        currentMediaId = mediaId
    }

    override fun getCurrentMediaId(): String? = currentMediaId

    private fun createPlayerIfNeeded() {
        if (player != null) return
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                60_000,
                1_500,
                5_000
            )
            .build()
        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                val attributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build()
                setAudioAttributes(attributes, true)
                setHandleAudioBecomingNoisy(true)
                addListener(playerListener)
            }
    }

    private fun updatePlaybackState(playbackState: Int) {
        val exoPlayer = player ?: return
        val newState = when (playbackState) {
            Player.STATE_BUFFERING -> android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY -> if (exoPlayer.playWhenReady) {
                android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING
            } else {
                android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED
            }
            Player.STATE_ENDED -> android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED
            Player.STATE_IDLE -> android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
            else -> android.support.v4.media.session.PlaybackStateCompat.STATE_NONE
        }
        if (state == newState) return
        state = newState
        callback?.onPlaybackStatusChanged(state)
        if (state == android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED) {
            callback?.onCompletion()
        }
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
        releaseWifiLock()
    }

    private fun releaseWifiLock() {
        if (wifiLock.isHeld) {
            wifiLock.release()
        }
    }
}
