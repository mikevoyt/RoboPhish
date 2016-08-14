package com.bayapps.android.robophish.model;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Created by mvoytovich on 1/13/15.
 */
public class ParseUtils {

    private static final String TAG = ParseUtils.class.getSimpleName();

    public static Show parseShow(String jsonString) {
        JSONObject json = null;
        try {
            json = new JSONObject(jsonString);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return parseShow(json);
    }

    public static Show parseShow(JSONObject json) {

        Show show = null;

        try {

            //parse 'data'
            JSONObject data = json.getJSONObject("data");
            show =  parseShowData(data);

        } catch (Exception e) {
            Log.d(TAG, "failed to parse show!");
            e.printStackTrace();
            return null;
        }

        return show;
    }

    private static Show parseShowData(JSONObject data) {

        JSONArray tracks = null;

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
                    Log.d(TAG, jsonTrack.toString());

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
            Log.d(TAG, "failed to parse show!");
            e.printStackTrace();
            return null;
        }

        return show;
    }

    public static ArrayList<YearData> parseYears(JSONObject json) {

        ArrayList<YearData> yearList = new ArrayList<>();

        try {
            //parse 'data' which contains the array of years
            JSONArray years = json.getJSONArray("data");
            for (int i = 0; i < years.length(); i++) {
                JSONObject entry = years.getJSONObject(i);
                String year = entry.getString("date");
                String showCount = entry.getString("show_count");
                YearData data = new YearData(year, showCount);
                yearList.add(data);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return yearList;
    }

    //parses basic data for array of shows, such as that obtained when retrieving all shows
    //for a given year with http://phish.in/api/v1/years/NNNN.json.
    //Returns an array of shows, with incomplete data (e.g., there are no tracks)
    public static ArrayList<Show> parseShows(String jsonString) {
        JSONObject json = null;
        try {
            json = new JSONObject(jsonString);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return parseShows(json);
    }

    public static ArrayList<Show> parseShows(JSONObject json) {

        ArrayList<Show> showsList = new ArrayList<Show>();

        try {
            //parse 'data' which contains the array of shows
            JSONArray shows = json.getJSONArray("data");
            for (int i = 0; i < shows.length(); i++) {
                Show show = parseShowData(shows.getJSONObject(i));
                showsList.add(show);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return showsList;
    }
}
