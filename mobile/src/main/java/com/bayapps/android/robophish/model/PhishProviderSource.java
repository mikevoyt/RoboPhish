package com.bayapps.android.robophish.model;

import android.support.v4.media.MediaMetadataCompat;
import android.util.Log;

import com.bayapps.android.robophish.utils.LogHelper;
import com.google.common.collect.Lists;
import com.loopj.android.http.*;
import org.json.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

import cz.msebera.android.httpclient.Header;

/**
 * Created by mikevoyt on 7/27/16.
 */

public class PhishProviderSource implements MusicProviderSource  {

    private static ArrayList<String> mImages = new ArrayList<>();
    private Random mRandomGenerator;
    private static final String TAG = LogHelper.makeLogTag(PhishProviderSource.class);

    PhishProviderSource() {
        mRandomGenerator = new Random();
        //add some random images to display
        //Images used with permission (c) Andrea Nusinov, AZN Photography www.instagram.com/aznpics
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13732130_277319952649047_849294057_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13743448_1424000554280417_912332374_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13712772_641757285973222_527297489_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/s750x750/sh0.08/e35/13686862_1661410967519458_575510883_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13696381_596811543812270_718415472_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13628089_520932384778359_1628447580_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13732257_934099963365955_157717453_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13774432_598410456995460_1883253030_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13671286_1738702579712984_1828007626_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13725783_1784029171816866_1135447234_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13694461_1746810545586939_1641430204_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13649203_148077172263735_1989313152_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13597766_512279878963643_508373685_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13651695_268150956894013_1993777333_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13551840_952015738244562_751260068_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13556851_628850483957932_521834997_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13561960_600855040088485_20488278_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13551751_287919871562463_632078807_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13573427_297801313942007_1545221600_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13573538_1583041068663702_1520971806_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13549372_102349943533649_1364815772_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13557337_529348170606610_854510542_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13549347_508619289348001_1212737386_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13422833_1757416541208167_221982649_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13402244_1107858905968328_797946278_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13473229_1583944085237525_1319394460_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13381249_1047654198659884_1615737703_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13381193_622176694615237_375729107_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13391267_1025370187541825_410877554_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13249932_286351681704740_1911431023_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13249877_1713730188914408_1427093185_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13167326_1676536365945367_1939985831_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13129389_1607174089597163_64056422_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/e35/13099145_1618175438508664_747258233_n.jpg");
        mImages.add("https://instagram.fsnc1-1.fna.fbcdn.net/t51.2885-15/s750x750/sh0.08/e35/13092312_223483031360791_69669016_n.jpg");

    }

    @Override
    public ArrayList<String> years() {

        final ArrayList<String> years = new ArrayList<>();

        HttpClient.get("years.json", null, new JsonHttpResponseHandler() {

            @Override
            public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                ArrayList<String> yearStrings = ParseUtils.parseYears(response);

                Collections.reverse(yearStrings);

                if (yearStrings != null) {
                    for (String year : yearStrings) {

                        LogHelper.d(TAG, "year: ", year);

                        years.add(year);
                    }
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
                        LogHelper.w(TAG, "date: ", show.getDateSimple());
                        LogHelper.w(TAG, "venue: ", show.getVenueName());

                        String id = String.valueOf(show.getId());

                        shows.add(new MediaMetadataCompat.Builder()
                                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                                .putString(MediaMetadataCompat.METADATA_KEY_DATE, show.getDateSimple())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, show.getVenueName())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, show.getLocation())
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
                        Log.d(TAG, "name: " + track.getTitle());
                        String image = getRandomImage();
                        Log.d(TAG, "image: " + image);

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
