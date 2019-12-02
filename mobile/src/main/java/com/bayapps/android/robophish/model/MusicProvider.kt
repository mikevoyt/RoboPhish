/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bayapps.android.robophish.model

import android.content.Context
import android.graphics.Bitmap
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.utils.MediaIDHelper
import robophish.model.YearData
import timber.log.Timber
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap


/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
class MusicProvider(
        private val context: Context,
        private val source: MusicProviderSource
) {

    private var cachedTracks: MutableMap<String, List<MediaMetadataCompat>> = Collections.synchronizedMap(mutableMapOf())

    // Categorized caches for music track data
    private val favoriteTracks: MutableSet<String> = mutableSetOf()
    private val musicListById: ConcurrentMap<String, MutableMediaMetadata?> = ConcurrentHashMap()

    /**
     * Get tracks for the given show
     */
    suspend fun getTracksForShow(showId: String): List<MediaMetadataCompat> = source.tracksInShow(showId)

    fun getCachedTracksForShow(showId: String) = cachedTracks[showId]

    /**
     * Return the MediaMetadataCompat for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    fun getMusic(musicId: String?): MediaMetadataCompat? = if (musicListById.containsKey(musicId)) musicListById[musicId]!!.metadata else null

    fun updateMusicArt(musicId: String?, albumArt: Bitmap?, icon: Bitmap?) {
        val metadata = MediaMetadataCompat.Builder(getMusic(musicId))
                // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is used, for
                // example, on the lockscreen background when the media session is active.
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                // set small version of the album art in the DISPLAY_ICON. This is used on
                // the MediaDescription and thus it should be small to be serialized if
                // necessary
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)
                .build()

        val mutableMetadata = musicListById[musicId]
                ?: throw IllegalStateException("Unexpected error: Inconsistent data structures in MusicProvider")
        mutableMetadata.metadata = metadata
    }

    fun setFavorite(musicId: String, favorite: Boolean) {
        if (favorite) {
            favoriteTracks.add(musicId)
        } else {
            favoriteTracks.remove(musicId)
        }
    }

    fun isFavorite(musicId: String?): Boolean {
        return favoriteTracks.contains(musicId)
    }

    suspend fun childeren(mediaId: String): List<MediaBrowserCompat.MediaItem> {
        if (!MediaIDHelper.isBrowseable(mediaId)) {
            return emptyList()
        }

        if (MediaIDHelper.MEDIA_ID_ROOT == mediaId) {
            //always refresh years so we get fresh show count
            return source.years().toMediaItemList()
        }

        if (mediaId.startsWith(MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR)) {
            val year = MediaIDHelper.getHierarchy(mediaId)[1]
            return source.showsInYear(year).toMediaItemListOfShows()
        }

        if (mediaId.startsWith(MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW)) {
            val showId = MediaIDHelper.getHierarchy(mediaId)[1]
            val tracks = source.tracksInShow(showId)
            cachedTracks[showId] = tracks

            tracks.map {
                MutableMediaMetadata(it.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID), it)
            }.forEach { musicListById[it.trackId] = it }

            return tracks.toMediaItemListOfTracks()
        }

        Timber.w("Skipping unmatched mediaId: %s", mediaId)
        return emptyList()
    }

    private fun List<YearData>.toMediaItemList(): List<MediaBrowserCompat.MediaItem> = map {
        val description = MediaDescriptionCompat.Builder()
                .setMediaId(MediaIDHelper.createMediaID(null, MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR, it.date))
                .setTitle(it.date)
                .setSubtitle("${it.show_count} shows")
                .build()

        MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun List<MediaMetadataCompat>.toMediaItemListOfShows(): List<MediaBrowserCompat.MediaItem> = map { show ->
        val showId = show.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
        val venue = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE)
        val location = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
        val date = show.getString(MediaMetadataCompat.METADATA_KEY_DATE)

        val description = MediaDescriptionCompat.Builder()
                .setMediaId(MediaIDHelper.createMediaID(null, MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW, showId))
                .setTitle(venue)
                .setSubtitle(context.getString(R.string.browse_musics_by_genre_subtitle, date))
                .setDescription(location)
                .build()
        MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    private fun List<MediaMetadataCompat>.toMediaItemListOfTracks(): List<MediaBrowserCompat.MediaItem> = map { track ->
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)
        val venue = track.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
        val showId = track.getString(MediaMetadataCompat.METADATA_KEY_COMPILATION)
        val hierarchyAwareMediaID = MediaIDHelper.createMediaID(track.description.mediaId,
                MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW, showId)

        val copy = MediaMetadataCompat.Builder(track)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, venue)
                .build()
        MediaBrowserCompat.MediaItem(copy.description,
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }
}
