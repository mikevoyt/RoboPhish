package com.bayapps.android.robophish.utils;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.bayapps.android.robophish.model.ParseUtils;
import com.bayapps.android.robophish.model.Show;
import com.bayapps.android.robophish.model.Track;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

import cz.msebera.android.httpclient.Header;

/**
 * Created by mikevoyt on 8/13/16.
 */

public class Downloader {

    private static final String TAG = LogHelper.makeLogTag(Downloader.class);
    private static ArrayList<Long> mDownloadIds = new ArrayList<>();


    private String downloadCompleteIntentName = DownloadManager.ACTION_DOWNLOAD_COMPLETE;
    private IntentFilter downloadCompleteIntentFilter = new IntentFilter(downloadCompleteIntentName);

    public Downloader(Context context, String showId, JSONObject showData) {
        context.registerReceiver(downloadCompleteReceiver, downloadCompleteIntentFilter);


        Show show = ParseUtils.parseShow(showData);
        String title = show.getDateSimple() + ": " + show.getVenueName() + ", " + show.getLocation();
        ArrayList<String> urls = new ArrayList<>();

        for (Track track : show.getTracks()) {
            String url = track.getUrl();
            urls.add(url);

            /*
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

            request.setTitle(title);
            request.setDescription("Downloading " + title);
            Log.d(TAG, "downloading " + url);

            request.setVisibleInDownloadsUi(true);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(context, null, url);

            // enqueue this request
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            long id = downloadManager.enqueue(request);
            mDownloadIds.add(id);
            */
        }

        String url = urls.get(0);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));

        request.setTitle(title);
        request.setDescription("Downloading " + title);
        Log.d(TAG, "downloading " + url);

        request.setVisibleInDownloadsUi(true);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, null, url);

        // enqueue this request
        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = downloadManager.enqueue(request);
        mDownloadIds.add(id);

    }

    private BroadcastReceiver downloadCompleteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L);
            if (!mDownloadIds.contains(id)) {
                Log.v(TAG, "Ingnoring unrelated download " + id);
                return;
            }

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(id);
            Cursor cursor = downloadManager.query(query);

            // it shouldn't be empty, but just in case
            if (!cursor.moveToFirst()) {
                Log.e(TAG, "Empty row");
                return;
            }

            int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
            if (DownloadManager.STATUS_SUCCESSFUL != cursor.getInt(statusIndex)) {
                Log.w(TAG, "Download Failed");
                return;
            }

            int uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
            String downloadedPackageUriString = cursor.getString(uriIndex);
            Log.d(TAG, "downloaded " + downloadedPackageUriString);
        }
    };
}
