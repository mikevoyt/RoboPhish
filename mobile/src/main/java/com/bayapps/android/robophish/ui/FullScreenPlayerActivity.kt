package com.bayapps.android.robophish.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.RemoteException
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.NonNull
import androidx.core.content.ContextCompat
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.inject
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.squareup.picasso.Picasso
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * A full screen player that shows the current playing music with a background image
 * depicting the album art.
 */
class FullScreenPlayerActivity : ActionBarCastActivity() {
    private lateinit var skipPrev: ImageView
    private lateinit var skipNext: ImageView
    private lateinit var playPause: ImageView
    private lateinit var start: TextView
    private lateinit var end: TextView
    private lateinit var seekbar: SeekBar
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var line3: TextView
    private lateinit var line4: TextView
    private lateinit var line5: TextView
    private lateinit var loading: ProgressBar
    private lateinit var controllers: View
    private lateinit var pauseDrawable: Drawable
    private lateinit var playDrawable: Drawable
    private lateinit var backgroundImage: ImageView

    private var currentArtUrl: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var scheduleFuture: ScheduledFuture<*>? = null
    private var lastPlaybackState: PlaybackStateCompat? = null
    private var currentMetadata: MediaMetadataCompat? = null
    private var userSeeking = false

    @Inject lateinit var picasso: Picasso

    private val updateProgressTask = Runnable { handler.post { updateProgress() } }
    private val executorService: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Timber.d("onPlaybackstate changed %s", state)
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) return
            currentMetadata = metadata
            val venue = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
            val location = metadata.getString(MediaMetadataCompat.METADATA_KEY_AUTHOR)
            updateMediaDescription(metadata.description, venue, location, resolveArtUri(metadata))
            updateDuration(metadata)
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Timber.d("onConnected")
            try {
                connectToSession(mediaBrowser.sessionToken)
            } catch (e: RemoteException) {
                Timber.e(e, "could not connect media controller")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_player)
        initializeToolbar()
        inject()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pauseDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_pause_white_48dp)!!
        playDrawable = ContextCompat.getDrawable(this, R.drawable.uamp_ic_play_arrow_white_48dp)!!

        skipPrev = findViewById(R.id.prev)
        skipNext = findViewById(R.id.next)
        playPause = findViewById(R.id.play_pause)
        start = findViewById(R.id.startText)
        end = findViewById(R.id.endText)
        seekbar = findViewById(R.id.seekBar1)
        line1 = findViewById(R.id.line1)
        line2 = findViewById(R.id.line2)
        line3 = findViewById(R.id.line3)
        line4 = findViewById(R.id.line4)
        line5 = findViewById(R.id.line5)
        loading = findViewById(R.id.progressBar1)
        controllers = findViewById(R.id.controllers)
        backgroundImage = findViewById(R.id.background_image)

        skipPrev.setOnClickListener { MediaControllerCompat.getMediaController(this)?.transportControls?.skipToPrevious() }
        skipNext.setOnClickListener { MediaControllerCompat.getMediaController(this)?.transportControls?.skipToNext() }
        playPause.setOnClickListener { onPlayPauseClick() }

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MediaControllerCompat.getMediaController(this@FullScreenPlayerActivity)
                        ?.transportControls
                        ?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                updatePlaybackState(lastPlaybackState)
            }
        })

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicService::class.java),
            connectionCallback,
            null
        )
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
        scheduleFuture = executorService.scheduleAtFixedRate(
            updateProgressTask,
            PROGRESS_UPDATE_INITIAL_INTERVAL,
            PROGRESS_UPDATE_INTERNAL,
            TimeUnit.MILLISECONDS
        )
    }

    override fun onStop() {
        super.onStop()
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(callback)
        mediaBrowser.disconnect()
        scheduleFuture?.cancel(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        executorService.shutdown()
    }

    private fun connectToSession(token: MediaSessionCompat.Token) {
        val mediaController = MediaControllerCompat(this, token)
        MediaControllerCompat.setMediaController(this, mediaController)
        mediaController.registerCallback(callback)

        val metadata = mediaController.metadata
        val description = intent.getParcelableExtraCompat<MediaDescriptionCompat>(
            MusicPlayerActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION
        )
        if (metadata != null) {
            currentMetadata = metadata
            val venue = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
            val location = metadata.getString(MediaMetadataCompat.METADATA_KEY_AUTHOR)
            updateMediaDescription(metadata.description, venue, location, resolveArtUri(metadata))
            updateDuration(metadata)
        } else if (description != null) {
            updateMediaDescription(description, "", "", null)
        }

        updatePlaybackState(mediaController.playbackState)
    }

    private fun updateMediaDescription(
        description: MediaDescriptionCompat,
        venue: String?,
        location: String?,
        artUriOverride: String?
    ) {
        line1.text = description.title
        line2.text = description.subtitle
        line3.text = venue
        line4.text = location
        line5.text = description.description

        val iconUri = artUriOverride ?: description.iconUri?.toString()
        if (iconUri != null && iconUri != currentArtUrl) {
            currentArtUrl = iconUri
            picasso.load(iconUri).into(backgroundImage)
        }
    }

    private fun updateDuration(metadata: MediaMetadataCompat) {
        val duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
        seekbar.max = duration.toInt()
        end.text = DateUtils.formatElapsedTime(duration / 1000)
    }

    private fun resolveArtUri(metadata: MediaMetadataCompat): String? {
        return metadata.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI)
            ?: metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
    }

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        lastPlaybackState = state
        if (state == null) {
            return
        }
        val position = if (state.position == PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN) {
            0L
        } else {
            state.position
        }
        seekbar.progress = position.toInt()
        start.text = DateUtils.formatElapsedTime(position / 1000)

        val isPlaying = state.state == PlaybackStateCompat.STATE_PLAYING ||
            state.state == PlaybackStateCompat.STATE_BUFFERING
        playPause.setImageDrawable(if (isPlaying) pauseDrawable else playDrawable)

        val buffering = state.state == PlaybackStateCompat.STATE_BUFFERING
        loading.visibility = if (buffering) View.VISIBLE else View.GONE
        controllers.visibility = if (buffering && !userSeeking) View.INVISIBLE else View.VISIBLE
    }

    private fun updateProgress() {
        val controller = MediaControllerCompat.getMediaController(this)
        val state = controller?.playbackState ?: lastPlaybackState
        if (state == null) {
            return
        }
        lastPlaybackState = state
        val currentTime = SystemClock.elapsedRealtime()
        var position = state.position
        if (state.state == PlaybackStateCompat.STATE_PLAYING) {
            val timeDelta = currentTime - state.lastPositionUpdateTime
            position += (timeDelta * state.playbackSpeed).toLong()
        }
        seekbar.progress = position.toInt()
        start.text = DateUtils.formatElapsedTime(position / 1000)
    }

    private fun onPlayPauseClick() {
        val controller = MediaControllerCompat.getMediaController(this)
        val state = controller?.playbackState
        if (state == null) {
            return
        }
        if (state.state == PlaybackStateCompat.STATE_PAUSED ||
            state.state == PlaybackStateCompat.STATE_STOPPED ||
            state.state == PlaybackStateCompat.STATE_NONE
        ) {
            controller.transportControls.play()
        } else if (state.state == PlaybackStateCompat.STATE_PLAYING ||
            state.state == PlaybackStateCompat.STATE_BUFFERING ||
            state.state == PlaybackStateCompat.STATE_CONNECTING
        ) {
            controller.transportControls.pause()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            navigateBackToShow()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        navigateBackToShow()
    }

    private fun navigateBackToShow() {
        val metadata = currentMetadata ?: MediaControllerCompat.getMediaController(this)?.metadata
        val showId = metadata?.getString(MediaMetadataCompat.METADATA_KEY_COMPILATION)
        if (showId.isNullOrEmpty()) {
            finish()
            return
        }
        val trackMediaId = metadata.description?.mediaId
        val trackId = trackMediaId?.let { MediaIDHelper.extractMusicIDFromMediaID(it) } ?: trackMediaId
        val showMediaId = MediaIDHelper.createMediaID(
            null,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )
        val intent = Intent(this, MusicPlayerActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MusicPlayerActivity.EXTRA_SHOW_MEDIA_ID, showMediaId)
        if (!trackId.isNullOrEmpty()) {
            intent.putExtra(MusicPlayerActivity.EXTRA_SELECTED_TRACK_ID, trackId)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        private const val PROGRESS_UPDATE_INTERNAL = 1000L
        private const val PROGRESS_UPDATE_INITIAL_INTERVAL = 100L
    }

    private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }
}
