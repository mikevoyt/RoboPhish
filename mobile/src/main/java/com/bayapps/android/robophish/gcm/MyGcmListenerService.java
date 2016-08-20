/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bayapps.android.robophish.gcm;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.bayapps.android.robophish.MusicService;
import com.bayapps.android.robophish.R;
import com.bayapps.android.robophish.ui.BaseActivity;
import com.bayapps.android.robophish.ui.MusicPlayerActivity;
import com.google.android.gms.gcm.GcmListenerService;

public class MyGcmListenerService extends GcmListenerService {

    private static final String TAG = "MyGcmListenerService";

    /**
     * Called when message is received.
     *
     * @param from SenderID of the sender.
     * @param data Data bundle containing message data as key/value pairs.
     *             For Set of keys use data.keySet().
     */
    // [START receive_message]
    @Override
    public void onMessageReceived(String from, Bundle data) {
        String title = data.getString("title");
        String subtitle = data.getString("subtitle");
        String mediaId = data.getString("mediaid");

        Log.d(TAG, "title: " + title);
        Log.d(TAG, "subtitle: " + subtitle);
        Log.d(TAG, "mediaId: " + mediaId);

        if (from.startsWith("/topics/")) {
            // message received from some topic.
        } else {
            // normal downstream message.
        }

        // [START_EXCLUDE]
        /**
         * Production applications would usually process the message here.
         * Eg: - Syncing with server.
         *     - Store message in local database.
         *     - Update UI.
         */

        /**
         * In some cases it may be useful to show a notification indicating to the user
         * that a message was received.
         */
        showNotification(title, subtitle, mediaId);
        // [END_EXCLUDE]
    }
    // [END receive_message]

    /**
     * Create and show a simple notification containing the received GCM message.
     *
     */
    private void showNotification(String title, String subtitle, String mediaId) {
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MusicPlayerActivity.EXTRA_START_FULLSCREEN, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

        //TEST
        intent.setAction(MediaStore.INTENT_ACTION_MEDIA_SEARCH);
        intent.putExtra("title", title);
        intent.putExtra("subtitle", subtitle);
        intent.putExtra("showid", "__TRACKS_BY_SHOW__/" + mediaId);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0 /* Request code */, intent,
                PendingIntent.FLAG_ONE_SHOT);



        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_allmusic_black_24dp)
                .setContentTitle("There's a new Phish show!")
                .setContentText(title + ", " + subtitle)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(pendingIntent);

        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        notificationManager.notify(Integer.parseInt(mediaId), notificationBuilder.build());
    }
}
