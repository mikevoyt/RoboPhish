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
package com.bayapps.android.robophish.ui;

import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.text.TextUtils;

import androidx.fragment.app.FragmentTransaction;

import com.bayapps.android.robophish.R;
import com.bayapps.android.robophish.utils.MediaIDHelper;

import timber.log.Timber;

/**
 * Main activity for the music player.
 * This class hold the MediaBrowser and the MediaController instances. It will create a MediaBrowser
 * when it is created and connect/disconnect on start/stop. Thus, a MediaBrowser will be always
 * connected while this activity is running.
 */
public class MusicPlayerActivity extends BaseActivity
        implements MediaBrowserFragment.MediaFragmentListener {

    private static final String SAVED_MEDIA_ID="com.example.android.uamp.MEDIA_ID";
    private static final String FRAGMENT_TAG = "uamp_list_container";

    public static final String EXTRA_START_FULLSCREEN =
            "com.example.android.uamp.EXTRA_START_FULLSCREEN";

    /**
     * Optionally used with {@link #EXTRA_START_FULLSCREEN} to carry a MediaDescription to
     * the {@link FullScreenPlayerActivity}, speeding up the screen rendering
     * while the {@link android.support.v4.media.session.MediaControllerCompat} is connecting.
     */
    public static final String EXTRA_CURRENT_MEDIA_DESCRIPTION =
        "com.example.android.uamp.CURRENT_MEDIA_DESCRIPTION";

    private Bundle mVoiceSearchParams;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.d("Activity onCreate");

        setContentView(R.layout.activity_player);

        initializeToolbar();
        initializeFromParams(savedInstanceState, getIntent());

        // Only check if a full screen player is needed on the first time:
        if (savedInstanceState == null) {
            startFullScreenActivityIfNeeded(getIntent());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        String mediaId = getMediaId();
        if (mediaId != null) {
            outState.putString(SAVED_MEDIA_ID, mediaId);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onMediaItemSelected(MediaBrowserCompat.MediaItem item) {
        Timber.d("onMediaItemSelected, mediaId=%s", item.getMediaId());
        if (item.isPlayable()) {
            getSupportMediaController().getTransportControls()
                    .playFromMediaId(item.getMediaId(), null);
        } else if (item.isBrowsable()) {

            String title = "";
            String subtitle = "";

            if (item.getDescription().getTitle() != null) {
                title = item.getDescription().getTitle().toString();
            }

            if (item.getDescription().getSubtitle() != null) {
                subtitle = item.getDescription().getSubtitle().toString();
            }

            navigateToBrowser(title, subtitle, item.getMediaId());

        } else {
            Timber.w("Ignoring MediaItem that is neither browsable nor playable: mediaId=%s",
                    item.getMediaId());
        }
    }

    @Override
    public void setToolbarTitle(CharSequence title) {
        Timber.d("Setting toolbar title to %s", title);
        if (title == null) {
            title = getString(R.string.app_name);
        }
        setTitle(title);
    }

    @Override
    public void setToolbarSubTitle(CharSequence subTitlle) {
        Timber.d("Setting toolbar title to %s", subTitlle);
        if (subTitlle == null) {
            subTitlle = "";
        }
        setSubtitle(subTitlle);
    }

    @Override
    public void updateDrawerToggle() {
        super.updateDrawerToggle();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Timber.d("onNewIntent, intent=%s", intent);
        initializeFromParams(null, intent);
        startFullScreenActivityIfNeeded(intent);
    }

    private void startFullScreenActivityIfNeeded(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START_FULLSCREEN, false)) {
            Intent fullScreenIntent = new Intent(this, FullScreenPlayerActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION, (Parcelable) intent.getParcelableExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION));
            startActivity(fullScreenIntent);
        }
    }

    protected void initializeFromParams(Bundle savedInstanceState, Intent intent) {
        String mediaId = null;
        // check if we were started from a "Play XYZ" voice search. If so, we save the extras
        // (which contain the query details) in a parameter, so we can reuse it later, when the
        // MediaSession is connected.
        if (intent.getAction() != null
            && intent.getAction().equals(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)) {
            mVoiceSearchParams = intent.getExtras();
            Timber.d("Starting from voice search query=%s",
                mVoiceSearchParams.getString(SearchManager.QUERY));
        } else if (intent.getAction() != null
                && intent.getAction().equals(MediaStore.INTENT_ACTION_MEDIA_SEARCH)) {

            navigateToBrowser(null, null, null);

            Bundle extras = intent.getExtras();
            String title = extras.getString("title");
            String subtitle = extras.getString("subtitle");
            mediaId = extras.getString("showid");

            String year = subtitle.split("-")[0];
            //browse to year...
            navigateToBrowser(null, null, MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR + "/" + year);

            //now launch as show
            navigateToBrowser(title, subtitle, mediaId);
        }


        else {
            if (savedInstanceState != null) {
                // If there is a saved media ID, use it
                mediaId = savedInstanceState.getString(SAVED_MEDIA_ID);
            }
            navigateToBrowser(null, null, mediaId);
        }
    }

    private void navigateToBrowser(String title, String subtitle, String mediaId) {
        Timber.d("navigateToBrowser, mediaId=%s", mediaId);
        MediaBrowserFragment fragment = getBrowseFragment();

        if (fragment == null || !TextUtils.equals(fragment.getMediaId(), mediaId)) {
            fragment = new MediaBrowserFragment();
            fragment.setMediaId(title, subtitle, mediaId);
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            transaction.setCustomAnimations(
                R.animator.slide_in_from_right, R.animator.slide_out_to_left,
                R.animator.slide_in_from_left, R.animator.slide_out_to_right);
            transaction.replace(R.id.container, fragment, FRAGMENT_TAG);
            // If this is not the top level media (root), we add it to the fragment back stack,
            // so that actionbar toggle and Back will work appropriately:
            if (mediaId != null) {
                transaction.addToBackStack(null);
            }
            transaction.commit();
        }
    }

    public String getMediaId() {
        MediaBrowserFragment fragment = getBrowseFragment();
        if (fragment == null) {
            return null;
        }
        return fragment.getMediaId();
    }

    private MediaBrowserFragment getBrowseFragment() {
        return (MediaBrowserFragment) getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
    }

    @Override
    protected void onMediaControllerConnected() {
        if (mVoiceSearchParams != null) {
            // If there is a bootstrap parameter to start from a search query, we
            // send it to the media session and set it to null, so it won't play again
            // when the activity is stopped/started or recreated:
            String query = mVoiceSearchParams.getString(SearchManager.QUERY);
            getSupportMediaController().getTransportControls()
                    .playFromSearch(query, mVoiceSearchParams);
            mVoiceSearchParams = null;
        }
        getBrowseFragment().onConnected();
    }
}
