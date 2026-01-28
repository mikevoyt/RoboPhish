package com.bayapps.android.robophish.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.utils.NetworkHelper
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import timber.log.Timber

/**
 * Base activity for activities that need to show a playback control fragment.
 */
abstract class BaseActivity : ActionBarCastActivity(), MediaBrowserProvider {
    private lateinit var controlsFragment: PlaybackControlsFragment
    override var mediaBrowser: MediaBrowser? = null
    private var mediaBrowserFuture: ListenableFuture<MediaBrowser>? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updateControlsVisibility()
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateControlsVisibility()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("Activity onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val taskDesc = ActivityManager.TaskDescription(
                title.toString(),
                BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_white),
                ContextCompat.getColor(this, R.color.primaryColor)
            )
            setTaskDescription(taskDesc)
        }
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
        connectBrowserIfNeeded()
    }

    override fun onStop() {
        super.onStop()
        Timber.d("Activity onStop")
        mediaBrowser?.removeListener(playerListener)
        mediaBrowser?.release()
        mediaBrowser = null
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

    private fun shouldShowControls(): Boolean {
        val browser = mediaBrowser ?: return false
        val hasItem = browser.currentMediaItem != null
        return hasItem &&
            browser.playbackState != Player.STATE_IDLE &&
            browser.playbackState != Player.STATE_ENDED
    }

    private fun updateControlsVisibility() {
        if (shouldShowControls()) {
            showPlaybackControls()
        } else {
            hidePlaybackControls()
        }
    }

    private fun connectBrowserIfNeeded() {
        if (mediaBrowser != null) {
            updateControlsVisibility()
            controlsFragment.onConnected()
            onMediaControllerConnected()
            return
        }
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaBrowser.Builder(this, token).buildAsync()
        mediaBrowserFuture = future
        future.addListener(
            {
                try {
                    val browser = future.get()
                    mediaBrowser = browser
                    browser.addListener(playerListener)
                    updateControlsVisibility()
                    controlsFragment.onConnected()
                    onMediaControllerConnected()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect MediaBrowser")
                    hidePlaybackControls()
                }
            },
            MoreExecutors.directExecutor()
        )
    }
}
