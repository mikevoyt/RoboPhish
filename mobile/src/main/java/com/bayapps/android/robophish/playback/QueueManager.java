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

package com.bayapps.android.robophish.playback;

import android.content.res.Resources;
import android.os.Bundle;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.annotation.NonNull;

import com.bayapps.android.robophish.R;
import com.bayapps.android.robophish.model.MusicProvider;
import com.bayapps.android.robophish.utils.MediaIDHelper;
import com.bayapps.android.robophish.utils.PicassoKt;
import com.bayapps.android.robophish.utils.QueueHelper;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import kotlin.Unit;
import timber.log.Timber;

import static android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION;

/**
 * Simple data provider for queues. Keeps track of a current queue and a current index in the
 * queue. Also provides methods to set the current queue based on common queries, relying on a
 * given MusicProvider to provide the actual media metadata.
 */
public class QueueManager {

    private MusicProvider mMusicProvider;
    private MetadataUpdateListener mListener;
    private Resources mResources;
    private Picasso picasso;

    // "Now playing" queue:
    private List<MediaSessionCompat.QueueItem> mPlayingQueue;
    private int mCurrentIndex;

    public QueueManager(
            @NonNull MusicProvider musicProvider,
            @NonNull Resources resources,
            @NonNull Picasso picasso,
            @NonNull MetadataUpdateListener listener
    ) {
        this.mMusicProvider = musicProvider;
        this.mListener = listener;
        this.mResources = resources;
        this.picasso = picasso;

        mPlayingQueue = Collections.synchronizedList(new ArrayList<>());
        mCurrentIndex = 0;
    }

    public boolean isSameBrowsingCategory(@NonNull String mediaId) {
        String[] newBrowseHierarchy = MediaIDHelper.getHierarchy(mediaId);
        MediaSessionCompat.QueueItem current = getCurrentMusic();
        if (current == null) {
            return false;
        }
        String[] currentBrowseHierarchy = MediaIDHelper.getHierarchy(
                current.getDescription().getMediaId());

        return Arrays.equals(newBrowseHierarchy, currentBrowseHierarchy);
    }

    private void setCurrentQueueIndex(int index) {
        if (index >= 0 && index < mPlayingQueue.size()) {
            mCurrentIndex = index;
            mListener.onCurrentQueueIndexUpdated(mCurrentIndex);
        }
    }

    public boolean setCurrentQueueItem(long queueId) {
        // set the current index on queue from the queue Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, queueId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    public boolean setCurrentQueueItem(String mediaId) {
        // set the current index on queue from the music Id:
        int index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, mediaId);
        setCurrentQueueIndex(index);
        return index >= 0;
    }

    public boolean skipQueuePosition(int amount) {
        int index = mCurrentIndex + amount;
        if (index < 0) {
            // skip backwards before the first song will keep you on the first song
            index = 0;
        } else {
            // skip forwards when in last song will cycle back to start of the queue
            index %= mPlayingQueue.size();
        }
        if (!QueueHelper.isIndexPlayable(index, mPlayingQueue)) {
            Timber.e("Cannot increment queue index by %s . Current=%s queue length=%s", amount,
                    mCurrentIndex, mPlayingQueue.size());
            return false;
        }
        mCurrentIndex = index;
        return true;
    }

    public boolean setQueueFromSearch(String query, Bundle extras) {
        List<MediaSessionCompat.QueueItem> queue =
                QueueHelper.getPlayingQueueFromSearch(query, extras, mMusicProvider);
        setCurrentQueue(mResources.getString(R.string.search_queue_title), queue);
        return queue != null && !queue.isEmpty();
    }

    public void setRandomQueue() {
        setCurrentQueue(mResources.getString(R.string.random_queue_title),
                QueueHelper.getRandomQueue(mMusicProvider));
    }

    public void setQueueFromMusic(String mediaId) {
        Timber.d("setQueueFromMusic %s", mediaId);

        // The mediaId used here is not the unique musicId. This one comes from the
        // MediaBrowser, and is actually a "hierarchy-aware mediaID": a concatenation of
        // the hierarchy in MediaBrowser and the actual unique musicID. This is necessary
        // so we can build the correct playing queue, based on where the track was
        // selected from.
        boolean canReuseQueue = false;
        if (isSameBrowsingCategory(mediaId)) {
            canReuseQueue = setCurrentQueueItem(mediaId);
        }
        if (!canReuseQueue) {
            String queueTitle = mResources.getString(R.string.browse_musics_by_genre_subtitle,
                    MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaId));
            setCurrentQueue(queueTitle,
                    QueueHelper.getPlayingQueue(mediaId, mMusicProvider), mediaId);
        }
        updateMetadata();
    }

    public MediaSessionCompat.QueueItem getCurrentMusic() {
        if (!QueueHelper.isIndexPlayable(mCurrentIndex, mPlayingQueue)) {
            return null;
        }
        return mPlayingQueue.get(mCurrentIndex);
    }

    public int getCurrentQueueSize() {
        if (mPlayingQueue == null) {
            return 0;
        }
        return mPlayingQueue.size();
    }

    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue) {
        setCurrentQueue(title, newQueue, null);
    }

    protected void setCurrentQueue(String title, List<MediaSessionCompat.QueueItem> newQueue,
                                   String initialMediaId) {
        mPlayingQueue = newQueue;
        int index = 0;
        if (initialMediaId != null) {
            index = QueueHelper.getMusicIndexOnQueue(mPlayingQueue, initialMediaId);
        }
        mCurrentIndex = Math.max(index, 0);
        mListener.onQueueUpdated(title, newQueue);
    }

    public long getDuration() {
        MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
        if (currentMusic == null) {
            return -1;
        }

        final String musicId = MediaIDHelper.extractMusicIDFromMediaID(
                currentMusic.getDescription().getMediaId());

        MediaMetadataCompat metadata = mMusicProvider.getMusic(musicId);

            if (metadata == null) {
                throw new IllegalArgumentException("Invalid musicId " + musicId);
            }

            long duration = metadata.getLong(METADATA_KEY_DURATION);

            return duration;
    }

    public void updateMetadata() {
        MediaSessionCompat.QueueItem currentMusic = getCurrentMusic();
        if (currentMusic == null) {
            mListener.onMetadataRetrieveError();
            return;
        }
        final String musicId = MediaIDHelper.extractMusicIDFromMediaID(
                currentMusic.getDescription().getMediaId());
        MediaMetadataCompat metadata = mMusicProvider.getMusic(musicId);
        if (metadata == null) {
            throw new IllegalArgumentException("Invalid musicId " + musicId);
        }

        mListener.onMetadataChanged(metadata);

        // Set the proper album artwork on the media session, so it can be shown in the
        // locked screen and in other places.
        if (metadata.getDescription().getIconBitmap() == null &&
                metadata.getDescription().getIconUri() != null) {
            String albumUri = metadata.getDescription().getIconUri().toString();


            PicassoKt.loadLargeAndSmallImage(picasso, albumUri, result -> {
                mMusicProvider.updateMusicArt(musicId, result.getImage(), result.getIcon());

                // If we are still playing the same music, notify the listeners:
                MediaSessionCompat.QueueItem music = getCurrentMusic();
                if (music == null) {
                    return Unit.INSTANCE;
                }
                String currentPlayingId = MediaIDHelper.extractMusicIDFromMediaID(music.getDescription().getMediaId());
                if (musicId.equals(currentPlayingId)) {
                    mListener.onMetadataChanged(mMusicProvider.getMusic(currentPlayingId));
                }

                return Unit.INSTANCE;
            });
        }
    }

    public interface MetadataUpdateListener {
        void onMetadataChanged(MediaMetadataCompat metadata);
        void onMetadataRetrieveError();
        void onCurrentQueueIndexUpdated(int queueIndex);
        void onQueueUpdated(String title, List<MediaSessionCompat.QueueItem> newQueue);
    }
}
