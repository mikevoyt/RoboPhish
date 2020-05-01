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
package com.bayapps.android.robophish

import android.os.Bundle
import android.provider.MediaStore

/**
 * For more information about voice search parameters,
 * check https://developer.android.com/guide/components/intents-common.html#PlaySearch
 */
class VoiceSearchParams(private val query: String, extras: Bundle?) {
    var isAny = false
        private set
    var isUnstructured = false
        private set
    var isGenreFocus = false
        private set
    var isArtistFocus = false
        private set
    var isAlbumFocus = false
        private set
    var isSongFocus = false
        private set
    var genre: String? = null
        private set
    var artist: String? = null
        private set
    var album: String? = null
        private set
    var song: String? = null
        private set

    /**
     * Creates a simple object describing the search criteria from the query and extras.
     * @param query the query parameter from a voice search
     * @param extras the extras parameter from a voice search
     */
    init {
        init(extras)
    }

    // Function outside of constructor to be able to return reducing the number of
    // if else statements.
    private fun init(extras: Bundle?) {
        if (query.isBlank()) {
            // A generic search like "Play music" sends an empty query
            isAny = true
            return
        }

        if (extras == null) {
            isUnstructured = true
            return
        }

        when (extras.getString(MediaStore.EXTRA_MEDIA_FOCUS)) {
            MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE -> {
                // for a Genre focused search, only genre is set:
                isGenreFocus = true
                genre = extras.getString(MediaStore.EXTRA_MEDIA_GENRE)
                if (genre.isNullOrBlank()) {
                    // Because of a bug on the platform, genre is only sent as a query, not as
                    // the semantic-aware extras. This check makes it future-proof when the
                    // bug is fixed.
                    genre = query
                }
            }
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> {
                // for an Artist focused search, both artist and genre are set:
                isArtistFocus = true
                genre = extras.getString(MediaStore.EXTRA_MEDIA_GENRE)
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> {
                // for an Album focused search, album, artist and genre are set:
                isAlbumFocus = true
                album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                genre = extras.getString(MediaStore.EXTRA_MEDIA_GENRE)
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> {
                // for a Song focused search, title, album, artist and genre are set:
                isSongFocus = true
                song = extras.getString(MediaStore.EXTRA_MEDIA_TITLE)
                album = extras.getString(MediaStore.EXTRA_MEDIA_ALBUM)
                genre = extras.getString(MediaStore.EXTRA_MEDIA_GENRE)
                artist = extras.getString(MediaStore.EXTRA_MEDIA_ARTIST)
            }
            else -> {
                // If we don't know the focus, we treat it is an unstructured query:
                isUnstructured = true
            }
        }
    }

    override fun toString(): String {
        return ("query=" + query
                + " isAny=" + isAny
                + " isUnstructured=" + isUnstructured
                + " isGenreFocus=" + isGenreFocus
                + " isArtistFocus=" + isArtistFocus
                + " isAlbumFocus=" + isAlbumFocus
                + " isSongFocus=" + isSongFocus
                + " genre=" + genre
                + " artist=" + artist
                + " album=" + album
                + " song=" + song)
    }
}
