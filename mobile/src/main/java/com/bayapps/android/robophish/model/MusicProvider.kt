package com.bayapps.android.robophish.model

import android.content.Context
import android.os.Bundle
import com.bayapps.android.robophish.downloads.DownloadStore
import com.bayapps.android.robophish.downloads.DownloadRepository
import com.bayapps.android.robophish.downloads.DownloadShow
import com.bayapps.android.robophish.downloads.OfflineMedia
import com.bayapps.android.robophish.utils.NetworkHelper
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

    fun getTrackItem(trackId: String?): MediaItem? = trackId?.let { mediaItemsById[it] }

    suspend fun children(mediaId: String): List<MediaItem> {
        if (!MediaIDHelper.isBrowseable(mediaId)) return emptyList()

        if (MediaIDHelper.MEDIA_ID_ROOT == mediaId) {
            val downloads = MediaItem.Builder().setMediaId(MEDIA_ID_DOWNLOADS)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(context.getString(R.string.offline_downloads))
                    .setSubtitle(context.getString(R.string.offline_browse_subtitle))
                    .setIsBrowsable(true).setIsPlayable(false).build()).build()
            return listOf(downloads) + if (NetworkHelper.isOnline(context)) source.years().toMediaItemList() else emptyList()
        }
        if (mediaId == MEDIA_ID_DOWNLOADS) {
            return DownloadShow.group(DownloadRepository.get(context).reconcile()).map { show ->
                MediaItem.Builder().setMediaId(MediaIDHelper.createMediaID(null,
                    MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW, show.first.showId ?: "offline"))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(show.first.venue ?: show.first.album)
                        .setSubtitle(show.first.showDate).setIsBrowsable(true).setIsPlayable(false).build()).build()
            }
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
        val local = DownloadRepository.get(context).reconcile().filter { it.showId == showId && OfflineMedia.isPlayable(it) }
        val tracks = if (NetworkHelper.isOnline(context)) source.tracksInShow(showId) else emptyList()
        val items = if (tracks.isNotEmpty()) tracks.mapIndexed { index, track -> track.toTrackMediaItem(index + 1) }
            else DownloadShow.group(local).flatMap { it.tracks }.map(OfflineMedia::toMediaItem)
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

    private fun TrackData.toTrackMediaItem(trackNumber: Int): MediaItem {
        val durationText = formatDuration(durationMs)

        val hierarchyAwareMediaId = MediaIDHelper.createMediaID(
            id,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setTrackNumber(trackNumber)
            .setSubtitle(durationText)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setDescription(showDate)
            .setArtworkUri(artUrl.let { android.net.Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
        val extras = Bundle().apply {
            if (!set.isNullOrBlank()) putString(METADATA_SET, set)
            if (!setName.isNullOrBlank()) putString(METADATA_SET_NAME, setName)
            putString(METADATA_STREAM_URL, sourceUrl)
            putLong(METADATA_DURATION_MS, durationMs)
        }
        metadataBuilder.setExtras(extras)
        val metadata = metadataBuilder.build()

        return MediaItem.Builder()
            .setMediaId(hierarchyAwareMediaId)
            .setUri(sourceUrl)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(android.net.Uri.parse(sourceUrl))
                    .build()
            )
            .setMediaMetadata(metadata)
            .build()
    }

    private fun formatDuration(durationMs: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) -
            TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%d:%02d", minutes, seconds)
    }

    companion object {
        const val MEDIA_ID_DOWNLOADS = "__DOWNLOADS__"
        const val METADATA_SET = "robophish.metadata.SET"
        const val METADATA_SET_NAME = "robophish.metadata.SET_NAME"
        const val METADATA_SHOW_SBD = "robophish.metadata.SHOW_SBD"
        const val METADATA_STREAM_URL = "robophish.metadata.STREAM_URL"
        const val METADATA_DURATION_MS = "robophish.metadata.DURATION_MS"
    }
}
