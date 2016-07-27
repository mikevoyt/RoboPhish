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

    @Override
    public Iterator<MediaMetadataCompat> iterator() {

        final ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();

        HttpClient.get("years.json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                ArrayList<String> mYears = ParseUtils.parseYears(response);

                if (mYears != null) {
                    for (String year : mYears) {
                        tracks.add(buildFromYear(year));
                    }
                }
            }
        });


        return tracks.iterator();
    }

    private MediaMetadataCompat buildFromYear(String year) {

        // Since we don't have a unique ID in the server, we fake one using the hashcode of
        // the music source. In a real world app, this could come from the server.
        String id = String.valueOf(year.hashCode());

        // Adding the music source to the MediaMetadata (and consequently using it in the
        // mediaSession.setMetadata) is not a good idea for a real world music app, because
        // the session metadata can be accessed by notification listeners. This is done in this
        // sample for convenience only.
        //noinspection ResourceType
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, year)
                .build();
    }

}
