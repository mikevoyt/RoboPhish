package com.bayapps.android.robophish.playback

import android.content.res.Resources
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.bayapps.android.robophish.utils.QueueHelper
import com.bayapps.android.robophish.utils.loadLargeAndSmallImage
import com.squareup.picasso.Picasso
import timber.log.Timber
import java.util.Collections

/**
 * Simple data provider for queues. Keeps track of a current queue and a current index.
 */
class QueueManager(
    private val musicProvider: MusicProvider,
    private val resources: Resources,
    private val picasso: Picasso,
    private val listener: MetadataUpdateListener
) {
    private var playingQueue: MutableList<MediaSessionCompat.QueueItem> =
        Collections.synchronizedList(mutableListOf())
    private var currentIndex: Int = 0

    fun isSameBrowsingCategory(mediaId: String): Boolean {
        val newBrowseHierarchy = MediaIDHelper.getHierarchy(mediaId)
        val current = currentMusic ?: return false
        val currentBrowseHierarchy =
            MediaIDHelper.getHierarchy(current.description.mediaId ?: return false)
        return newBrowseHierarchy.contentEquals(currentBrowseHierarchy)
    }

    private fun setCurrentQueueIndex(index: Int) {
        if (index >= 0 && index < playingQueue.size) {
            currentIndex = index
            listener.onCurrentQueueIndexUpdated(currentIndex)
        }
    }

    fun setCurrentQueueItem(queueId: Long): Boolean {
        val index = QueueHelper.getMusicIndexOnQueue(playingQueue, queueId)
        setCurrentQueueIndex(index)
        return index >= 0
    }

    fun setCurrentQueueItem(mediaId: String): Boolean {
        val index = QueueHelper.getMusicIndexOnQueue(playingQueue, mediaId)
        setCurrentQueueIndex(index)
        return index >= 0
    }

    fun skipQueuePosition(amount: Int): Boolean {
        var index = currentIndex + amount
        index = if (index < 0) {
            0
        } else {
            index % playingQueue.size
        }
        if (!QueueHelper.isIndexPlayable(index, playingQueue)) {
            Timber.e(
                "Cannot increment queue index by %s . Current=%s queue length=%s",
                amount,
                currentIndex,
                playingQueue.size
            )
            return false
        }
        currentIndex = index
        return true
    }

    fun setQueueFromSearch(query: String, extras: Bundle): Boolean {
        val queue = QueueHelper.getPlayingQueueFromSearch(query, extras)
        setCurrentQueue(resources.getString(R.string.search_queue_title), queue)
        return !queue.isNullOrEmpty()
    }

    fun setRandomQueue() {
        setCurrentQueue(
            resources.getString(R.string.random_queue_title),
            QueueHelper.getRandomQueue()
        )
    }

    fun setQueueFromMusic(mediaId: String) {
        Timber.d("setQueueFromMusic %s", mediaId)
        var canReuseQueue = false
        if (isSameBrowsingCategory(mediaId)) {
            canReuseQueue = setCurrentQueueItem(mediaId)
        }
        if (!canReuseQueue) {
            val queueTitle = resources.getString(
                R.string.browse_musics_by_genre_subtitle,
                MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId)
            )
            setCurrentQueue(queueTitle, QueueHelper.getPlayingQueue(mediaId, musicProvider), mediaId)
        }
        updateMetadata()
    }

    val currentMusic: MediaSessionCompat.QueueItem?
        get() = if (QueueHelper.isIndexPlayable(currentIndex, playingQueue)) {
            playingQueue[currentIndex]
        } else {
            null
        }

    fun getCurrentQueueSize(): Int = playingQueue.size

    private fun setCurrentQueue(title: String, newQueue: List<MediaSessionCompat.QueueItem>?) {
        setCurrentQueue(title, newQueue, null)
    }

    private fun setCurrentQueue(
        title: String,
        newQueue: List<MediaSessionCompat.QueueItem>?,
        initialMediaId: String?
    ) {
        playingQueue = (newQueue ?: emptyList()).toMutableList()
        val index = if (initialMediaId != null) {
            QueueHelper.getMusicIndexOnQueue(playingQueue, initialMediaId)
        } else {
            0
        }
        currentIndex = maxOf(index, 0)
        listener.onQueueUpdated(title, newQueue ?: emptyList())
    }

    fun getDuration(): Long {
        val currentMusic = currentMusic ?: return -1
        val musicId = MediaIDHelper.extractMusicIDFromMediaID(
            currentMusic.description.mediaId ?: return -1
        )
        val metadata = musicProvider.getMusic(musicId)
            ?: throw IllegalArgumentException("Invalid musicId $musicId")
        return metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
    }

    fun updateMetadata() {
        val currentMusic = currentMusic ?: run {
            listener.onMetadataRetrieveError()
            return
        }
        val musicId = MediaIDHelper.extractMusicIDFromMediaID(
            currentMusic.description.mediaId ?: return
        )
        val metadata = musicProvider.getMusic(musicId)
            ?: throw IllegalArgumentException("Invalid musicId $musicId")
        listener.onMetadataChanged(metadata)

        val description = metadata.description
        if (description.iconBitmap == null && description.iconUri != null) {
            val albumUri = description.iconUri.toString()
            picasso.loadLargeAndSmallImage(albumUri) { result ->
                musicProvider.updateMusicArt(musicId, result.image, result.icon)
                val currentPlayingId = MediaIDHelper.extractMusicIDFromMediaID(
                    currentMusic.description.mediaId ?: return@loadLargeAndSmallImage
                )
                val updated = musicProvider.getMusic(currentPlayingId)
                if (musicId == currentPlayingId && updated != null) {
                    listener.onMetadataChanged(updated)
                }
            }
        }
    }

    interface MetadataUpdateListener {
        fun onMetadataChanged(metadata: MediaMetadataCompat)
        fun onMetadataRetrieveError()
        fun onCurrentQueueIndexUpdated(queueIndex: Int)
        fun onQueueUpdated(title: String, newQueue: List<MediaSessionCompat.QueueItem>)
    }
}
