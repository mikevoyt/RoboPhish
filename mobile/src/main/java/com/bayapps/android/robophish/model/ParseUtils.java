package com.bayapps.android.robophish.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;

import timber.log.Timber;

/**
 * Created by mvoytovich on 1/13/15.
 */
public class ParseUtils {

    public static Show parseShow(JSONObject json) {

        Show show;

        try {

            //parse 'data'
            JSONObject data = json.getJSONObject("data");
            show =  parseShowData(data);

        } catch (Exception e) {
            Timber.d("failed to parse show!");
            e.printStackTrace();
            return null;
        }

        return show;
    }

    private static Show parseShowData(JSONObject data) {

        JSONArray tracks;

        Show show = new Show();

        try {

            //parse 'data'
            Long showId = data.getLong("id");
            show.setId(showId);

            String dateString = data.getString("date"); //formatted as YYYY-MM-DD
            Date date = new SimpleDateFormat("yyyy-MM-dd").parse(dateString);
            show.setDate(date);

            JSONObject venue = data.optJSONObject("venue");
            if (venue != null) {
                //full show data contains a venue object
                String venueName = venue.getString("name");
                show.setVenueName(venueName);

                String location = venue.getString("location");
                show.setLocation(location);

            } else {
                String venueName = data.getString("venue_name");
                show.setVenueName(venueName);

                String location = data.getString("location");
                show.setLocation(location);
            }

            //parse 'tracks' if available (if this is more that "simple data")
            tracks = data.optJSONArray("tracks");

            if (tracks != null) {
                for (int i = 0; i < tracks.length(); i++) {
                    JSONObject jsonTrack = tracks.getJSONObject(i);
                    Timber.d(jsonTrack.toString());

                    int id = jsonTrack.getInt("id");
                    String title = jsonTrack.getString("title");
                    String url = jsonTrack.getString("mp3");

                    long duration = jsonTrack.getLong("duration");

                    Track track = new Track(id, title, url);
                    track.setDuration(duration);

                    show.addTrack(track);
                }
            }

            String taperNotes = data.getString("taper_notes");
            if (taperNotes != null) {
                show.setTaperNotes(taperNotes);
            }

            boolean sbd = data.getBoolean("sbd");
            show.setSbd(sbd);

        } catch (Exception e) {
            Timber.d("failed to parse show!");
            e.printStackTrace();
            return null;
        }

        return show;
    }
}
