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
package com.bayapps.android.robophish.ui.tv;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.bayapps.android.robophish.MusicService;
import com.bayapps.android.robophish.R;

import timber.log.Timber;

/**
 * Main activity for the Android TV user interface.
 */
public class TvBrowseActivity extends FragmentActivity
        implements TvBrowseFragment.MediaFragmentListener {

    public static final String SAVED_MEDIA_ID="com.example.android.uamp.MEDIA_ID";
    public static final String BROWSE_TITLE = "com.example.android.uamp.BROWSE_TITLE";

    private MediaBrowserCompat mMediaBrowser;

    private String mMediaId;
    private String mBrowseTitle;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.d("Activity onCreate");

        setContentView(R.layout.tv_activity_player);

        mMediaBrowser = new MediaBrowserCompat(this,
                new ComponentName(this, MusicService.class),
                mConnectionCallback, null);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (mMediaId != null) {
            outState.putString(SAVED_MEDIA_ID, mMediaId);
            outState.putString(BROWSE_TITLE, mBrowseTitle);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.d("Activity onStart");
        mMediaBrowser.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Timber.d("Activity onStop");
        if (mMediaBrowser != null) {
            mMediaBrowser.disconnect();
        }
    }
    
    @Override
    public boolean onSearchRequested() {
        startActivity(new Intent(this, TvBrowseActivity.class));
        return true;
    }

    protected void navigateToBrowser(String mediaId) {
        Timber.d("navigateToBrowser, mediaId=%s", mediaId);
        TvBrowseFragment fragment =
                (TvBrowseFragment) getSupportFragmentManager().findFragmentById(R.id.main_browse_fragment);
        fragment.initializeWithMediaId(mediaId);
        mMediaId = mediaId;
        if (mediaId == null) {
            mBrowseTitle = getResources().getString(R.string.home_title);
        }
        fragment.setTitle(mBrowseTitle);
    }

    @Override
    public MediaBrowserCompat getMediaBrowser() {
        return mMediaBrowser;
    }

    private final MediaBrowserCompat.ConnectionCallback mConnectionCallback =
            new MediaBrowserCompat.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Timber.d("onConnected: session token %s", mMediaBrowser.getSessionToken());
                    try {
                        MediaControllerCompat mediaController = new MediaControllerCompat(
                                TvBrowseActivity.this, mMediaBrowser.getSessionToken());
                        MediaControllerCompat.setMediaController(TvBrowseActivity.this, mediaController);
                        navigateToBrowser(mMediaId);
                    } catch (RemoteException e) {
                        Timber.e(e, "could not connect media controller");
                    }
                }

                @Override
                public void onConnectionFailed() {
                    Timber.d("onConnectionFailed");
                }

                @Override
                public void onConnectionSuspended() {
                    Timber.d("onConnectionSuspended");
                    MediaControllerCompat.setMediaController(TvBrowseActivity.this, null);
                }
            };
}
