package com.bayapps.android.robophish.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.squareup.picasso.Picasso
import timber.log.Timber

/**
 * A class that shows the Media Queue to the user.
 */
class PlaybackControlsFragment : Fragment() {
    private lateinit var playPause: ImageButton
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var extraInfo: TextView
    private lateinit var albumArt: ImageView
    private var artUrl: String? = null

    private val picasso: Picasso by lazy { ServiceLocator.get(requireContext()).picasso }
    private var controller: Player? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            updatePlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlaybackState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateMetadata(mediaItem?.mediaMetadata)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateMetadata(mediaMetadata)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.fragment_playback_controls, container, false)

        playPause = rootView.findViewById(R.id.play_pause)
        playPause.isEnabled = true
        playPause.setOnClickListener(buttonListener)

        title = rootView.findViewById(R.id.title)
        subtitle = rootView.findViewById(R.id.artist)
        extraInfo = rootView.findViewById(R.id.extra_info)
        albumArt = rootView.findViewById(R.id.album_art)
        rootView.setOnClickListener {
            val context = activity ?: return@setOnClickListener
            val intent = Intent(context, FullScreenPlayerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
        return rootView
    }

    override fun onStart() {
        super.onStart()
        Timber.d("fragment.onStart")
        onConnected()
    }

    override fun onStop() {
        super.onStop()
        Timber.d("fragment.onStop")
        controller?.removeListener(playerListener)
        controller = null
    }

    fun onConnected() {
        val browser = (activity as? MediaBrowserProvider)?.mediaBrowser ?: return
        controller?.removeListener(playerListener)
        controller = browser
        browser.addListener(playerListener)
        updateMetadata(browser.currentMediaItem?.mediaMetadata)
        updatePlaybackState()
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun updateMetadata(metadata: MediaMetadata?) {
        Timber.d("onMetadataChanged %s", metadata)
        if (metadata == null) return

        title.text = metadata.title
        subtitle.text = metadata.subtitle
        extraInfo.text = metadata.description

        val newArtUrl = metadata.artworkUri?.toString()
        if (!TextUtils.equals(newArtUrl, artUrl)) {
            artUrl = newArtUrl
            if (!artUrl.isNullOrBlank()) {
                picasso.load(artUrl).into(albumArt)
            } else {
                albumArt.setImageDrawable(null)
            }
        }
    }

    private fun updatePlaybackState() {
        val player = controller ?: return
        val isPlaying = player.isPlaying
        val state = player.playbackState
        val isEnabled = state != Player.STATE_IDLE && state != Player.STATE_ENDED
        playPause.isEnabled = isEnabled

        val drawable = if (isPlaying) {
            ContextCompat.getDrawable(requireContext(), R.drawable.uamp_ic_pause_white_48dp)
        } else {
            ContextCompat.getDrawable(requireContext(), R.drawable.uamp_ic_play_arrow_white_48dp)
        }
        playPause.setImageDrawable(drawable)
    }

    private val buttonListener = View.OnClickListener { v ->
        val player = controller ?: return@OnClickListener
        when (v?.id) {
            R.id.play_pause -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            else -> {
                Timber.w("Unknown button event")
            }
        }
    }
}
