/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bayapps.android.robophish

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.RemoteException
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bayapps.android.robophish.ui.MusicPlayerActivity
import com.squareup.picasso.Picasso
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.IOException

private const val NOTIFICATION_ID = 412
private const val REQUEST_CODE = 100

/**
 * Keeps track of a notification and updates it automatically for a given
 * MediaSession. Maintaining a visible notification (usually) guarantees that the music service
 * won't be killed during playback.
 */
class MediaNotificationManager(
        private val musicService: MusicService,
        private val picasso: Picasso,
        private val notificationManagerCompat: NotificationManagerCompat
) : BroadcastReceiver() {

    private var sessionToken: MediaSessionCompat.Token? = null
    private var controller: MediaControllerCompat? = null
    private var mTransportControls: MediaControllerCompat.TransportControls? = null
    private var mPlaybackState: PlaybackStateCompat? = null
    private var mMetadata: MediaMetadataCompat? = null


    private val pauseIntent = PendingIntent.getBroadcast(
            musicService,
            REQUEST_CODE,
            Intent(ACTION_PAUSE).setPackage(musicService.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val playIntent = PendingIntent.getBroadcast(
            musicService,
            REQUEST_CODE,
            Intent(ACTION_PLAY).setPackage(musicService.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val previousIntent = PendingIntent.getBroadcast(
            musicService,
            REQUEST_CODE,
            Intent(ACTION_PREV).setPackage(musicService.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val nextIntent = PendingIntent.getBroadcast(
            musicService,
            REQUEST_CODE,
            Intent(ACTION_NEXT).setPackage(musicService.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    private val stopCastIntent = PendingIntent.getBroadcast(
            musicService,
            REQUEST_CODE,
            Intent(ACTION_STOP_CASTING).setPackage(musicService.packageName),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private val notificationColor: Int = ContextCompat.getColor(musicService, R.color.primaryColor)

    private var mStarted = false

    init {
        updateSessionToken()

        // Cancel all notifications to handle the case where the Service was killed and
        // restarted by the system.
        notificationManagerCompat.cancelAll()
    }

    /**
     * Posts the notification and starts tracking the session to keep it
     * updated. The notification will automatically be removed if the session is
     * destroyed before [.stopNotification] is called.
     */
    suspend fun startNotification() {
        if (!mStarted) {
            mMetadata = controller!!.metadata
            mPlaybackState = controller!!.playbackState
            // The notification must be updated after setting started to true
            val notification = createNotification()
            if (notification != null) {
                controller!!.registerCallback(mCb)
                val filter = IntentFilter()
                filter.addAction(ACTION_NEXT)
                filter.addAction(ACTION_PAUSE)
                filter.addAction(ACTION_PLAY)
                filter.addAction(ACTION_PREV)
                filter.addAction(ACTION_STOP_CASTING)
                if (Build.VERSION.SDK_INT >= 33) {
                    musicService.registerReceiver(
                        this@MediaNotificationManager,
                        filter,
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    musicService.registerReceiver(this@MediaNotificationManager, filter)
                }
                musicService.startForeground(NOTIFICATION_ID, notification)
                mStarted = true
            }
        }
    }

    /**
     * Removes the notification and stops tracking the session. If the session
     * was destroyed this has no effect.
     */
    fun stopNotification() {
        if (mStarted) {
            mStarted = false
            controller!!.unregisterCallback(mCb)
            try {
                notificationManagerCompat.cancel(NOTIFICATION_ID)
                musicService.unregisterReceiver(this)
            } catch (ex: IllegalArgumentException) { // ignore if the receiver is not registered.
            }
            musicService.stopForeground(true)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.d("Received intent with action %s", action)
        when (action) {
            ACTION_PAUSE -> mTransportControls!!.pause()
            ACTION_PLAY -> mTransportControls!!.play()
            ACTION_NEXT -> mTransportControls!!.skipToNext()
            ACTION_PREV -> mTransportControls!!.skipToPrevious()
            ACTION_STOP_CASTING -> {
                val i = Intent(context, MusicService::class.java)
                i.action = MusicService.ACTION_CMD
                i.putExtra(MusicService.CMD_NAME, MusicService.CMD_STOP_CASTING)
                musicService.startService(i)
            }
            else -> Timber.w("Unknown intent ignored. Action=%s", action)
        }
    }

    /**
     * Update the state based on a change on the session token. Called either when
     * we are running for the first time or when the media session owner has destroyed the session
     * (see [android.media.session.MediaController.Callback.onSessionDestroyed])
     */
    private fun updateSessionToken() {
        val freshToken = musicService.sessionToken
        if (sessionToken == null && freshToken != null ||
                sessionToken != null && sessionToken != freshToken) {
            if (controller != null) {
                controller!!.unregisterCallback(mCb)
            }
            sessionToken = freshToken
            if (sessionToken != null) {
                controller = MediaControllerCompat(musicService, sessionToken!!)
                mTransportControls = controller!!.transportControls
                if (mStarted) {
                    controller!!.registerCallback(mCb)
                }
            }
        }
    }

    private fun createContentIntent(description: MediaDescriptionCompat?): PendingIntent {
        val openUI = Intent(musicService, MusicPlayerActivity::class.java)
        openUI.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        openUI.putExtra(MusicPlayerActivity.EXTRA_START_FULLSCREEN, true)
        if (description != null) {
            openUI.putExtra(MusicPlayerActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION, description)
        }
        return PendingIntent.getActivity(
            musicService,
            REQUEST_CODE,
            openUI,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val mCb: MediaControllerCompat.Callback = object : MediaControllerCompat.Callback(), CoroutineScope by MainScope() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat) {
            launch {
                mPlaybackState = state
                Timber.d("Received new playback state %s", state)
                if (state.state == PlaybackStateCompat.STATE_STOPPED ||
                        state.state == PlaybackStateCompat.STATE_NONE) {
                    stopNotification()
                } else {
                    val notification = createNotification()
                    if (notification != null) {
                        notificationManagerCompat.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat) {
            launch {
                mMetadata = metadata
                Timber.d("Received new metadata %s", metadata)
                val notification = createNotification()
                if (notification != null) {
                    notificationManagerCompat.notify(NOTIFICATION_ID, notification)
                }
            }
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Timber.d("Session was destroyed, resetting to the new session token")
            try {
                updateSessionToken()
            } catch (e: RemoteException) {
                Timber.e(e, "could not connect media controller")
            }
            cancel()
        }
    }

    private suspend fun createNotification(): Notification? {
        Timber.d("updateNotificationMetadata. mMetadata=%s", mMetadata)
        if (mMetadata == null || mPlaybackState == null) {
            return null
        }
        val notificationBuilder = NotificationCompat.Builder(musicService, "RoboPhish")
        var playPauseButtonPosition = 0
        // If skip to previous action is enabled
        if (mPlaybackState!!.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) {
            notificationBuilder.addAction(R.drawable.ic_skip_previous_white_24dp,
                    musicService.getString(R.string.label_previous), previousIntent)
            // If there is a "skip to previous" button, the play/pause button will
            // be the second one. We need to keep track of it, because the MediaStyle notification
            // requires to specify the index of the buttons (actions) that should be visible
            // when in compact view.
            playPauseButtonPosition = 1
        }
        addPlayPauseAction(notificationBuilder)
        // If skip to next action is enabled
        if (mPlaybackState!!.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) {
            notificationBuilder.addAction(R.drawable.ic_skip_next_white_24dp,
                    musicService.getString(R.string.label_next), nextIntent)
        }
        val description = mMetadata!!.description
        var art: Bitmap? = null
        if (description.iconUri != null) {
            // This sample assumes the iconUri will be a valid URL formatted String, but
            // it can actually be any valid Android Uri formatted String.
            // async fetch the album art icon
            val artUrl = description.iconUri.toString()

            val job = CoroutineScope(Dispatchers.IO).launch {
                art = // use a placeholder art while the remote art is being downloaded
                        withContext(Dispatchers.Default) {
                            try {
                                picasso.load(artUrl)
                                        .placeholder(R.drawable.ic_default_art)
                                        .error(R.drawable.ic_default_art)
                                        .get()
                            } catch (e: IOException) {
                                // use a placeholder art while the remote art is being downloaded
                                BitmapFactory.decodeResource(musicService.resources,
                                        R.drawable.ic_default_art)
                            }
                        }
            }

            job.cancelAndJoin()
        }
        notificationBuilder
                .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(playPauseButtonPosition)
                        // show only play/pause in compact view
                        .setMediaSession(sessionToken))
                .setColor(notificationColor)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setUsesChronometer(true)
                .setContentIntent(createContentIntent(description))
                .setContentTitle(description.title)
                .setContentText(description.subtitle)
                .setChannelId(MEDIA_PLAYER_NOTIFICATION)
                .setSound(null)
                .setVibrate(null)
                .setLargeIcon(art)

        if (controller != null && controller!!.extras != null) {
            val castName = controller!!.extras.getString(MusicService.EXTRA_CONNECTED_CAST)
            if (castName != null) {
                val castInfo = musicService.resources
                        .getString(R.string.casting_to_device, castName)
                notificationBuilder.setSubText(castInfo)
                notificationBuilder.addAction(R.drawable.ic_close_black_24dp,
                        musicService.getString(R.string.stop_casting), stopCastIntent)
            }
        }

        setNotificationPlaybackState(notificationBuilder)
        return notificationBuilder.build()
    }

    private fun addPlayPauseAction(builder: NotificationCompat.Builder) {
        Timber.d("updatePlayPauseAction")
        val label: String
        val icon: Int
        val intent: PendingIntent
        if (mPlaybackState!!.state == PlaybackStateCompat.STATE_PLAYING) {
            label = musicService.getString(R.string.label_pause)
            icon = R.drawable.uamp_ic_pause_white_24dp
            intent = pauseIntent
        } else {
            label = musicService.getString(R.string.label_play)
            icon = R.drawable.uamp_ic_play_arrow_white_24dp
            intent = playIntent
        }
        builder.addAction(NotificationCompat.Action(icon, label, intent))
    }

    private fun setNotificationPlaybackState(builder: NotificationCompat.Builder) {
        Timber.d("updateNotificationPlaybackState. mPlaybackState=%s", mPlaybackState)
        if (mPlaybackState == null || !mStarted) {
            Timber.d("updateNotificationPlaybackState. cancelling notification!")
            musicService.stopForeground(true)
            return
        }
        if (mPlaybackState!!.state == PlaybackStateCompat.STATE_PLAYING
                && mPlaybackState!!.position >= 0) {
            Timber.d("updateNotificationPlaybackState. updating playback position to %s seconds",
                    (System.currentTimeMillis() - mPlaybackState!!.position) / 1000)
            builder
                    .setWhen(System.currentTimeMillis() - mPlaybackState!!.position)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
        } else {
            Timber.d("updateNotificationPlaybackState. hiding playback position")
            builder
                    .setWhen(0)
                    .setShowWhen(false)
                    .setUsesChronometer(false)
        }
        // Make sure that the notification can be dismissed by the user when we are not playing:
        builder.setOngoing(mPlaybackState!!.state == PlaybackStateCompat.STATE_PLAYING)
    }

    companion object {
        const val ACTION_PAUSE = "com.example.android.uamp.pause"
        const val ACTION_PLAY = "com.example.android.uamp.play"
        const val ACTION_PREV = "com.example.android.uamp.prev"
        const val ACTION_NEXT = "com.example.android.uamp.next"
        const val ACTION_STOP_CASTING = "com.example.android.uamp.stop_cast"
    }
}
