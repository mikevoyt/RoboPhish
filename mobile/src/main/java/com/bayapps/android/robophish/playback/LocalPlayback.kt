package com.bayapps.android.robophish.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.model.MusicProviderSource
import com.bayapps.android.robophish.utils.MediaIDHelper
import timber.log.Timber
import java.io.IOException

/**
 * A class that implements local media playback using MediaPlayer.
 */
class LocalPlayback(
    private val context: Context,
    private val musicProvider: MusicProvider
) : Playback,
    AudioManager.OnAudioFocusChangeListener,
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnSeekCompleteListener {

    private val wifiLock: WifiManager.WifiLock = (context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "uAmp_lock")
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var state = PlaybackStateCompat.STATE_NONE
    private var playOnFocusGain = false
    private var callback: Playback.Callback? = null
    private var audioNoisyReceiverRegistered = false
    @Volatile private var currentPosition = 0
    @Volatile private var currentMediaId: String? = null
    private var audioFocus = AUDIO_NO_FOCUS_NO_DUCK

    private var mediaPlayerA: MediaPlayer? = null
    private var mediaPlayerB: MediaPlayer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayersSwapping = false
    @Volatile private var nextMediaId: String? = null

    override fun supportsGapless(): Boolean = true

    private fun nextMediaPlayer(): MediaPlayer? {
        return if (mediaPlayer == mediaPlayerA) mediaPlayerB else mediaPlayerA
    }

    private val audioNoisyIntentFilter =
        IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)

    private val audioNoisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                Timber.d("Headphones disconnected.")
                if (isPlaying()) {
                    val pauseIntent = Intent(context, MusicService::class.java).apply {
                        action = MusicService.ACTION_CMD
                        putExtra(MusicService.CMD_NAME, MusicService.CMD_PAUSE)
                    }
                    this@LocalPlayback.context.startService(pauseIntent)
                }
            }
        }
    }

    override fun start() {
    }

    override fun stop(notifyListeners: Boolean) {
        state = PlaybackStateCompat.STATE_STOPPED
        if (notifyListeners) {
            callback?.onPlaybackStatusChanged(state)
        }
        currentPosition = getCurrentStreamPosition()
        giveUpAudioFocus()
        unregisterAudioNoisyReceiver()
        relaxResources(true)
    }

    override fun setState(state: Int) {
        this.state = state
    }

    override fun getState(): Int = state

    override fun isConnected(): Boolean = true

    override fun isPlaying(): Boolean {
        return playOnFocusGain || (mediaPlayer?.isPlaying == true)
    }

    override fun getCurrentStreamPosition(): Int {
        return mediaPlayer?.currentPosition ?: currentPosition
    }

    override fun updateLastKnownStreamPosition() {
        mediaPlayer?.let { currentPosition = it.currentPosition }
    }

    override fun playNext(item: MediaSessionCompat.QueueItem): Boolean {
        val nextPlayer = if (mediaPlayer == mediaPlayerA) mediaPlayerB else mediaPlayerA
        val mediaId = item.description.mediaId
        val mediaHasChanged = !TextUtils.equals(mediaId, currentMediaId)
        if (mediaHasChanged) {
            nextMediaId = mediaId
        }

        val source = try {
            val track = musicProvider.getMusic(
                MediaIDHelper.extractMusicIDFromMediaID(item.description.mediaId ?: "")
            ) ?: throw IllegalArgumentException("Invalid mediaId ${item.description.mediaId}")
            track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE)
        } catch (ex: IllegalArgumentException) {
            Timber.e(ex, "Exception playing song")
            callback?.onError(ex.message ?: "Playback error")
            return false
        }

        setAudioAttributes(nextPlayer)

        try {
            nextPlayer?.setDataSource(source)
        } catch (ex: IOException) {
            Timber.e(ex, "Exception playing song")
            callback?.onError(ex.message ?: "Playback error")
        }

        nextPlayer?.prepareAsync()
        mediaPlayersSwapping = true
        return true
    }

    override fun play(item: MediaSessionCompat.QueueItem) {
        if (mediaPlayersSwapping) {
            mediaPlayersSwapping = false
        }

        playOnFocusGain = true
        tryToGetAudioFocus()
        registerAudioNoisyReceiver()

        val mediaId = item.description.mediaId
        val mediaHasChanged = !TextUtils.equals(mediaId, currentMediaId)
        if (mediaHasChanged) {
            currentPosition = 0
            currentMediaId = mediaId
        }

        if (state == PlaybackStateCompat.STATE_PAUSED && !mediaHasChanged && mediaPlayer != null) {
            configMediaPlayerState()
            return
        }

        state = PlaybackStateCompat.STATE_STOPPED
        relaxResources(false)
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
            createMediaPlayerIfNeeded()
            state = PlaybackStateCompat.STATE_BUFFERING
            setAudioAttributes(mediaPlayer)
            mediaPlayer?.setDataSource(source)
            mediaPlayer?.prepareAsync()
            wifiLock.acquire()
            callback?.onPlaybackStatusChanged(state)
        } catch (ex: IOException) {
            Timber.e(ex, "Exception playing song")
            callback?.onError(ex.message ?: "Playback error")
        }
    }

    override fun pause() {
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                currentPosition = mediaPlayer?.currentPosition ?: currentPosition
            }
            relaxResources(false)
            giveUpAudioFocus()
        }
        state = PlaybackStateCompat.STATE_PAUSED
        callback?.onPlaybackStatusChanged(state)
        unregisterAudioNoisyReceiver()
    }

    override fun seekTo(position: Int) {
        Timber.d("seekTo called with %s", position)
        if (mediaPlayer == null) {
            currentPosition = position
            return
        }
        if (mediaPlayer?.isPlaying == true) {
            state = PlaybackStateCompat.STATE_BUFFERING
        }
        mediaPlayer?.seekTo(position)
        callback?.onPlaybackStatusChanged(state)
    }

    override fun setCallback(callback: Playback.Callback?) {
        this.callback = callback
    }

    override fun setCurrentStreamPosition(pos: Int) {
        currentPosition = pos
    }

    override fun setCurrentMediaId(mediaId: String?) {
        currentMediaId = mediaId
    }

    override fun getCurrentMediaId(): String? = currentMediaId

    private fun tryToGetAudioFocus() {
        Timber.d("tryToGetAudioFocus")
        if (audioFocus != AUDIO_FOCUSED) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (audioFocusRequest == null) {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setOnAudioFocusChangeListener(this)
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else {
                audioManager.requestAudioFocus(
                    this,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocus = AUDIO_FOCUSED
            }
        }
    }

    private fun giveUpAudioFocus() {
        Timber.d("giveUpAudioFocus")
        if (audioFocus == AUDIO_FOCUSED) {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = audioFocusRequest
                if (request != null) {
                    audioManager.abandonAudioFocusRequest(request)
                } else {
                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }
            } else {
                audioManager.abandonAudioFocus(this)
            }
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocus = AUDIO_NO_FOCUS_NO_DUCK
            }
        }
    }

    private fun configMediaPlayerState() {
        Timber.d("configMediaPlayerState. mAudioFocus=%s", audioFocus)
        if (audioFocus == AUDIO_NO_FOCUS_NO_DUCK) {
            if (state == PlaybackStateCompat.STATE_PLAYING) {
                pause()
            }
        } else {
            if (audioFocus == AUDIO_NO_FOCUS_CAN_DUCK) {
                mediaPlayer?.setVolume(VOLUME_DUCK, VOLUME_DUCK)
            } else {
                mediaPlayer?.setVolume(VOLUME_NORMAL, VOLUME_NORMAL)
            }
            if (playOnFocusGain) {
                if (mediaPlayer != null && mediaPlayer?.isPlaying == false) {
                    Timber.d(
                        "configMediaPlayerState startMediaPlayer. seeking to %s ",
                        currentPosition
                    )
                    if (currentPosition == mediaPlayer?.currentPosition) {
                        mediaPlayer?.start()
                        state = PlaybackStateCompat.STATE_PLAYING
                    } else {
                        mediaPlayer?.seekTo(currentPosition)
                        state = PlaybackStateCompat.STATE_BUFFERING
                    }
                }
                playOnFocusGain = false
            }
        }
        callback?.onPlaybackStatusChanged(state)
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Timber.d("onAudioFocusChange. focusChange=%s", focusChange)
        if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            audioFocus = AUDIO_FOCUSED
        } else if (
            focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            val canDuck = focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            audioFocus = if (canDuck) AUDIO_NO_FOCUS_CAN_DUCK else AUDIO_NO_FOCUS_NO_DUCK
            if (state == PlaybackStateCompat.STATE_PLAYING && !canDuck) {
                playOnFocusGain = true
            }
        } else {
            Timber.e("onAudioFocusChange: Ignoring unsupported focusChange: %s", focusChange)
        }
        configMediaPlayerState()
    }

    private fun setAudioAttributes(player: MediaPlayer?) {
        player ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } else {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        }
    }

    override fun onSeekComplete(mp: MediaPlayer) {
        Timber.d("onSeekComplete from MediaPlayer: %s", mp.currentPosition)
        currentPosition = mp.currentPosition
        if (state == PlaybackStateCompat.STATE_BUFFERING) {
            mediaPlayer?.start()
            state = PlaybackStateCompat.STATE_PLAYING
        }
        callback?.onPlaybackStatusChanged(state)
    }

    override fun onCompletion(player: MediaPlayer) {
        Timber.d("onCompletion from MediaPlayer")
        if (mediaPlayersSwapping) {
            currentPosition = 0
            currentMediaId = nextMediaId
            val old = mediaPlayer
            mediaPlayer = nextMediaPlayer()
            mediaPlayersSwapping = false
            old?.reset()
            callback?.onPlaybackStatusChanged(state)
        }
        callback?.onCompletion()
    }

    override fun onPrepared(player: MediaPlayer) {
        Timber.d("onPrepared from MediaPlayer")
        if (mediaPlayersSwapping) {
            mediaPlayer?.setNextMediaPlayer(nextMediaPlayer())
            return
        }
        configMediaPlayerState()
    }

    override fun onError(mp: MediaPlayer, what: Int, extra: Int): Boolean {
        Timber.e("Media player error: what=%s extra=%s", what, extra)
        callback?.onError("MediaPlayer error $what ($extra)")
        return true
    }

    private fun createMediaPlayerIfNeeded() {
        mediaPlayerA = createMediaPlayer(mediaPlayerA)
        mediaPlayerB = createMediaPlayer(mediaPlayerB)
        if (mediaPlayer == null) mediaPlayer = mediaPlayerA
    }

    private fun createMediaPlayer(player: MediaPlayer?): MediaPlayer {
        Timber.d("createMediaPlayerIfNeeded. needed? %s", player == null)
        val result = player ?: MediaPlayer().apply {
            setWakeMode(context.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener(this@LocalPlayback)
            setOnCompletionListener(this@LocalPlayback)
            setOnErrorListener(this@LocalPlayback)
            setOnSeekCompleteListener(this@LocalPlayback)
        }
        if (player != null) {
            result.reset()
        }
        return result
    }

    private fun relaxResources(releaseMediaPlayer: Boolean) {
        Timber.d("relaxResources. releaseMediaPlayer=%s", releaseMediaPlayer)
        if (releaseMediaPlayer) {
            mediaPlayer?.reset()
            mediaPlayer?.release()
            mediaPlayer = null
        }
        if (wifiLock.isHeld) {
            wifiLock.release()
        }
    }

    private fun registerAudioNoisyReceiver() {
        if (audioNoisyReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                audioNoisyReceiver,
                audioNoisyIntentFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(audioNoisyReceiver, audioNoisyIntentFilter)
        }
        audioNoisyReceiverRegistered = true
    }

    private fun unregisterAudioNoisyReceiver() {
        if (audioNoisyReceiverRegistered) {
            context.unregisterReceiver(audioNoisyReceiver)
            audioNoisyReceiverRegistered = false
        }
    }

    companion object {
        const val VOLUME_DUCK = 0.2f
        const val VOLUME_NORMAL = 1.0f

        private const val AUDIO_NO_FOCUS_NO_DUCK = 0
        private const val AUDIO_NO_FOCUS_CAN_DUCK = 1
        private const val AUDIO_FOCUSED = 2
    }
}
