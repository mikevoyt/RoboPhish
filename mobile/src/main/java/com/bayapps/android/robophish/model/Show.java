package com.bayapps.android.robophish.model;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Created by mvoytovich on 1/13/15.
 */
public class Show {
    private long mId;
    private Date mDate;
    private long mDuration;
    private boolean mSbd;
    private long mTourId;
    private String mVenueName;
    private String mLocation;
    private String mTaperNotes;
    private ArrayList<Track> mTracks;

    public Show() {
        this.mTracks = new ArrayList<>();
    }

    public long getId() {
        return mId;
    }

    public void setId(long id) {
        mId = id;
    }

    public Date getDate() {
        return mDate;
    }

    public String getDateSimple() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        return dateFormat.format(mDate);
    }

    public void setDate(Date date) {
        mDate = date;
    }

    public long getDuration() {
        return mDuration;
    }

    public void setDuration(long duration) {
        mDuration = duration;
    }

    public boolean isSbd() {
        return mSbd;
    }

    public void setSbd(boolean sbd) {
        mSbd = sbd;
    }

    public long getTourId() {
        return mTourId;
    }

    public void setTourId(long tourId) {
        mTourId = tourId;
    }

    public String getVenueName() {
        return mVenueName;
    }

    public void setVenueName(String venueName) {
        mVenueName = venueName;
    }

    public String getLocation() {
        return mLocation;
    }

    public void setLocation(String location) {
        mLocation = location;
    }

    public String getTaperNotes() {
        return mTaperNotes;
    }

    public void setTaperNotes(String taperNotes) {
        mTaperNotes = taperNotes;
    }

    public ArrayList<Track> getTracks() {
        return mTracks;
    }

    //index is 0 based (i.e., the meta-data 'position' minus 1)
    public Track getTrack(int index) {
        return mTracks.get(index);
    }

    public void addTrack(Track track) {
        mTracks.add(track);
    }
}
