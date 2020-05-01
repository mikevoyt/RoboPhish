package com.bayapps.android.robophish.model;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Created by mvoytovich on 1/13/15.
 */
public class Track {
    private long mId;
    private String mTitle;
    private long mDuration;
    private String mSet;
    private String mSetName;
    private String mUrl;
    private ArrayList<Integer> mSongIds;

    public Track(long trackId, String trackTitle, String trackUrl) {
        this.mId = trackId;
        this.mTitle = trackTitle;
        String[] parts = trackUrl.split("https://");
        this.mUrl = "http://" + parts[1];
    }

    public void setId(long id) {
        mId = id;
    }

    public long getId() {
        return mId;
    }

    public void setTitle(String title) {
        mTitle = title;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setUrl(String url) {
        mUrl = url;
    }

    public String getUrl() {
        return mUrl;
    }

    public void setDuration(long duration) {
        mDuration = duration;
    }

    public long getDuration() {
        return mDuration;
    }

    public String getDurationString() {
        String timestamp = String.format("%d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(mDuration),
                TimeUnit.MILLISECONDS.toSeconds(mDuration) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(mDuration))
        );
    return timestamp;
    }

    public void setSet(String set) {
        mSet = set;
    }

    public String getSet() {
        return mSet;
    }

    public void setSetName(String setName) {
        mSetName = setName;
    }

    public String getSetName() {
        return mSetName;
    }

}
