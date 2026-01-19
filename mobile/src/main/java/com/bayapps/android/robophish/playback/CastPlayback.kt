package com.bayapps.android.robophish.playback

import android.net.Uri
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.TextUtils
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.model.MusicProviderSource
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * An implementation of Playback that talks to Cast.
 */
class CastPlayback(
    private val musicProvider: MusicProvider,
    private val castSession: CastSession
) : Playback {

    private var state: Int = PlaybackStateCompat.STATE_NONE
    private var callback: Playback.Callback? = null
    @Volatile private var currentPosition: Int = 0
    @Volatile private var currentMediaId: String? = null

    private val remoteMediaClientListener = object : RemoteMediaClient.Listener {
        override fun onMetadataUpdated() {
            Timber.d("onRemoteMediaPlayerMetadataUpdated")
            setMetadataFromRemote()
        }

        override fun onStatusUpdated() {
            Timber.d("onRemoteMediaPlayerStatusUpdated")
            updatePlaybackState()
        }

        override fun onAdBreakStatusUpdated() {
        }

        override fun onPreloadStatusUpdated() {
        }

        override fun onQueueStatusUpdated() {
        }

        override fun onSendingRemoteMediaRequest() {
        }
    }

    override fun supportsGapless(): Boolean = false

    override fun start() {
        getRemoteMediaClient()?.addListener(remoteMediaClientListener)
    }

    override fun stop(notifyListeners: Boolean) {
        getRemoteMediaClient()?.removeListener(remoteMediaClientListener)
        state = PlaybackStateCompat.STATE_STOPPED
        if (notifyListeners) {
            callback?.onPlaybackStatusChanged(state)
        }
    }

    override fun setState(state: Int) {
        this.state = state
    }

    override fun getState(): Int = state

    override fun isConnected(): Boolean = castSession.isConnected

    override fun isPlaying(): Boolean {
        val client = getRemoteMediaClient()
        return client != null && client.isPlaying
    }

    override fun getCurrentStreamPosition(): Int {
        val client = getRemoteMediaClient()
        if (client == null || !castSession.isConnected) {
            return currentPosition
        }
        return client.approximateStreamPosition.toInt()
    }

    override fun setCurrentStreamPosition(pos: Int) {
        currentPosition = pos
    }

    override fun updateLastKnownStreamPosition() {
        currentPosition = getCurrentStreamPosition()
    }

    override fun playNext(item: MediaSessionCompat.QueueItem): Boolean = false

    override fun play(item: MediaSessionCompat.QueueItem) {
        try {
            loadMedia(item.description.mediaId, true)
            state = PlaybackStateCompat.STATE_BUFFERING
            callback?.onPlaybackStatusChanged(state)
        } catch (e: JSONException) {
            Timber.e(e, "Exception loading media")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Exception loading media")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalStateException) {
            Timber.e(e, "Exception loading media")
            callback?.onError(e.message ?: "Playback error")
        }
    }

    override fun pause() {
        try {
            val client = getRemoteMediaClient()
            if (client != null && client.hasMediaSession()) {
                client.pause()
                currentPosition = client.approximateStreamPosition.toInt()
            } else {
                loadMedia(currentMediaId, false)
            }
        } catch (e: JSONException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalStateException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        }
    }

    override fun seekTo(position: Int) {
        if (currentMediaId == null) {
            callback?.onError("seekTo cannot be calling in the absence of mediaId.")
            return
        }
        try {
            val client = getRemoteMediaClient()
            if (client != null && client.hasMediaSession()) {
                client.seek(position.toLong())
                currentPosition = position
            } else {
                currentPosition = position
                loadMedia(currentMediaId, false)
            }
        } catch (e: JSONException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        } catch (e: IllegalStateException) {
            Timber.e(e, "Exception pausing cast playback")
            callback?.onError(e.message ?: "Playback error")
        }
    }

    override fun setCurrentMediaId(mediaId: String?) {
        currentMediaId = mediaId
    }

    override fun getCurrentMediaId(): String? = currentMediaId

    override fun setCallback(callback: Playback.Callback?) {
        this.callback = callback
    }

    private fun loadMedia(mediaId: String?, autoPlay: Boolean) {
        val safeMediaId = mediaId ?: throw IllegalArgumentException("Invalid mediaId null")
        val musicId = MediaIDHelper.extractMusicIDFromMediaID(safeMediaId)
        val track = musicProvider.getMusic(musicId)
            ?: throw IllegalArgumentException("Invalid mediaId $mediaId")
        if (!TextUtils.equals(mediaId, currentMediaId)) {
            currentMediaId = mediaId
            currentPosition = 0
        }
        val customData = JSONObject().apply { put(ITEM_ID, mediaId) }
        val media = toCastMediaMetadata(track, customData)
        val client = getRemoteMediaClient() ?: throw IllegalStateException("No Cast session available")
        client.load(media, autoPlay, currentPosition.toLong(), customData)
    }

    private fun toCastMediaMetadata(
        track: MediaMetadataCompat,
        customData: JSONObject
    ): MediaInfo {
        val mediaMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, track.description.title?.toString() ?: "")
            putString(MediaMetadata.KEY_SUBTITLE, track.description.subtitle?.toString() ?: "")
            putString(
                MediaMetadata.KEY_ALBUM_ARTIST,
                track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST)
            )
            putString(
                MediaMetadata.KEY_ALBUM_TITLE,
                track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
            )
        }
        val image = WebImage(
            Uri.Builder().encodedPath(
                track.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
            ).build()
        )
        mediaMetadata.addImage(image)
        mediaMetadata.addImage(image)

        return MediaInfo.Builder(
            track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE)
        )
            .setContentType(MIME_TYPE_AUDIO_MPEG)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(mediaMetadata)
            .setCustomData(customData)
            .build()
    }

    private fun setMetadataFromRemote() {
        try {
            val client = getRemoteMediaClient()
            val mediaInfo = client?.mediaInfo ?: return
            val customData = mediaInfo.customData
            if (customData != null && customData.has(ITEM_ID)) {
                val remoteMediaId = customData.getString(ITEM_ID)
                if (!TextUtils.equals(currentMediaId, remoteMediaId)) {
                    currentMediaId = remoteMediaId
                    callback?.setCurrentMediaId(remoteMediaId)
                    updateLastKnownStreamPosition()
                }
            }
        } catch (e: JSONException) {
            Timber.e(e, "Exception processing update metadata")
        }
    }

    private fun updatePlaybackState() {
        val client = getRemoteMediaClient() ?: return
        val status = client.mediaStatus
        val playerState = status?.playerState ?: MediaStatus.PLAYER_STATE_UNKNOWN
        val idleReason = status?.idleReason ?: MediaStatus.IDLE_REASON_NONE
        Timber.d("onRemoteMediaPlayerStatusUpdated %s", playerState)

        when (playerState) {
            MediaStatus.PLAYER_STATE_IDLE -> {
                if (idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    callback?.onCompletion()
                }
            }
            MediaStatus.PLAYER_STATE_BUFFERING -> {
                state = PlaybackStateCompat.STATE_BUFFERING
                callback?.onPlaybackStatusChanged(state)
            }
            MediaStatus.PLAYER_STATE_PLAYING -> {
                state = PlaybackStateCompat.STATE_PLAYING
                setMetadataFromRemote()
                callback?.onPlaybackStatusChanged(state)
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                state = PlaybackStateCompat.STATE_PAUSED
                setMetadataFromRemote()
                callback?.onPlaybackStatusChanged(state)
            }
            else -> Timber.d("State default : %s", playerState)
        }
    }

    private fun getRemoteMediaClient(): RemoteMediaClient? {
        return castSession.remoteMediaClient
    }

    companion object {
        private const val MIME_TYPE_AUDIO_MPEG = "audio/mpeg"
        private const val ITEM_ID = "itemId"
    }
}
