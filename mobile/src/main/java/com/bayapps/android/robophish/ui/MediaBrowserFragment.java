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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import com.bayapps.android.robophish.R;
import com.bayapps.android.robophish.utils.Downloader;
import com.bayapps.android.robophish.utils.MediaIDHelper;
import com.bayapps.android.robophish.utils.NetworkHelper;
import com.google.android.material.tabs.TabLayout;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import cz.msebera.android.httpclient.Header;
import timber.log.Timber;

import static com.bayapps.android.robophish.utils.MediaIDHelper.extractShowFromMediaID;

/**
 * A Fragment that lists all the various browsable queues available
 * from a {@link android.service.media.MediaBrowserService}.
 * <p/>
 * It uses a {@link MediaBrowserCompat} to connect to the {@link com.bayapps.android.robophish.MusicService}.
 * Once connected, the fragment subscribes to get all the children.
 * All {@link MediaBrowserCompat.MediaItem}'s that can be browsed are shown in a ListView.
 */
public class MediaBrowserFragment extends Fragment {

    private static final String ARG_MEDIA_ID = "media_id";
    private static final String ARG_TITLE = "title";
    private static final String ARG_SUBTITLE = "subtitle";

    private BrowseAdapter mBrowserAdapter;
    private String mMediaId;
    private MediaFragmentListener mMediaFragmentListener;
    private View mErrorView;
    private TextView mErrorMessage;
    private ProgressBar mProgressBar;
    private JSONObject mShowData;

    private final BroadcastReceiver mConnectivityChangeReceiver = new BroadcastReceiver() {
        private boolean oldOnline = false;
        @Override
        public void onReceive(Context context, Intent intent) {
            // We don't care about network changes while this fragment is not associated
            // with a media ID (for example, while it is being initialized)
            if (mMediaId != null) {
                boolean isOnline = NetworkHelper.isOnline(context);
                if (isOnline != oldOnline) {
                    oldOnline = isOnline;
                    checkForUserVisibleErrors(false);
                    if (isOnline) {
                        mBrowserAdapter.notifyDataSetChanged();
                    }
                }
            }
        }
    };

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    private final MediaControllerCompat.Callback mMediaControllerCallback =
            new MediaControllerCompat.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            super.onMetadataChanged(metadata);
            if (metadata == null) {
                return;
            }
            Timber.d("Received metadata change to media %s",
                    metadata.getDescription().getMediaId());
            mBrowserAdapter.notifyDataSetChanged();
            mProgressBar.setVisibility(View.INVISIBLE);  //hide progress bar when we receive metadata
        }

        @Override
        public void onPlaybackStateChanged(@NonNull PlaybackStateCompat state) {
            super.onPlaybackStateChanged(state);
            Timber.d("Received state change: %s", state);
            checkForUserVisibleErrors(false);
            mBrowserAdapter.notifyDataSetChanged();
        }
    };

    private final MediaBrowserCompat.SubscriptionCallback mSubscriptionCallback =
        new MediaBrowserCompat.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(@NonNull String parentId,
                                         @NonNull List<MediaBrowserCompat.MediaItem> children) {
                try {
                    Timber.d("fragment onChildrenLoaded, parentId=%s, count=%s",
                            parentId, children.size());
                    checkForUserVisibleErrors(children.isEmpty());
                    mProgressBar.setVisibility(View.INVISIBLE);
                    mBrowserAdapter.clear();
                    for (MediaBrowserCompat.MediaItem item : children) {
                        mBrowserAdapter.add(item);
                    }
                    mBrowserAdapter.notifyDataSetChanged();
                } catch (Throwable t) {
                    Timber.e(t, "Error on childrenloaded");
                }
            }

            @Override
            public void onError(@NonNull String id) {
                Timber.e("browse fragment subscription onError, id=%s", id);
                Toast.makeText(getActivity(), R.string.error_loading_media, Toast.LENGTH_LONG).show();
                checkForUserVisibleErrors(true);
            }
        };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // If used on an activity that doesn't implement MediaFragmentListener, it
        // will throw an exception as expected:
        mMediaFragmentListener = (MediaFragmentListener) getActivity();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case 0:
                // start DownloadManager
                String showId = extractShowFromMediaID(getMediaId());
                Downloader dl = new Downloader(getActivity(), showId, mShowData);
                return true;
        }

        return super.onOptionsItemSelected(item); // important line
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        //menu.add("Download Show");  //TODO: enable once downloads are working
        super.onCreateOptionsMenu(menu,inflater);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Timber.d("fragment.onCreateView");

        View rootView;

        String mediaId = getMediaId();
        ListView listView;

        if (mediaId != null && MediaIDHelper.isShow(mediaId)) {

            setHasOptionsMenu(true);  //show option to download

            rootView = inflater.inflate(R.layout.fragment_list_show, container, false);

            ViewPager viewPager = rootView.findViewById(R.id.viewpager);
            viewPager.setAdapter(new ShowPagerAdapter(inflater, rootView));
            viewPager.setOffscreenPageLimit(3);

            TabLayout tabLayout = rootView.findViewById(R.id.sliding_tabs);
            tabLayout.setupWithViewPager(viewPager);

            final WebView setlist = rootView.findViewById(R.id.setlist_webview);
            setlist.getSettings().setJavaScriptEnabled(true);

            AsyncHttpClient setlistClient = new AsyncHttpClient();
            RequestParams setlistParams = new RequestParams();
            setlistParams.put("api", "2.0");
            setlistParams.put("method", "pnet.shows.setlists.get");
            setlistParams.put("showdate", getSubTitle());
            setlistParams.put("apikey", "C01AEE2002E80723E9E7");
            setlistParams.put("format", "json");
            setlistClient.get("http://api.phish.net/api.js", setlistParams, new JsonHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, Header[] headers, JSONArray response) {
                    super.onSuccess(statusCode, headers, response);
                    try {
                        JSONObject result = response.getJSONObject(0);
                        String city = result.getString("city");
                        String state = result.getString("state");
                        String country = result.getString("country");
                        String venue = result.getString("venue");

                        String header = "<h1>" + venue + "</h1>" + "<h2>" + city +
                                 ", " + state + "<br/>" + country + "</h2>";

                        String setlistdata = result.getString("setlistdata");
                        String setlistnotes = result.getString("setlistnotes");
                        setlist.loadData(header + setlistdata + setlistnotes, "text/html", null);
                    }  catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                        public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                            super.onFailure(statusCode, headers, throwable, errorResponse);
                        }
                    }
            );

            final WebView reviews = rootView.findViewById(R.id.reviews_webview);
            reviews.getSettings().setJavaScriptEnabled(true);

            AsyncHttpClient reviewsClient = new AsyncHttpClient();
            RequestParams reviewsParams = new RequestParams();
            reviewsParams.put("api", "2.0");
            reviewsParams.put("method", "pnet.reviews.query");
            reviewsParams.put("showdate", getSubTitle());
            reviewsParams.put("apikey", "C01AEE2002E80723E9E7");
            reviewsParams.put("format", "json");
            reviewsClient.get("http://api.phish.net/api.js", reviewsParams, new JsonHttpResponseHandler() {

                        @Override
                        public void onSuccess(int statusCode, Header[] headers, JSONArray response) {
                            super.onSuccess(statusCode, headers, response);
                            try {
                                StringBuilder display = new StringBuilder();

                                int len = response.length();
                                for (int i=0; i<len; i++) {
                                    JSONObject entry = response.getJSONObject(i);
                                    String author = entry.getString("author");
                                    String review = entry.getString("review");
                                    String tstamp = entry.getString("tstamp");

                                    Date reviewTime = new Date(Long.parseLong(tstamp)*1000);
                                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                    String reviewDate = dateFormat.format(reviewTime);

                                    String reviewSubs = review.replaceAll("\n", "<br/>");

                                    display.append("<h2>").append(author).append("</h2>").append("<h4>").append(reviewDate).append("</h4>");
                                    display.append(reviewSubs).append("<br/>");
                                }

                                reviews.loadData(display.toString(), "text/html", null);
                            }  catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                            super.onFailure(statusCode, headers, throwable, errorResponse);
                        }
                    }
            );


            final WebView tapernotesWebview = rootView.findViewById(R.id.tapernotes_webview);
            tapernotesWebview.getSettings().setJavaScriptEnabled(true);

            String showId = extractShowFromMediaID(mediaId);
            final AsyncHttpClient tapernotesClient = new AsyncHttpClient();
            tapernotesClient.get("http://phish.in/api/v1/shows/" + showId + ".json",
                    null, new JsonHttpResponseHandler() {

                        @Override
                        public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                            super.onSuccess(statusCode, headers, response);
                            try {
                                mShowData = response;
                                JSONObject data = response.getJSONObject("data");
                                String tapernotes = data.getString("taper_notes");
                                if (tapernotes.equals("null")) tapernotes = "Not available";
                                String notesSubs = tapernotes.replaceAll("\n", "<br/>");

                                tapernotesWebview.loadData(notesSubs, "text/html", null);
                            }  catch (JSONException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                            super.onFailure(statusCode, headers, throwable, errorResponse);
                        }
                    }
            );


        } else {
            rootView = inflater.inflate(R.layout.fragment_list, container, false);
        }

        mErrorView = rootView.findViewById(R.id.playback_error);
        mErrorMessage = mErrorView.findViewById(R.id.error_message);
        mProgressBar = rootView.findViewById(R.id.progress_bar);
        mProgressBar.setVisibility(View.VISIBLE);

        mBrowserAdapter = new BrowseAdapter(getActivity());

        listView = rootView.findViewById(R.id.list_view);
        listView.setAdapter(mBrowserAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            checkForUserVisibleErrors(false);
            MediaBrowserCompat.MediaItem item = mBrowserAdapter.getItem(position);
            mMediaFragmentListener.onMediaItemSelected(item);
        });

        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();

        // fetch browsing information to fill the listview:
        MediaBrowserCompat mediaBrowser = mMediaFragmentListener.getMediaBrowser();

        Timber.d("fragment.onStart, mediaId=%s onConnected=%s", mMediaId,
                mediaBrowser.isConnected());

        if (mediaBrowser.isConnected()) {
            onConnected();
        }

        // Registers BroadcastReceiver to track network connection changes.
        this.getActivity().registerReceiver(mConnectivityChangeReceiver,
            new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    public void onStop() {
        super.onStop();
        MediaBrowserCompat mediaBrowser = mMediaFragmentListener.getMediaBrowser();
        if (mediaBrowser != null && mediaBrowser.isConnected() && mMediaId != null) {
            mediaBrowser.unsubscribe(mMediaId);
        }
        MediaControllerCompat controller = ((BaseActivity) getActivity())
                .getSupportMediaController();
        if (controller != null) {
            controller.unregisterCallback(mMediaControllerCallback);
        }
        this.getActivity().unregisterReceiver(mConnectivityChangeReceiver);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mMediaFragmentListener = null;
    }

    public String getMediaId() {
        Bundle args = getArguments();
        if (args != null) {
            return args.getString(ARG_MEDIA_ID);
        }
        return null;
    }

    public String getTitle() {
        Bundle args = getArguments();
        if (args != null) {
            return args.getString(ARG_TITLE);
        }
        return null;
    }

    public String getSubTitle() {
        Bundle args = getArguments();
        if (args != null) {
            return args.getString(ARG_SUBTITLE);
        }
        return null;
    }

    public void setMediaId(String title, String subtitle, String mediaId) {
        Bundle args = new Bundle(3);
        args.putString(MediaBrowserFragment.ARG_MEDIA_ID, mediaId);
        args.putString(MediaBrowserFragment.ARG_TITLE, title);
        args.putString(MediaBrowserFragment.ARG_SUBTITLE, subtitle);
        setArguments(args);
    }

    // Called when the MediaBrowser is connected. This method is either called by the
    // fragment.onStart() or explicitly by the activity in the case where the connection
    // completes after the onStart()
    public void onConnected() {
        if (isDetached()) {
            return;
        }
        mMediaId = getMediaId();
        if (mMediaId == null) {
            mMediaId = mMediaFragmentListener.getMediaBrowser().getRoot();
        }
        updateTitle();

        // Unsubscribing before subscribing is required if this mediaId already has a subscriber
        // on this MediaBrowser instance. Subscribing to an already subscribed mediaId will replace
        // the callback, but won't trigger the initial callback.onChildrenLoaded.
        //
        // This is temporary: A bug is being fixed that will make subscribe
        // consistently call onChildrenLoaded initially, no matter if it is replacing an existing
        // subscriber or not. Currently this only happens if the mediaID has no previous
        // subscriber or if the media content changes on the service side, so we need to
        // unsubscribe first.
        mMediaFragmentListener.getMediaBrowser().unsubscribe(mMediaId);

        mMediaFragmentListener.getMediaBrowser().subscribe(mMediaId, mSubscriptionCallback);

        // Add MediaController callback so we can redraw the list when metadata changes:
        MediaControllerCompat controller = ((BaseActivity) getActivity())
                .getSupportMediaController();
        if (controller != null) {
            controller.registerCallback(mMediaControllerCallback);
        }
    }

    private void checkForUserVisibleErrors(boolean forceError) {
        boolean showError = forceError;
        // If offline, message is about the lack of connectivity:
        if (!NetworkHelper.isOnline(getActivity())) {
            mErrorMessage.setText(R.string.error_no_connection);
            showError = true;
        } else {
            // otherwise, if state is ERROR and metadata!=null, use playback state error message:
            MediaControllerCompat controller = ((BaseActivity) getActivity())
                    .getSupportMediaController();
            if (controller != null
                && controller.getMetadata() != null
                && controller.getPlaybackState() != null
                && controller.getPlaybackState().getState() == PlaybackStateCompat.STATE_ERROR
                && controller.getPlaybackState().getErrorMessage() != null) {
                mErrorMessage.setText(controller.getPlaybackState().getErrorMessage());
                showError = true;
            } else if (forceError) {
                // Finally, if the caller requested to show error, show a generic message:
                mErrorMessage.setText(R.string.error_loading_media);
                showError = true;
            }
        }
        mErrorView.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showError) mProgressBar.setVisibility(View.INVISIBLE);
        Timber.d("checkForUserVisibleErrors. forceError=%s  showError=%s  isOnline=%s", forceError,
            showError, NetworkHelper.isOnline(getActivity()));
    }

    private void updateTitle() {

        mMediaFragmentListener.updateDrawerToggle();


        if (mMediaId.startsWith(MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR)) {

            String year = MediaIDHelper.getHierarchy(mMediaId)[1];
            mMediaFragmentListener.setToolbarTitle(year);
            mMediaFragmentListener.setToolbarSubTitle("");
            return;
        }

        if (mMediaId.startsWith(MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW)) {

            mMediaFragmentListener.setToolbarTitle(getTitle());
            mMediaFragmentListener.setToolbarSubTitle(getSubTitle());
            return;
        }


        if (MediaIDHelper.MEDIA_ID_ROOT.equals(mMediaId)) {
            mMediaFragmentListener.setToolbarTitle(null);
            return;
        }

        MediaBrowserCompat mediaBrowser = mMediaFragmentListener.getMediaBrowser();
        mediaBrowser.getItem(mMediaId, new MediaBrowserCompat.ItemCallback() {
            @Override
            public void onItemLoaded(MediaBrowserCompat.MediaItem item) {
                mMediaFragmentListener.setToolbarTitle(
                        item.getDescription().getTitle());
            }
        });
    }

    // An adapter for showing the list of browsed MediaItem's
    private static class BrowseAdapter extends ArrayAdapter<MediaBrowserCompat.MediaItem> {

        BrowseAdapter(Activity context) {
            super(context, R.layout.media_list_item, new ArrayList<>());
        }

        @NotNull
        @Override
        public View getView(int position, View convertView, @Nullable ViewGroup parent) {

            MediaBrowserCompat.MediaItem item = getItem(position);
            int itemState = MediaItemViewHolder.STATE_NONE;
            if (item.isPlayable()) {
                itemState = MediaItemViewHolder.STATE_PLAYABLE;
                MediaControllerCompat controller = ((BaseActivity) getContext()).getSupportMediaController();
                if (controller != null && controller.getMetadata() != null) {
                    String currentPlaying = controller.getMetadata().getDescription().getMediaId();
                    String musicId = MediaIDHelper.extractMusicIDFromMediaID(item.getDescription().getMediaId());
                    if (currentPlaying != null && currentPlaying.equals(musicId)) {
                        PlaybackStateCompat pbState = controller.getPlaybackState();
                        if (pbState == null ||
                                pbState.getState() == PlaybackStateCompat.STATE_ERROR) {
                            itemState = MediaItemViewHolder.STATE_NONE;
                        } else if (pbState.getState() == PlaybackStateCompat.STATE_PLAYING) {
                            itemState = MediaItemViewHolder.STATE_PLAYING;
                        } else {
                            itemState = MediaItemViewHolder.STATE_PAUSED;
                        }
                    }
                }
            }


            return MediaItemViewHolder.setupView((Activity) getContext(), convertView, parent,
                item.getDescription(), itemState);
        }
    }

    public interface MediaFragmentListener extends MediaBrowserProvider {
        void onMediaItemSelected(MediaBrowserCompat.MediaItem item);
        void setToolbarTitle(CharSequence title);
        void setToolbarSubTitle(CharSequence title);
        void updateDrawerToggle();
    }

}
