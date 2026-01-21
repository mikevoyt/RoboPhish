package com.bayapps.android.robophish.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
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
import com.bayapps.android.robophish.MusicService
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

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Timber.d("Received playback state change to state %s", state?.state)
            onPlaybackStateChangedInternal(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) return
            Timber.d(
                "Received metadata state change to mediaId=%s song=%s",
                metadata.description.mediaId,
                metadata.description.title
            )
            onMetadataChangedInternal(metadata)
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
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
            val controller = (context as BaseActivity).getSupportMediaController()
            val metadata = controller?.metadata
            if (metadata != null) {
                intent.putExtra(
                    MusicPlayerActivity.EXTRA_CURRENT_MEDIA_DESCRIPTION,
                    metadata.description
                )
            }
            startActivity(intent)
        }
        return rootView
    }

    override fun onStart() {
        super.onStart()
        Timber.d("fragment.onStart")
        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        if (controller != null) {
            onConnected()
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.d("fragment.onStop")
        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        controller?.unregisterCallback(callback)
    }

    fun onConnected() {
        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        Timber.d("onConnected, mediaController==null? %s", controller == null)
        if (controller != null) {
            onMetadataChangedInternal(controller.metadata)
            onPlaybackStateChangedInternal(controller.playbackState)
            controller.registerCallback(callback)
        }
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun onMetadataChangedInternal(metadata: MediaMetadataCompat?) {
        Timber.d("onMetadataChanged %s", metadata)
        if (activity == null) {
            Timber.w(
                "onMetadataChanged called when getActivity null, ignoring."
            )
            return
        }
        if (metadata == null) return

        title.text = metadata.description.title
        subtitle.text = metadata.description.subtitle
        val newArtUrl = metadata.description.iconUri?.toString()
        if (!TextUtils.equals(newArtUrl, artUrl)) {
            artUrl = newArtUrl
            val art: Bitmap? = metadata.description.iconBitmap
            if (art != null) {
                albumArt.setImageBitmap(art)
            } else if (!artUrl.isNullOrBlank()) {
                picasso.load(artUrl).fit().centerInside().into(albumArt)
            }
        }
    }

    fun setExtraInfo(extraInfoValue: String?) {
        if (extraInfoValue == null) {
            extraInfo.visibility = View.GONE
        } else {
            extraInfo.text = extraInfoValue
            extraInfo.visibility = View.VISIBLE
        }
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun onPlaybackStateChangedInternal(state: PlaybackStateCompat?) {
        Timber.d("onPlaybackStateChanged %s", state)
        if (activity == null) {
            Timber.w(
                "onPlaybackStateChanged called when getActivity null, ignoring."
            )
            return
        }
        if (state == null) return

        val enablePlay = when (state.state) {
            PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.STATE_STOPPED -> true
            PlaybackStateCompat.STATE_ERROR -> {
                Timber.e("error playbackstate: %s", state.errorMessage)
                Toast.makeText(activity, state.errorMessage, Toast.LENGTH_LONG).show()
                false
            }
            else -> false
        }

        val drawableId = if (enablePlay) {
            R.drawable.ic_play_arrow_black_36dp
        } else {
            R.drawable.ic_pause_black_36dp
        }
        playPause.setImageDrawable(
            ContextCompat.getDrawable(requireActivity(), drawableId)
        )

        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        val castName = controller?.extras?.getString(MusicService.EXTRA_CONNECTED_CAST)
        val extraInfoValue = if (castName != null) {
            resources.getString(R.string.casting_to_device, castName)
        } else {
            null
        }
        setExtraInfo(extraInfoValue)
    }

    private val buttonListener = View.OnClickListener { view ->
        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        val stateObj = controller?.playbackState
        val state = stateObj?.state ?: PlaybackStateCompat.STATE_NONE
        Timber.d("Button pressed, in state %s", state)
        when (view.id) {
            R.id.play_pause -> {
                Timber.d("Play button pressed, in state %s", state)
                if (state == PlaybackStateCompat.STATE_PAUSED ||
                    state == PlaybackStateCompat.STATE_STOPPED ||
                    state == PlaybackStateCompat.STATE_NONE
                ) {
                    playMedia()
                } else if (state == PlaybackStateCompat.STATE_PLAYING ||
                    state == PlaybackStateCompat.STATE_BUFFERING ||
                    state == PlaybackStateCompat.STATE_CONNECTING
                ) {
                    pauseMedia()
                }
            }
        }
    }

    private fun playMedia() {
        (activity as? BaseActivity)?.getSupportMediaController()
            ?.transportControls
            ?.play()
    }

    private fun pauseMedia() {
        (activity as? BaseActivity)?.getSupportMediaController()
            ?.transportControls
            ?.pause()
    }
}
