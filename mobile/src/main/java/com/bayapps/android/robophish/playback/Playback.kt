package com.bayapps.android.robophish.playback

import android.support.v4.media.session.MediaSessionCompat

/**
 * Interface representing either Local or Remote Playback.
 */
interface Playback {
    /**
     * Start/setup the playback.
     */
    fun start()

    /**
     * Stop the playback. All resources can be de-allocated by implementations here.
     */
    fun stop(notifyListeners: Boolean)

    /**
     * Set the latest playback state as determined by the caller.
     */
    fun setState(state: Int)

    /**
     * Get the current playback state.
     */
    fun getState(): Int

    /**
     * @return boolean that indicates that this is ready to be used.
     */
    fun isConnected(): Boolean

    /**
     * @return boolean indicating whether the player is playing or is supposed to be playing.
     */
    fun isPlaying(): Boolean

    /**
     * @return pos if currently playing an item
     */
    fun getCurrentStreamPosition(): Int

    /**
     * Set the current position.
     */
    fun setCurrentStreamPosition(pos: Int)

    /**
     * Query the underlying stream and update the internal last known stream position.
     */
    fun updateLastKnownStreamPosition()

    /**
     * @param item to play
     */
    fun play(item: MediaSessionCompat.QueueItem)

    /**
     * @param item to play after the currently playing item, using gapless playback
     */
    fun playNext(item: MediaSessionCompat.QueueItem): Boolean

    /**
     * Pause the current playing item
     */
    fun pause()

    /**
     * Seek to the given position
     */
    fun seekTo(position: Int)

    /**
     * Set the current mediaId.
     */
    fun setCurrentMediaId(mediaId: String?)

    /**
     * @return the current media Id being processed in any state or null.
     */
    fun getCurrentMediaId(): String?

    fun setCallback(callback: Callback?)

    fun supportsGapless(): Boolean

    interface Callback {
        fun onCompletion()
        fun onPlaybackStatusChanged(state: Int)
        fun onError(error: String)
        fun setCurrentMediaId(mediaId: String)
    }
}
