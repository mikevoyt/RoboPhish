package com.bayapps.android.robophish.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bayapps.android.robophish.MusicService
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.bayapps.android.robophish.utils.MediaIDHelper
import coil.ImageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import timber.log.Timber

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
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var userSeeking = false

    private val imageLoader: ImageLoader by lazy { ServiceLocator.get(this).imageLoader }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updatePlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateMetadata(mediaItem?.mediaMetadata)
            updateDuration()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateMetadata(mediaMetadata)
            updateDuration()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_player)
        initializeToolbar()
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

        skipPrev.setOnClickListener { controller?.seekToPrevious() }
        skipNext.setOnClickListener { controller?.seekToNext() }
        playPause.setOnClickListener { onPlayPauseClick() }

        seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    controller?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                updatePlaybackState()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        connectController()
        handler.post(updateProgressTask)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(updateProgressTask)
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        controllerFuture = null
    }

    private fun connectController() {
        if (controller != null) return
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val mediaController = future.get()
                    controller = mediaController
                    mediaController.addListener(playerListener)
                    updateMetadata(mediaController.mediaMetadata)
                    updateDuration()
                    updatePlaybackState()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to connect MediaController")
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return
        line1.text = metadata.title
        line2.text = metadata.subtitle
        line3.text = metadata.albumTitle
        line4.text = metadata.artist
        line5.text = metadata.description

        val iconUri = metadata.artworkUri?.toString()
        if (!iconUri.isNullOrEmpty() && iconUri != currentArtUrl) {
            currentArtUrl = iconUri
            val request = ImageRequest.Builder(this)
                .data(iconUri)
                .target(backgroundImage)
                .build()
            imageLoader.enqueue(request)
        }
    }

    private fun updateDuration() {
        val duration = controller?.duration ?: 0L
        seekbar.max = duration.toInt().coerceAtLeast(0)
        end.text = DateUtils.formatElapsedTime(duration / 1000)
    }

    private fun updatePlaybackState() {
        val player = controller ?: return
        val position = player.currentPosition
        seekbar.progress = position.toInt()
        start.text = DateUtils.formatElapsedTime(position / 1000)

        val isPlaying = player.isPlaying
        playPause.setImageDrawable(if (isPlaying) pauseDrawable else playDrawable)

        val buffering = player.playbackState == Player.STATE_BUFFERING
        loading.visibility = if (buffering) View.VISIBLE else View.GONE
        controllers.visibility = if (buffering && !userSeeking) View.INVISIBLE else View.VISIBLE
    }

    private val updateProgressTask = object : Runnable {
        override fun run() {
            updatePlaybackState()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun onPlayPauseClick() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
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
        val currentItem = controller?.currentMediaItem
        val mediaId = currentItem?.mediaId
        if (mediaId.isNullOrEmpty()) {
            finish()
            return
        }
        val showId = MediaIDHelper.getHierarchy(mediaId).getOrNull(1)
        if (showId.isNullOrEmpty()) {
            finish()
            return
        }
        val showMediaId = MediaIDHelper.createMediaID(
            null,
            MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW,
            showId
        )
        val intent = Intent(this, MusicPlayerActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MusicPlayerActivity.EXTRA_SHOW_MEDIA_ID, showMediaId)
            .putExtra(MusicPlayerActivity.EXTRA_SELECTED_TRACK_ID, MediaIDHelper.extractMusicIDFromMediaID(mediaId))
        startActivity(intent)
        finish()
    }
}
