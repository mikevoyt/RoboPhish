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
    private ArrayList<String> mYears;
    private ArrayList<Show> mShows;


    @Override
    public Iterator<MediaMetadataCompat> iterator() {

        final ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();

        HttpClient.get("years.json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                mYears = ParseUtils.parseYears(response);

                if (mYears != null) {
                    for (String year : mYears) {
                        tracks.add(buildFromYear(year));
                    }
                }
            }
        });

        if (mYears != null) {
            for (final String year : mYears) {
                HttpClient.get("years/" + year + ".json", null, new JsonHttpResponseHandler() {

                    @Override
                    public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                        mShows = ParseUtils.parseShows(response);

                        if (mShows != null) {
                            for (Show show: mShows) {
                                LogHelper.w(TAG, "date: ", show.getDateSimple());
                                LogHelper.w(TAG, "venue: ", show.getVenueName());

                                for (Track track: show.getTracks()) {
                                    tracks.add(buildFromTrack(year, show, track));
                                }
                            }
                        }
                    }
                });
            }
        }


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
                .putString(MediaMetadataCompat.METADATA_KEY_COMPILATION, year)
                .build();
    }

    private MediaMetadataCompat buildFromTrack(String year, Show show, Track track) {

        String id = String.valueOf(track.getId());

        // Adding the music source to the MediaMetadata (and consequently using it in the
        // mediaSession.setMetadata) is not a good idea for a real world music app, because
        // the session metadata can be accessed by notification listeners. This is done in this
        // sample for convenience only.
        //noinspection ResourceType

        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE, track.getUrl())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.getDuration())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, show.getVenueName())
                .putString(MediaMetadataCompat.METADATA_KEY_COMPILATION, year)
                .putString(MediaMetadataCompat.METADATA_KEY_DATE, show.getDate().toString())
                .build();
    }

}
