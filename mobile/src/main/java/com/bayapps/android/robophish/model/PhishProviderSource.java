package com.bayapps.android.robophish.model;

import android.support.v4.media.MediaMetadataCompat;

import com.loopj.android.http.*;
import org.json.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import cz.msebera.android.httpclient.Header;
import timber.log.Timber;

/**
 * Created by mikevoyt on 7/27/16.
 */
public class PhishProviderSource implements MusicProviderSource  {

    private static ArrayList<String> mImages = new ArrayList<>();
    private Random mRandomGenerator;

    PhishProviderSource() {
        mRandomGenerator = new Random();
        //add some random images to display

        //Images used with permission (c) Jason Guss, James Bryan, Mike Rambo, Evan Krohn
        mImages.add("https://i.imgur.com/qhqUJWh.jpg");
        mImages.add("https://i.imgur.com/uvxpvkQ.jpg");
        mImages.add("https://i.imgur.com/AHls6H6.jpg");
        mImages.add("https://i.imgur.com/uxJVage.jpg");
        mImages.add("https://i.imgur.com/u4ShCFi.jpg");
        mImages.add("https://i.imgur.com/Fywi5vG.jpg");
        mImages.add("https://i.imgur.com/aZrqwDT.jpg");
        mImages.add("https://i.imgur.com/AECeOue.jpg");
        mImages.add("https://i.imgur.com/MHGKZqL.jpg");
        mImages.add("https://i.imgur.com/Oi3DnPt.jpg");
        mImages.add("https://i.imgur.com/FsfazYF.jpg");
        mImages.add("https://i.imgur.com/LAT9Q16.jpg");
        mImages.add("https://i.imgur.com/bhkGDLT.jpg");

        //Images used with permission (c) Andrea Nusinov, AZN Photography
        //www.instagram.com/aznpics
        mImages.add("https://i.imgur.com/XAp4BUA.jpg");
        mImages.add("https://i.imgur.com/9qRlnMl.jpg");
        mImages.add("https://i.imgur.com/LTTNpG6.jpg");
        mImages.add("https://i.imgur.com/Iv9EGzr.jpg");
        mImages.add("https://i.imgur.com/MYuqi0Z.jpg");
        mImages.add("https://i.imgur.com/PQfiuPy.jpg");
        mImages.add("https://i.imgur.com/YBRbMYv.jpg");
        mImages.add("https://i.imgur.com/GJyOac0.jpg");
        mImages.add("https://i.imgur.com/v3wsHr0.jpg");
        mImages.add("https://i.imgur.com/WzcS3Sc.jpg");
        mImages.add("https://i.imgur.com/ej1eiy1.jpg");
        mImages.add("https://i.imgur.com/ZYA1ilw.jpg");
        mImages.add("https://i.imgur.com/Fi9TXU1.jpg");
        mImages.add("https://i.imgur.com/2WN1PBC.jpg");
        mImages.add("https://i.imgur.com/ZukejZd.jpg");
        mImages.add("https://i.imgur.com/uPohYjt.jpg");
        mImages.add("https://i.imgur.com/MEYqCKP.jpg");
        mImages.add("https://i.imgur.com/2UnuEMB.jpg");
        mImages.add("https://i.imgur.com/TanyMAh.jpg");
        mImages.add("https://i.imgur.com/sSCeoR3.jpg");
        mImages.add("https://i.imgur.com/YYLy4hd.jpg");
        mImages.add("https://i.imgur.com/84i9wEt.jpg");
        mImages.add("https://i.imgur.com/p3My2pv.jpg");
        mImages.add("https://i.imgur.com/6aG3Wdr.jpg");
        mImages.add("https://i.imgur.com/owGLpm2.jpg");
        mImages.add("https://i.imgur.com/1HN6ifl.jpg");
        mImages.add("https://i.imgur.com/6QPf69F.jpg");
        mImages.add("https://i.imgur.com/TX1Aok3.jpg");
        mImages.add("https://i.imgur.com/ha1doJI.jpg");

        //Images used with permission (c) David Logan, David Logan Photography
        //https://www.flickr.com/photos/davidloganphotography
        mImages.add("https://c2.staticflickr.com/8/7428/9407135018_df133c9c28_b.jpg");
        mImages.add("https://c2.staticflickr.com/6/5344/9407138068_2d4f876588_b.jpg");
        mImages.add("https://c1.staticflickr.com/3/2827/9407148598_3b53cc5e1b_b.jpg");
        mImages.add("https://c2.staticflickr.com/4/3805/9404388499_6b446a4e0c_b.jpg");
        mImages.add("https://c2.staticflickr.com/6/5327/9407144032_01fb41745c_b.jpg");
        mImages.add("https://c2.staticflickr.com/8/7365/9404384233_75eebb6e76_b.jpg");
        mImages.add("https://c1.staticflickr.com/9/8229/8394959099_d4b1d30017_b.jpg");
        mImages.add("https://c1.staticflickr.com/9/8361/8394958861_af3d802945_b.jpg");
        mImages.add("https://c1.staticflickr.com/9/8189/8394665046_0c6a426395_b.jpg");
        mImages.add("https://c1.staticflickr.com/3/2478/3654332535_c3826a066b_b.jpg");
        mImages.add("https://c2.staticflickr.com/4/3359/3655135680_276263f359_b.jpg");
        mImages.add("https://c1.staticflickr.com/3/2443/3655134046_d9f4697a4a_b.jpg");
        mImages.add("https://c1.staticflickr.com/3/2776/4151295029_abc36c90c4_b.jpg");
    }

    @Override
    public ArrayList<YearData> years() {

        final ArrayList<YearData> years = new ArrayList<>();

        RequestParams yearsParams = new RequestParams();
        yearsParams.put("include_show_counts", "true");
        HttpClient.get("years.json", yearsParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                ArrayList<YearData> yearEntries = ParseUtils.parseYears(response);

                Collections.reverse(yearEntries);

                for (YearData year : yearEntries) {

                    Timber.d("year: %s", year.getYear());
                    years.add(year);
                }
            }
        });

        return years;
    }

    public Iterable<MediaMetadataCompat> showsInYear(String year) {
        final ArrayList<MediaMetadataCompat> shows = new ArrayList<>();

        HttpClient.get("years/" + year + ".json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                ArrayList<Show> showsArray = ParseUtils.parseShows(response);

                if (showsArray != null) {
                    for (Show show: showsArray) {
                        Timber.w("date: %s", show.getDateSimple());
                        Timber.w("venue: %s", show.getVenueName());

                        String id = String.valueOf(show.getId());

                        shows.add(new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                                .putString(MediaMetadataCompat.METADATA_KEY_DATE, show.getDateSimple())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, show.getVenueName())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, show.getLocation())

                                //we're using 'Author' here for taper notes
                                .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, show.getTaperNotes())
                                .build());
                    }
                }
            }
        });

        Collections.reverse(shows);
        return shows;
    }

    private String getRandomImage() {

        int index = mRandomGenerator.nextInt(mImages.size());
        String image = mImages.get(index);
        return  image;
    }

    public Iterable<MediaMetadataCompat> tracksInShow(final String showId) {

        final ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();

        HttpClient.get("shows/" + showId + ".json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                Show show = ParseUtils.parseShow(response);

                if (show != null) {
                    for (Track track: show.getTracks()) {
                        Timber.d("name: %s", track.getTitle());
                        String image = getRandomImage();
                        Timber.d("image: %s", image);

                        String id = String.valueOf(track.getId());

                        // Adding the music source to the MediaMetadata (and consequently using it in the
                        // mediaSession.setMetadata) is not a good idea for a real world music app, because
                        // the session metadata can be accessed by notification listeners. This is done in this
                        // sample for convenience only.
                        //noinspection ResourceType
                        tracks.add(new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                                .putString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE, track.getUrl())
                                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.getDuration())
                                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.getTitle())

                                //pretty hokey, but we're overloading these fields so we can save venue and location, and showId
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, show.getVenueName())
                                .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, show.getLocation())
                                .putString(MediaMetadataCompat.METADATA_KEY_COMPILATION, showId)

                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.getTitle())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.getDurationString())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, show.getDateSimple())
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getDurationString())
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, image)
                                .build());
                    }
                }
            }
        });

        return tracks;
    }

}
