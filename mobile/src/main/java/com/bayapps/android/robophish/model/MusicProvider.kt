package com.bayapps.android.robophish.model

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.utils.MediaIDHelper
import robophish.model.YearData
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.TimeUnit

/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
class MusicProvider(
    private val context: Context,
    private val source: MusicProviderSource
) {
    private var cachedTracks: MutableMap<String, List<MediaItem>> =
        Collections.synchronizedMap(mutableMapOf())

    private val mediaItemsById: ConcurrentMap<String, MediaItem> = ConcurrentHashMap()

    suspend fun getTracksForShow(showId: String): List<MediaItem> {
        return buildTrackItems(showId)
    }

    fun getCachedTracksForShow(showId: String) = cachedTracks[showId]

    fun getTrackItem(trackId: String?): MediaItem? = mediaItemsById[trackId]

    suspend fun children(mediaId: String): List<MediaItem> {
        if (!MediaIDHelper.isBrowseable(mediaId)) return emptyList()

        if (MediaIDHelper.MEDIA_ID_ROOT == mediaId) {
            return source.years().toMediaItemList()
        }

        if (mediaId.startsWith(MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR)) {
            val year = MediaIDHelper.getHierarchy(mediaId)[1]
            return source.showsInYear(year).toMediaItemListOfShows()
        }

        if (mediaId.startsWith(MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW)) {
            val showId = MediaIDHelper.getHierarchy(mediaId)[1]
            return buildTrackItems(showId)
        }

        Timber.w("Skipping unmatched mediaId: %s", mediaId)
        return emptyList()
    }

    private suspend fun buildTrackItems(showId: String): List<MediaItem> {
        val tracks = source.tracksInShow(showId)
        val items = tracks.map { track -> track.toTrackMediaItem() }
        cachedTracks[showId] = items
        items.forEach { item ->
            mediaItemsById[item.mediaId] = item
            MediaIDHelper.extractMusicIDFromMediaID(item.mediaId)?.let { trackId ->
                mediaItemsById[trackId] = item
            }
        }
        return items
    }

    private fun List<YearData>.toMediaItemList(): List<MediaItem> = map { year ->
        val mediaId = MediaIDHelper.createMediaID(
            null,
            MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR,
            year.date
        )
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(year.date)
                    .setSubtitle("${year.show_count} shows")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun List<ShowData>.toMediaItemListOfShows(): List<MediaItem> = map { show ->
        val showId = show.id
        val venue = show.venue
        val location = show.location
        val date = show.date

        val mediaId = MediaIDHelper.createMediaID(
            null,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(venue)
                    .setSubtitle(context.getString(R.string.browse_musics_by_genre_subtitle, date))
                    .setDescription(location)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun TrackData.toTrackMediaItem(): MediaItem {
        val durationText = formatDuration(durationMs)

        val hierarchyAwareMediaId = MediaIDHelper.createMediaID(
            id,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(durationText)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDescription(showDate)
            .setArtworkUri(artUrl.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(hierarchyAwareMediaId)
            .setUri(sourceUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) -
            TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%d:%02d", minutes, seconds)
    }
}
