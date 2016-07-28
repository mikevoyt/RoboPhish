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

package com.example.android.uamp.model;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import com.example.android.uamp.R;
import com.example.android.uamp.utils.LogHelper;
import com.example.android.uamp.utils.MediaIDHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR;
import static com.example.android.uamp.utils.MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW;
import static com.example.android.uamp.utils.MediaIDHelper.createMediaID;

/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    private MusicProviderSource mSource;

    // Categorized caches for music track data
    private List<String> mYears;
    private final Set<String> mFavoriteTracks;
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;
    private ConcurrentMap<String, List<MediaMetadataCompat>> mShowsInYearYear;
    private ConcurrentMap<String, List<MediaMetadataCompat>> mTracksInShow;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void onMusicCatalogReady(boolean success);
    }

    public MusicProvider() {
        this(new PhishProviderSource());
    }

    public MusicProvider(MusicProviderSource source) {
        mSource = source;
        mYears = new ArrayList<String>();
        mShowsInYearYear = new ConcurrentHashMap<>();
        mTracksInShow = new ConcurrentHashMap<>();
        mMusicListById = new ConcurrentHashMap<>();

        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    }


    /**
     * Get shows for the given year
     *
     */
    public Iterable<MediaMetadataCompat> getShowsByYear(String year) {
        if (mCurrentState != State.INITIALIZED || !mYears.contains(year)) {
            return Collections.emptyList();
        }
        return mSource.showsInYear(year);
    }

    /**
     * Get tracks for the given show
     *
     */
    public Iterable<MediaMetadataCompat> getTracksForShow(String showId) {
        if (mCurrentState != State.INITIALIZED) {
            return Collections.emptyList();
        }
        return mSource.tracksInShow(showId);
    }


    /**
     * Return the MediaMetadataCompat for the given musicID.
     *
     * @param musicId The unique, non-hierarchical music ID.
     */
    public MediaMetadataCompat getMusic(String musicId) {
        return mMusicListById.containsKey(musicId) ? mMusicListById.get(musicId).metadata : null;
    }

    public synchronized void updateMusicArt(String musicId, Bitmap albumArt, Bitmap icon) {
        MediaMetadataCompat metadata = getMusic(musicId);
        metadata = new MediaMetadataCompat.Builder(metadata)

                // set high resolution bitmap in METADATA_KEY_ALBUM_ART. This is used, for
                // example, on the lockscreen background when the media session is active.
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)

                // set small version of the album art in the DISPLAY_ICON. This is used on
                // the MediaDescription and thus it should be small to be serialized if
                // necessary
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon)

                .build();

        MutableMediaMetadata mutableMetadata = mMusicListById.get(musicId);
        if (mutableMetadata == null) {
            throw new IllegalStateException("Unexpected error: Inconsistent data structures in " +
                    "MusicProvider");
        }

        mutableMetadata.metadata = metadata;
    }

        public void setFavorite(String musicId, boolean favorite) {
                if (favorite) {
                        mFavoriteTracks.add(musicId);
                    } else {
                        mFavoriteTracks.remove(musicId);
                    }
            }

        public boolean isFavorite(String musicId) {
                return mFavoriteTracks.contains(musicId);
            }


    public boolean isInitialized() {
        return mCurrentState == State.INITIALIZED;
    }

    /**
     * Get the list of years from server
     */
    public void retrieveMediaAsync(final Callback callback) {
        LogHelper.d(TAG, "retrieveMediaAsync called");
        if (mCurrentState == State.INITIALIZED) {
            if (callback != null) {
                // Nothing to do, execute callback immediately
                callback.onMusicCatalogReady(true);
            }
            return;
        }

        // Asynchronously load the music catalog in a separate thread
        new AsyncTask<Void, Void, State>() {
            @Override
            protected State doInBackground(Void... params) {
                retrieveYears();
                return mCurrentState;
            }

            @Override
            protected void onPostExecute(State current) {
                if (callback != null) {
                    callback.onMusicCatalogReady(current == State.INITIALIZED);
                }
            }
        }.execute();
    }

    private synchronized void retrieveYears() {
        try {
            if (mCurrentState == State.NON_INITIALIZED) {
                mCurrentState = State.INITIALIZING;

                mYears = mSource.years();
                mCurrentState = State.INITIALIZED;
            }
        } finally {
            if (mCurrentState != State.INITIALIZED) {
                // Something bad happened, so we reset state to NON_INITIALIZED to allow
                // retries (eg if the network connection is temporary unavailable)
                mCurrentState = State.NON_INITIALIZED;
            }
        }
    }


    public List<MediaBrowserCompat.MediaItem> getChildren(String mediaId, Resources resources) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        if (!MediaIDHelper.isBrowseable(mediaId)) {
            return mediaItems;
        }

        if (MEDIA_ID_ROOT.equals(mediaId)) {
            mediaItems.add(createBrowsableMediaItemForRoot(resources));


        } else if (MEDIA_ID_SHOWS_BY_YEAR.equals(mediaId)) {
            for (String year : mYears) {
                mediaItems.add(createBrowsableMediaItemForYear(year, resources));
            }

        } else if (mediaId.startsWith(MEDIA_ID_SHOWS_BY_YEAR)) {
            String genre = MediaIDHelper.getHierarchy(mediaId)[1];
            for (MediaMetadataCompat metadata : getShowsByYear(genre)) {
                mediaItems.add(createMediaItem(metadata));
            }

        } else if (mediaId.startsWith(MEDIA_ID_TRACKS_BY_SHOW)) {
            String showId = MediaIDHelper.getHierarchy(mediaId)[1];
            for (MediaMetadataCompat metadata : getTracksForShow(showId)) {
                mediaItems.add(createMediaItem(metadata));
            }

        } else {
            LogHelper.w(TAG, "Skipping unmatched mediaId: ", mediaId);
        }
        return mediaItems;
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForRoot(Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(MEDIA_ID_SHOWS_BY_YEAR)
                .setTitle(resources.getString(R.string.browse_years))
                .setSubtitle(resources.getString(R.string.browse_years_subtitle))
                .setIconUri(Uri.parse("android.resource://" +
                        "com.example.android.uamp/drawable/ic_by_genre"))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }


    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForYear(String year,
                                                                          Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_SHOWS_BY_YEAR, year))
                .setTitle(year)
                .setSubtitle(resources.getString(
                        R.string.browse_musics_by_genre_subtitle, year))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForShow(String showId,
                                                                         Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_TRACKS_BY_SHOW, showId))
                .setTitle(showId)
                .setSubtitle(resources.getString(
                        R.string.browse_musics_by_genre_subtitle, showId))
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createMediaItem(MediaMetadataCompat metadata) {
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)
        String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                metadata.getDescription().getMediaId(), MEDIA_ID_TRACKS_BY_SHOW, title);
        MediaMetadataCompat copy = new MediaMetadataCompat.Builder(metadata)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .build();
        return new MediaBrowserCompat.MediaItem(copy.getDescription(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);

    }

}
