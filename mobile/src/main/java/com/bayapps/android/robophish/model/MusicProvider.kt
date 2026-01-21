package com.bayapps.android.robophish.model

import android.content.Context
import android.support.v4.media.MediaMetadataCompat
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
        val items = tracks.map { track -> track.toTrackMediaItem(showId) }
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

    private fun List<MediaMetadataCompat>.toMediaItemListOfShows(): List<MediaItem> = map { show ->
        val showId = show.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        val venue = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
        val location = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
        val date = show.getString(MediaMetadataCompat.METADATA_KEY_DATE)

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

    private fun MediaMetadataCompat.toTrackMediaItem(showId: String): MediaItem {
        val trackId = getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        val title = getString(MediaMetadataCompat.METADATA_KEY_TITLE)
        val durationMs = getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        val artist = getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
        val album = getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
        val description = getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION)
        val artUri = getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)
            ?: getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
            ?: getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
        val source = getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE)

        val hierarchyAwareMediaId = MediaIDHelper.createMediaID(
            trackId,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(formatDuration(durationMs))
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDescription(description)
            .setArtworkUri(artUri?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItem.Builder()
            .setMediaId(hierarchyAwareMediaId)
            .setUri(source)
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
