package com.example.android.uamp.model;

import android.support.v4.media.MediaMetadataCompat;
import com.example.android.uamp.utils.LogHelper;
import com.loopj.android.http.*;
import org.json.*;

import java.util.ArrayList;
import java.util.Iterator;

import cz.msebera.android.httpclient.Header;

/**
 * Created by mikevoyt on 7/27/16.
 */

public class PhishProviderSource implements MusicProviderSource  {

    private static final String TAG = LogHelper.makeLogTag(PhishProviderSource.class);
    ArrayList<String> mYears;

    @Override
    public Iterator<MediaMetadataCompat> iterator() {
            ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();
            return tracks.iterator();
    }


    private void fetchYears() {
        HttpClient.get("years.json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                mYears = ParseUtils.parseYears(response);
            }

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONArray timeline) {
            }
        });

    }
}
