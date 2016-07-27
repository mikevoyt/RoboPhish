package com.example.android.uamp.model;

import android.support.v4.media.MediaMetadataCompat;
import com.example.android.uamp.utils.LogHelper;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Created by mikevoyt on 7/27/16.
 */

public class PhishProviderSource implements MusicProviderSource  {

    private static final String TAG = LogHelper.makeLogTag(PhishProviderSource.class);

    @Override
    public Iterator<MediaMetadataCompat> iterator() {
            ArrayList<MediaMetadataCompat> tracks = new ArrayList<>();
            return tracks.iterator();
    }
}
