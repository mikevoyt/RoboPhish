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
import android.os.Bundle;
import android.os.RemoteException;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.session.MediaControllerCompat;

import androidx.fragment.app.FragmentActivity;

import com.bayapps.android.robophish.MusicService;
import com.bayapps.android.robophish.R;

import timber.log.Timber;

public class TvVerticalGridActivity extends FragmentActivity
        implements TvVerticalGridFragment.MediaFragmentListener {

    public static final String SHARED_ELEMENT_NAME = "hero";
    private MediaBrowserCompat mMediaBrowser;
    private String mMediaId;
    private String mTitle;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tv_vertical_grid);

        mMediaId = getIntent().getStringExtra(TvBrowseActivity.SAVED_MEDIA_ID);
        mTitle = getIntent().getStringExtra(TvBrowseActivity.BROWSE_TITLE);

        getWindow().setBackgroundDrawableResource(R.drawable.bg);

        mMediaBrowser = new MediaBrowserCompat(this,
                new ComponentName(this, MusicService.class),
                mConnectionCallback, null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.d("Activity onStart: mMediaBrowser connect");
        mMediaBrowser.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mMediaBrowser.disconnect();
    }

    protected void browse() {
        Timber.d("navigateToBrowser, mediaId=%s",mMediaId);
        TvVerticalGridFragment fragment = (TvVerticalGridFragment) getSupportFragmentManager()
                .findFragmentById(R.id.vertical_grid_fragment);
        fragment.setMediaId(mMediaId);
        fragment.setTitle(mTitle);
    }

    @Override
    public MediaBrowserCompat getMediaBrowser() {
        return mMediaBrowser;
    }

    private final MediaBrowserCompat.ConnectionCallback mConnectionCallback =
            new MediaBrowserCompat.ConnectionCallback() {
                @Override
                public void onConnected() {
                    Timber.d("onConnected: session token %s",
                            mMediaBrowser.getSessionToken());

                    try {
                        MediaControllerCompat mediaController = new MediaControllerCompat(
                                TvVerticalGridActivity.this, mMediaBrowser.getSessionToken());
                        MediaControllerCompat.setMediaController(TvVerticalGridActivity.this, mediaController);
                        browse();
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
                    MediaControllerCompat.setMediaController(TvVerticalGridActivity.this, null);
                }
            };

}
