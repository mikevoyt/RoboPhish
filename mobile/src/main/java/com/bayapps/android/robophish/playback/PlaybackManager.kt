package com.bayapps.android.robophish.playback

import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.MediaIDHelper
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Manage the interactions among the container service, the queue manager and the actual playback.
 */
class PlaybackManager(
    private val serviceCallback: PlaybackServiceCallback,
    private val resources: Resources,
    private val musicProvider: MusicProvider,
    private val queueManager: QueueManager,
    playback: Playback
) : Playback.Callback {

    val mediaSessionCallback: MediaSessionCompat.Callback = MediaSessionCallback()
    private val handler = Handler(Looper.getMainLooper())
    private val executorService = Executors.newSingleThreadScheduledExecutor()
    private var scheduleFuture: ScheduledFuture<*>? = null
    private var gaplessQueued = false

    var playback: Playback = playback
        private set

    init {
        this.playback.setCallback(this)
        scheduleFuture = executorService.scheduleAtFixedRate(
            { handler.post { monitorPosition() } },
            PROGRESS_UPDATE_INITIAL_INTERVAL,
            PROGRESS_UPDATE_INTERNAL,
            TimeUnit.MILLISECONDS
        )
    }

    private fun monitorPosition() {
        if (!playback.supportsGapless() || !playback.isPlaying() || gaplessQueued) {
            return
        }
        val currentPosition = playback.getCurrentStreamPosition() / 1000
        val duration = queueManager.getDuration() / 1000
        val delta = duration - currentPosition
        Timber.d("delta: %s", delta)

        if (duration - currentPosition == QUEUE_NEXT_TRACK_TIME.toLong()) {
            if (queueManager.skipQueuePosition(1)) {
                val currentMusic = queueManager.currentMusic
                if (currentMusic != null) {
                    Timber.d("Queing up next track : %s", currentMusic.description.title)
                    playback.playNext(currentMusic)
                    gaplessQueued = true
                }
            } else {
                handleStopRequest("Cannot skip")
            }
        }
    }

    /**
     * Handle a request to play music
     */
    fun handlePlayRequest() {
        gaplessQueued = false
        Timber.d("handlePlayRequest: mState=%s", playback.getState())
        val currentMusic = queueManager.currentMusic
        if (currentMusic != null) {
            serviceCallback.onPlaybackStart()
            playback.play(currentMusic)
        }
    }

    /**
     * Handle a request to pause music
     */
    fun handlePauseRequest() {
        Timber.d("handlePauseRequest: mState=%s", playback.getState())
        if (playback.isPlaying()) {
            playback.pause()
            serviceCallback.onPlaybackStop()
        }
    }

    /**
     * Handle a request to stop music
     */
    fun handleStopRequest(withError: String?) {
        Timber.d("handleStopRequest: mState=%s error=%s", playback.getState(), withError)
        playback.stop(true)
        serviceCallback.onPlaybackStop()
        updatePlaybackState(withError)
    }

    /**
     * Update the current media player state, optionally showing an error message.
     */
    fun updatePlaybackState(error: String?) {
        Timber.d("updatePlaybackState, playback state=%s", playback.getState())
        var position = PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        if (playback.isConnected()) {
            position = playback.getCurrentStreamPosition().toLong()
        }

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(getAvailableActions())

        setCustomAction(stateBuilder)
        var state = playback.getState()

        if (error != null) {
            stateBuilder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_UNKNOWN_ERROR, error)
            state = PlaybackStateCompat.STATE_ERROR
        }
        stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime())

        val currentMusic = queueManager.currentMusic
        if (currentMusic != null) {
            stateBuilder.setActiveQueueItemId(currentMusic.queueId)
        }

        serviceCallback.onPlaybackStateUpdated(stateBuilder.build())

        if (state == PlaybackStateCompat.STATE_PLAYING ||
            state == PlaybackStateCompat.STATE_PAUSED
        ) {
            serviceCallback.onNotificationRequired()
        }
    }

    private fun setCustomAction(stateBuilder: PlaybackStateCompat.Builder) {
        val currentMusic = queueManager.currentMusic ?: return
        val mediaId = currentMusic.description.mediaId ?: return
        val musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId)
        val favoriteIcon = if (musicProvider.isFavorite(musicId)) {
            R.drawable.ic_star_on
        } else {
            R.drawable.ic_star_off
        }
        Timber.d(
            "updatePlaybackState, setting Favorite custom action of music %s current favorite=%s",
            musicId,
            musicProvider.isFavorite(musicId)
        )
        val customActionExtras = Bundle()
        stateBuilder.addCustomAction(
            PlaybackStateCompat.CustomAction.Builder(
                CUSTOM_ACTION_THUMBS_UP,
                resources.getString(R.string.favorite),
                favoriteIcon
            )
                .setExtras(customActionExtras)
                .build()
        )
    }

    private fun getAvailableActions(): Long {
        var actions =
            PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        if (playback.isPlaying()) {
            actions = actions or PlaybackStateCompat.ACTION_PAUSE
        }
        return actions
    }

    override fun onCompletion() {
        if (gaplessQueued) {
            serviceCallback.onPlaybackStart()
            queueManager.updateMetadata()
            gaplessQueued = false
        } else if (queueManager.skipQueuePosition(1)) {
            handlePlayRequest()
            queueManager.updateMetadata()
        } else {
            handleStopRequest(null)
        }
    }

    override fun onPlaybackStatusChanged(state: Int) {
        updatePlaybackState(null)
    }

    override fun onError(error: String) {
        updatePlaybackState(error)
    }

    override fun setCurrentMediaId(mediaId: String) {
        Timber.d("setCurrentMediaId %s", mediaId)
        queueManager.setQueueFromMusic(mediaId)
    }

    /**
     * Switch to a different Playback instance, maintaining all playback state, if possible.
     */
    fun switchToPlayback(newPlayback: Playback, resumePlaying: Boolean) {
        val oldState = playback.getState()
        val pos = playback.getCurrentStreamPosition()
        val currentMediaId = playback.getCurrentMediaId()
        playback.stop(false)
        newPlayback.setCallback(this)
        newPlayback.setCurrentStreamPosition(if (pos < 0) 0 else pos)
        newPlayback.setCurrentMediaId(currentMediaId)
        newPlayback.start()
        playback = newPlayback
        when (oldState) {
            PlaybackStateCompat.STATE_BUFFERING,
            PlaybackStateCompat.STATE_CONNECTING,
            PlaybackStateCompat.STATE_PAUSED -> playback.pause()
            PlaybackStateCompat.STATE_PLAYING -> {
                val currentMusic = queueManager.currentMusic
                if (resumePlaying && currentMusic != null) {
                    playback.play(currentMusic)
                } else if (!resumePlaying) {
                    playback.pause()
                } else {
                    playback.stop(true)
                }
            }
            PlaybackStateCompat.STATE_NONE -> Unit
            else -> Timber.d("Default called. Old state is %s", oldState)
        }
    }

    private inner class MediaSessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            Timber.d("play")
            if (queueManager.currentMusic == null) {
                queueManager.setRandomQueue()
            }
            handlePlayRequest()
        }

        override fun onSkipToQueueItem(queueId: Long) {
            Timber.d("OnSkipToQueueItem: %s", queueId)
            queueManager.setCurrentQueueItem(queueId)
            handlePlayRequest()
            queueManager.updateMetadata()
        }

        override fun onSeekTo(position: Long) {
            Timber.d("onSeekTo: %s", position)
            playback.seekTo(position.toInt())
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Timber.d("playFromMediaId mediaId: %s extras=%s", mediaId, extras)
            if (mediaId == null) return
            queueManager.setQueueFromMusic(mediaId)
            handlePlayRequest()
        }

        override fun onPause() {
            Timber.d("pause. current state=%s", playback.getState())
            handlePauseRequest()
        }

        override fun onStop() {
            Timber.d("stop. current state=%s", playback.getState())
            handleStopRequest(null)
        }

        override fun onSkipToNext() {
            Timber.d("skipToNext")
            if (queueManager.skipQueuePosition(1)) {
                handlePlayRequest()
            } else {
                handleStopRequest("Cannot skip")
            }
            queueManager.updateMetadata()
        }

        override fun onSkipToPrevious() {
            if (queueManager.skipQueuePosition(-1)) {
                handlePlayRequest()
            } else {
                handleStopRequest("Cannot skip")
            }
            queueManager.updateMetadata()
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            if (CUSTOM_ACTION_THUMBS_UP == action) {
                Timber.i("onCustomAction: favorite for current track")
                val currentMusic = queueManager.currentMusic
                val mediaId = currentMusic?.description?.mediaId
                if (mediaId != null) {
                    val musicId = MediaIDHelper.extractMusicIDFromMediaID(mediaId)
                    if (musicId != null) {
                        musicProvider.setFavorite(musicId, !musicProvider.isFavorite(musicId))
                    }
                }
                updatePlaybackState(null)
            } else {
                Timber.e("Unsupported action: %s", action)
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            Timber.d("playFromSearch  query=%s extras=%s", query, extras)
            playback.setState(PlaybackStateCompat.STATE_CONNECTING)
            val successSearch = queueManager.setQueueFromSearch(query ?: "", extras ?: Bundle())
            if (successSearch) {
                handlePlayRequest()
                queueManager.updateMetadata()
            } else {
                updatePlaybackState("Could not find music")
            }
        }
    }

    interface PlaybackServiceCallback {
        fun onPlaybackStart()
        fun onNotificationRequired()
        fun onPlaybackStop()
        fun onPlaybackStateUpdated(newState: PlaybackStateCompat)
    }

    companion object {
        private const val CUSTOM_ACTION_THUMBS_UP = "com.example.android.uamp.THUMBS_UP"
        private const val QUEUE_NEXT_TRACK_TIME = 10
        private const val PROGRESS_UPDATE_INTERNAL = 1000L
        private const val PROGRESS_UPDATE_INITIAL_INTERVAL = 100L
    }
}
