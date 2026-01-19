package com.bayapps.android.robophish.utils

import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import com.bayapps.android.robophish.VoiceSearchParams
import com.bayapps.android.robophish.model.MusicProvider
import timber.log.Timber

/**
 * Utility class to help on queue related tasks.
 */
object QueueHelper {
    private const val RANDOM_QUEUE_SIZE = 10

    @JvmStatic
    fun getPlayingQueue(
        mediaId: String,
        musicProvider: MusicProvider
    ): List<MediaSessionCompat.QueueItem>? {
        val hierarchy = MediaIDHelper.getHierarchy(mediaId)
        if (hierarchy.size != 2) {
            Timber.e("Could not build a playing queue for this mediaId: %s", mediaId)
            return null
        }

        val categoryType = hierarchy[0]
        val categoryValue = hierarchy[1]
        Timber.d("Creating playing queue for %s, %s", categoryType, categoryValue)

        val tracks: Iterable<MediaMetadataCompat> = when (categoryType) {
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW -> musicProvider.getCachedTracksForShow(categoryValue)
                ?: return null
            else -> {
                Timber.e("Unrecognized category type: %s for media %s", categoryType, mediaId)
                return null
            }
        }

        return convertToQueue(tracks, hierarchy[0], hierarchy[1])
    }

    @JvmStatic
    fun getPlayingQueueFromSearch(
        query: String,
        queryParams: Bundle,
        musicProvider: MusicProvider
    ): List<MediaSessionCompat.QueueItem>? {
        Timber.d("Creating playing queue for musics from search: %s params=%s", query, queryParams)
        val params = VoiceSearchParams(query, queryParams)
        Timber.d("VoiceSearchParams: %s", params)

        if (params.isAny) {
            return getRandomQueue(musicProvider)
        }

        return convertToQueue(null, MediaIDHelper.MEDIA_ID_MUSICS_BY_SEARCH, query)
    }

    @JvmStatic
    fun getMusicIndexOnQueue(
        queue: Iterable<MediaSessionCompat.QueueItem>?,
        mediaId: String
    ): Int {
        if (queue == null) return -1
        var index = 0
        for (item in queue) {
            if (mediaId == item.description.mediaId) {
                return index
            }
            index++
        }
        return -1
    }

    @JvmStatic
    fun getMusicIndexOnQueue(queue: Iterable<MediaSessionCompat.QueueItem>?, queueId: Long): Int {
        if (queue == null) return -1
        var index = 0
        for (item in queue) {
            if (queueId == item.queueId) {
                return index
            }
            index++
        }
        return -1
    }

    private fun convertToQueue(
        tracks: Iterable<MediaMetadataCompat>?,
        vararg categories: String
    ): List<MediaSessionCompat.QueueItem>? {
        if (tracks == null) {
            return null
        }
        val queue = ArrayList<MediaSessionCompat.QueueItem>()
        var count = 0L
        for (track in tracks) {
            val hierarchyAwareMediaID =
                MediaIDHelper.createMediaID(track.description.mediaId, *categories)

            val trackCopy = MediaMetadataCompat.Builder(track)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .build()

            val item = MediaSessionCompat.QueueItem(trackCopy.description, count++)
            queue.add(item)
        }
        return queue
    }

    /**
     * Create a random queue with at most [RANDOM_QUEUE_SIZE] elements.
     */
    @JvmStatic
    fun getRandomQueue(musicProvider: MusicProvider): List<MediaSessionCompat.QueueItem> {
        val result = ArrayList<MediaMetadataCompat>(RANDOM_QUEUE_SIZE)
        return convertToQueue(result, MediaIDHelper.MEDIA_ID_MUSICS_BY_SEARCH, "random") ?: emptyList()
    }

    @JvmStatic
    fun isIndexPlayable(index: Int, queue: List<MediaSessionCompat.QueueItem>?): Boolean {
        return queue != null && index >= 0 && index < queue.size
    }
}
