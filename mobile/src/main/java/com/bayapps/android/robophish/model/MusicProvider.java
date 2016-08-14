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

package com.bayapps.android.robophish.model;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;

import com.bayapps.android.robophish.R;
import com.bayapps.android.robophish.utils.LogHelper;
import com.bayapps.android.robophish.utils.MediaIDHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.bayapps.android.robophish.utils.MediaIDHelper.MEDIA_ID_ROOT;
import static com.bayapps.android.robophish.utils.MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR;
import static com.bayapps.android.robophish.utils.MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW;
import static com.bayapps.android.robophish.utils.MediaIDHelper.createMediaID;

import com.google.common.collect.Lists;

/**
 * Simple data provider for music tracks. The actual metadata source is delegated to a
 * MusicProviderSource defined by a constructor argument of this class.
 */
public class MusicProvider {

    private static final String TAG = LogHelper.makeLogTag(MusicProvider.class);

    private MusicProviderSource mSource;

    // Categorized caches for music track data
    private List<YearData> mYears;
    private final Set<String> mFavoriteTracks;
    private final ConcurrentMap<String, MutableMediaMetadata> mMusicListById;
    private ConcurrentMap<String, List<MediaMetadataCompat>> mShowsInYearYear;
    private ConcurrentMap<String, List<MediaMetadataCompat>> mTracksInShow;

    enum State {
        NON_INITIALIZED, INITIALIZING, INITIALIZED
    }

    //private volatile State mCurrentState = State.NON_INITIALIZED;

    public interface Callback {
        void children(List<MediaBrowserCompat.MediaItem> mediaItems);
    }

    public MusicProvider() {
        this(new PhishProviderSource());
    }

    public MusicProvider(MusicProviderSource source) {
        mSource = source;
        mYears = new ArrayList<YearData>();
        mShowsInYearYear = new ConcurrentHashMap<>();
        mTracksInShow = new ConcurrentHashMap<>();
        mMusicListById = new ConcurrentHashMap<>();

        mFavoriteTracks = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    }


    /**
     * Get shows for the given year
     */
    public Iterable<MediaMetadataCompat> getShowsByYear(String year) {
        if (!mYears.contains(year)) {
            return Collections.emptyList();
        }
        return mSource.showsInYear(year);
    }

    /**
     * Get tracks for the given show
     */
    public Iterable<MediaMetadataCompat> getTracksForShow(String showId) {
        return mSource.tracksInShow(showId);
    }

    /**
     * Get cached tracks for the given show
     */
    public Iterable<MediaMetadataCompat> getCachedTracksForShow(String showId) {
        return mTracksInShow.get(showId);
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


    public synchronized void getChildrenAsync(final String mediaId, final Resources resources, final Callback callback) {

        final List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        if (!MediaIDHelper.isBrowseable(mediaId)) {
            callback.children(mediaItems);
        }

        else if (MEDIA_ID_ROOT.equals(mediaId)) {

            // Asynchronously load the years in a separate thread
            new AsyncTask<Void, Void, State>() {
                @Override
                protected State doInBackground(Void... params) {
                    List<YearData> years = mSource.years();  //always refresh years so we get fresh show count
                    if (!mYears.isEmpty() && (years.get(0).getShowCount() != mYears.get(0).getShowCount())) {
                        mShowsInYearYear = new ConcurrentHashMap<>();  //clear cache if number of shows have changed
                    }

                    mYears = years;

                    for (YearData year : mYears) {
                        mediaItems.add(createBrowsableMediaItemForYear(year.getYear(), year.getShowCount(), resources));
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(State current) {
                    if (callback != null) {
                        callback.children(mediaItems);
                    }
                }
            }.execute();


        } else if (mediaId.startsWith(MEDIA_ID_SHOWS_BY_YEAR)) {

            // Asynchronously load the shows in a separate thread
            new AsyncTask<Void, Void, State>() {
                @Override
                protected State doInBackground(Void... params) {

                    final String year = MediaIDHelper.getHierarchy(mediaId)[1];
                    LogHelper.w(TAG, "year: ", year);

                    List<MediaMetadataCompat> shows = mShowsInYearYear.get(year);
                    if (shows == null || shows.isEmpty()) {
                        shows = Lists.newArrayList(mSource.showsInYear(year));
                        mShowsInYearYear.put(year, shows);
                    }

                    for (MediaMetadataCompat show : shows) {
                        mediaItems.add(createBrowsableMediaItemForShow(show, resources));
                    }

                    return null;
                }

                @Override
                protected void onPostExecute(State current) {
                    if (callback != null) {
                        callback.children(mediaItems);
                    }
                }
            }.execute();

        } else if (mediaId.startsWith(MEDIA_ID_TRACKS_BY_SHOW)) {

            // Asynchronously load the shows in a separate thread
            new AsyncTask<Void, Void, State>() {
                @Override
                protected State doInBackground(Void... params) {

                    final String showId = MediaIDHelper.getHierarchy(mediaId)[1];
                    LogHelper.w(TAG, "showId: ", showId);

                    List<MediaMetadataCompat> tracks = mTracksInShow.get(showId);

                    if (tracks == null || tracks.isEmpty()) {
                        tracks = Lists.newArrayList(mSource.tracksInShow(showId));
                        mTracksInShow.put(showId, tracks);
                    }
                    for (MediaMetadataCompat track : tracks) {
                        String id = track.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);

                        mediaItems.add(createMediaItem(track));
                        mMusicListById.put(id, new MutableMediaMetadata(id, track));
                    }

                    return null;
                }

                @Override
                protected void onPostExecute(State current) {
                    if (callback != null) {
                        callback.children(mediaItems);
                    }
                }
            }.execute();

        } else {
            LogHelper.w(TAG, "Skipping unmatched mediaId: ", mediaId);
        }
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForYear(String year, String showCount,
                                                                         Resources resources) {
        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_SHOWS_BY_YEAR, year))
                .setTitle(year)
                .setSubtitle(showCount + " shows")
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createBrowsableMediaItemForShow(MediaMetadataCompat show,
                                                                         Resources resources) {

        String showId = show.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);
        String venue = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE);
        String location = show.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);
        String date = show.getString(MediaMetadataCompat.METADATA_KEY_DATE);

        MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                .setMediaId(createMediaID(null, MEDIA_ID_TRACKS_BY_SHOW, showId))
                .setTitle(venue)
                .setSubtitle(resources.getString(
                        R.string.browse_musics_by_genre_subtitle, date))
                .setDescription(location)
                .build();
        return new MediaBrowserCompat.MediaItem(description,
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE);
    }

    private MediaBrowserCompat.MediaItem createMediaItem(MediaMetadataCompat metadata) {
        // Since mediaMetadata fields are immutable, we need to create a copy, so we
        // can set a hierarchy-aware mediaID. We will need to know the media hierarchy
        // when we get a onPlayFromMusicID call, so we can create the proper queue based
        // on where the music was selected from (by artist, by genre, random, etc)
        String title = metadata.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID);

        String venue = metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE);

        String showId = metadata.getString(MediaMetadataCompat.METADATA_KEY_COMPILATION);
        String hierarchyAwareMediaID = MediaIDHelper.createMediaID(
                metadata.getDescription().getMediaId(), MEDIA_ID_TRACKS_BY_SHOW, showId);
        MediaMetadataCompat copy = new MediaMetadataCompat.Builder(metadata)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, hierarchyAwareMediaID)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, venue)
                .build();

        return new MediaBrowserCompat.MediaItem(copy.getDescription(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE);

    }

}
