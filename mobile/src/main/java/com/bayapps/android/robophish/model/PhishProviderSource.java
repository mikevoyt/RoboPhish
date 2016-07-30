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
        mImages.add("http://intensityadvisors.com/wp-content/uploads/portfolio/movies/phish-3d-concert-experience/phish3d3.jpg");
        mImages.add("http://www.brooklynvegan.com/files/img/as/phish-msg-2015.jpg");
        //mImages.add("http://seatgeek.com/blog/wp-content/uploads/2011/06/phish-1stbank-center-tickets_6351.png1.jpeg");
        mImages.add("https://steveneudaly.files.wordpress.com/2012/08/20120829-200636.jpg");
        mImages.add("http://imgs.l4lmcdn.com/2013/09/phish-lights.jpg");
        mImages.add("http://images1.westword.com/imager/outside-the-phish-show-weed-is-for-sharin/u/original/6431248/phishcrowdshakedown.jpg");
        mImages.add("http://www.northcountrypublicradio.org/news/images/phish_watkins_glen.jpg");
        mImages.add("http://phishthoughts.com/wp-content/uploads/2010/08/Rogell_Telly1_1a.jpg");
        mImages.add("https://consequenceofsound.files.wordpress.com/2015/03/screen-shot-2015-03-18-at-2-49-51-pm.png?w=807");
        mImages.add("http://theconcertdatabase.com/sites/theconcertdatabase.com/files/phish.jpg");
        mImages.add("http://media.tumblr.com/5a2b9964eeb04fa3a4f6c6e204312932/tumblr_inline_mley97bZIq1qz4rgp.jpg");
        mImages.add("https://s3.amazonaws.com/ssglobalcdn/performers/wide/phish.jpg");
        mImages.add("http://images1.villagevoice.com/imager/u/original/8104637/trey-anastasio-phish-msg-credit-jason-speakman-village-voice.jpg");
        mImages.add("http://media.philly.com/images/600*450/Phish_08.11.15-3.jpg");
        mImages.add("http://image.syracuse.com/home/syr-media/width960/img/post-standard/photo/2016/07/10/-f93d34cacda62e7e.JPG");
        mImages.add("http://cdn1-www.craveonline.com/assets/uploads/2014/04/phish-1.jpg");
        mImages.add("http://www.kansascity.com/entertainment/ent-columns-blogs/back-to-rockville/v4lqdg/picture30213282/ALTERNATES/FREE_640/Phish%20FY%20080515%20rs%200651f");
        mImages.add("http://static.bangordailynews.com/wp-content/uploads/2013/07/10076301_H10809241-600x436.jpg?strip=all");
        mImages.add("http://smedia.pnet-static.com/img/NYE.JPG");
        mImages.add("http://imgs.l4lmcdn.com/Phish%20Mayan%20Riviera_20160116_DSC05185.jpg");
        mImages.add("http://imgs.l4lmcdn.com/hish%20Mayan%20Riviera_20160116__03A8903.jpg");
        mImages.add("http://www.jambase.com/wp-content/uploads/2015/06/Phish-1480x832.jpg");
        mImages.add("https://i.ytimg.com/vi/shK8B1iKNfQ/maxresdefault.jpg");
        mImages.add("http://assets.noisey.com/content-images/contentimage/35342/P0ThI3i.jpg");
        mImages.add("http://www.sevendaysvt.com/general/multimedia/interactives/110613_phish/img/phish4.jpg");
        mImages.add("http://phishthoughts.com/wp-content/uploads/2012/08/DSC_0641.jpg");
        mImages.add("http://cdn.phillymag.com/wp-content/uploads/2015/08/DSC08938.jpg");
        mImages.add("http://cdn.phillymag.com/wp-content/uploads/2014/07/phish-banner.png");
        mImages.add("https://i.ytimg.com/vi/ZWQ24h3k69E/maxresdefault.jpg");
        mImages.add("http://livemusicblog.com/wp-content/uploads/2015/01/IMG_1329.jpg");
        mImages.add("https://i.ytimg.com/vi/shK8B1iKNfQ/maxresdefault.jpg");
        mImages.add("http://phish.com/wp-content/uploads/2015/08/08-02-15_DPV_8100_Phish_Tuscaloosa_AL_by_Dave_Vann-452x301.jpg");
        mImages.add("https://pbs.twimg.com/profile_images/578591325949284352/SiA_0myv.jpeg");
        mImages.add("http://www.jazzmusicarchives.com/images/artists/phish-20120704124659.jpg");
        mImages.add("http://trey.com/wp-content/uploads/2012/12/phish1.jpg");
        mImages.add("http://onlinephishtour.com/wp-content/uploads/2015/07/CK0tz79UEAAMGfZ.jpg");
        mImages.add("http://treethugger.com/wp-content/uploads/2013/02/phish-2013-breakup-rumors-tour-dates.jpg");
        mImages.add("http://america.pink/images/3/5/0/0/2/0/8/en/3-phish-tours.jpg");
        mImages.add("http://imgs.l4lmcdn.com/phish_zdDO2_1018.jpg");
        mImages.add("http://images.popmatters.com/reviews_art/p/phish2009.jpg");
        mImages.add("http://phishthoughts.com/wp-content/uploads/2015/02/photo-2-3.jpg");
        mImages.add("https://uproxx.files.wordpress.com/2015/10/phish.jpg?quality=90&w=650");
        mImages.add("http://phishthoughts.com/wp-content/uploads/2014/11/692A5111.jpg");
        mImages.add("http://phishthoughts.com/wp-content/uploads/2011/01/3.jpeg");
        mImages.add("https://i.ytimg.com/vi/Tdcl8KSr9mc/maxresdefault.jpg");
        mImages.add("http://blog.wcgworld.com/wp-content/uploads/2014/08/Phish1.jpg");
        mImages.add("http://2ab9pu2w8o9xpg6w26xnz04d.wpengine.netdna-cdn.com/wp-content/uploads/2016/06/fishchi-screenshot-980x541.jpg");
        mImages.add("https://pbs.twimg.com/profile_images/73181757/phish1990_400x400.jpg");
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
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, show.getVenueName())
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
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.getTitle())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.getDurationString())
                                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, show.getDateSimple())
                                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.getDurationString())
                                .putString(MediaMetadataCompat.METADATA_KEY_COMPILATION, showId)
                                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, image)
                                .build());
                    }
                }
            }
        });

        return tracks;
    }

}
