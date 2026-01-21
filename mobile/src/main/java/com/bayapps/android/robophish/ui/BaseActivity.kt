package com.bayapps.android.robophish.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.bayapps.android.robophish.utils.NetworkHelper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import timber.log.Timber

/**
 * Base activity for activities that need to show a playback control fragment.
 */
abstract class BaseActivity : ActionBarCastActivity(), MediaBrowserProvider {
    private lateinit var controlsFragment: PlaybackControlsFragment
    override lateinit var mediaBrowser: MediaBrowserCompat

    private val deps by lazy { ServiceLocator.get(this) }
    private val googleApiAvailability: GoogleApiAvailability by lazy { deps.googleApiAvailability }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("Activity onCreate")

        checkPlayServices()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val taskDesc = ActivityManager.TaskDescription.Builder()
                .setLabel(title.toString())
                .setIcon(R.drawable.ic_launcher_white)
                .setPrimaryColor(ContextCompat.getColor(this, R.color.primaryColor))
                .build()
            setTaskDescription(taskDesc)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val taskDesc = ActivityManager.TaskDescription(
                title.toString(),
                BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_white),
                ContextCompat.getColor(this, R.color.primaryColor)
            )
            setTaskDescription(taskDesc)
        }

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallback,
            null
        )
    }

    private fun checkPlayServices(): Boolean {
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)
        return resultCode == ConnectionResult.SUCCESS
    }

    fun getSupportMediaController(): MediaControllerCompat? {
        return MediaControllerCompat.getMediaController(this)
    }

    override fun onStart() {
        super.onStart()
        Timber.d("Activity onStart")

        controlsFragment = supportFragmentManager
            .findFragmentById(R.id.fragment_playback_controls) as? PlaybackControlsFragment
            ?: throw IllegalStateException(
                "Missing fragment with id 'controls'. Cannot continue."
            )

        hidePlaybackControls()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        Timber.d("Activity onStop")
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(mediaControllerCallback)
        mediaBrowser.disconnect()
    }

    protected open fun onMediaControllerConnected() {
    }

    protected fun showPlaybackControls() {
        Timber.d("showPlaybackControls")
        if (NetworkHelper.isOnline(this)) {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.animator.slide_in_from_bottom,
                    R.animator.slide_out_to_bottom,
                    R.animator.slide_in_from_bottom,
                    R.animator.slide_out_to_bottom
                )
                .show(controlsFragment)
                .commit()
        }
    }

    protected fun hidePlaybackControls() {
        Timber.d("hidePlaybackControls")
        supportFragmentManager.beginTransaction()
            .hide(controlsFragment)
            .commit()
    }

    protected fun shouldShowControls(): Boolean {
        val mediaController = MediaControllerCompat.getMediaController(this)
        if (mediaController == null ||
            mediaController.metadata == null ||
            mediaController.playbackState == null
        ) {
            return false
        }
        return when (mediaController.playbackState.state) {
            PlaybackStateCompat.STATE_ERROR,
            PlaybackStateCompat.STATE_NONE,
            PlaybackStateCompat.STATE_STOPPED -> false
            else -> true
        }
    }

    @Throws(RemoteException::class)
    private fun connectToSession(token: MediaSessionCompat.Token) {
        val mediaController = MediaControllerCompat(this, token)
        MediaControllerCompat.setMediaController(this, mediaController)
        mediaController.registerCallback(mediaControllerCallback)

        if (shouldShowControls()) {
            showPlaybackControls()
        } else {
            Timber.d("connectionCallback.onConnected: hiding controls because metadata is null")
            hidePlaybackControls()
        }

        controlsFragment.onConnected()
        onMediaControllerConnected()
    }

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        @SuppressLint("BinaryOperationInTimber")
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            if (shouldShowControls()) {
                showPlaybackControls()
            } else {
                Timber.d(
                    "mediaControllerCallback.onPlaybackStateChanged: hiding controls because state is %s",
                    state?.state
                )
                hidePlaybackControls()
            }
        }

        @SuppressLint("BinaryOperationInTimber")
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (shouldShowControls()) {
                showPlaybackControls()
            } else {
                Timber.d(
                    "mediaControllerCallback.onMetadataChanged: hiding controls because metadata is null"
                )
                hidePlaybackControls()
            }
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Timber.d("onConnected")
            try {
                connectToSession(mediaBrowser.sessionToken)
            } catch (e: RemoteException) {
                Timber.e(e, "could not connect media controller")
                hidePlaybackControls()
            }
        }
    }
}
