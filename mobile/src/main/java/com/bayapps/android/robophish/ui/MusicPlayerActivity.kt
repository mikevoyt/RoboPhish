package com.bayapps.android.robophish.ui

import android.app.SearchManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.text.TextUtils
import androidx.fragment.app.FragmentTransaction
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.utils.MediaIDHelper
import timber.log.Timber

/**
 * Main activity for the music player.
 */
class MusicPlayerActivity : BaseActivity(), MediaBrowserFragment.MediaFragmentListener {
    private var voiceSearchParams: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("Activity onCreate")

        setContentView(R.layout.activity_player)
        initializeToolbar()
        initializeFromParams(savedInstanceState, intent)

        if (savedInstanceState == null) {
            startFullScreenActivityIfNeeded(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        getMediaId()?.let { outState.putString(SAVED_MEDIA_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onMediaItemSelected(item: MediaBrowserCompat.MediaItem) {
        Timber.d("onMediaItemSelected, mediaId=%s", item.mediaId)
        when {
            item.isPlayable -> {
                getSupportMediaController()?.transportControls
                    ?.playFromMediaId(item.mediaId, null)
            }
            item.isBrowsable -> {
                val title = item.description.title?.toString() ?: ""
                val subtitle = item.description.subtitle?.toString() ?: ""
                navigateToBrowser(title, subtitle, item.mediaId)
            }
            else -> {
                Timber.w(
                    "Ignoring MediaItem that is neither browsable nor playable: mediaId=%s",
                    item.mediaId
                )
            }
        }
    }

    override fun setToolbarTitle(title: CharSequence?) {
        Timber.d("Setting toolbar title to %s", title)
        setTitle(title ?: getString(R.string.app_name))
    }

    override fun setToolbarSubTitle(title: CharSequence?) {
        Timber.d("Setting toolbar title to %s", title)
        setSubtitle(title ?: "")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.d("onNewIntent, intent=%s", intent)
        initializeFromParams(null, intent)
        startFullScreenActivityIfNeeded(intent)
    }

    private fun startFullScreenActivityIfNeeded(intent: Intent?) {
        if (intent != null && intent.getBooleanExtra(EXTRA_START_FULLSCREEN, false)) {
            val description =
                intent.getParcelableExtraCompat<MediaDescriptionCompat>(EXTRA_CURRENT_MEDIA_DESCRIPTION)
            val fullScreenIntent = Intent(this, FullScreenPlayerActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_CURRENT_MEDIA_DESCRIPTION, description)
            startActivity(fullScreenIntent)
        }
    }

    private fun initializeFromParams(savedInstanceState: Bundle?, intent: Intent) {
        var mediaId: String? = null
        val showMediaId = intent.getStringExtra(EXTRA_SHOW_MEDIA_ID)
        if (!showMediaId.isNullOrEmpty()) {
            val selectedTrackId = intent.getStringExtra(EXTRA_SELECTED_TRACK_ID)
            navigateToBrowser(null, null, showMediaId, selectedTrackId)
            return
        }
        when (intent.action) {
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                voiceSearchParams = intent.extras
                Timber.d(
                    "Starting from voice search query=%s",
                    voiceSearchParams?.getString(SearchManager.QUERY)
                )
            }
            MediaStore.INTENT_ACTION_MEDIA_SEARCH -> {
                navigateToBrowser(null, null, null)

                val extras = intent.extras ?: Bundle()
                val title = extras.getString("title")
                val subtitle = extras.getString("subtitle")
                mediaId = extras.getString("showid")

                val year = subtitle?.split("-")?.getOrNull(0)
                if (!year.isNullOrEmpty()) {
                    navigateToBrowser(null, null, MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR + "/" + year)
                }

                navigateToBrowser(title, subtitle, mediaId)
            }
            else -> {
                if (savedInstanceState != null) {
                    mediaId = savedInstanceState.getString(SAVED_MEDIA_ID)
                }
                navigateToBrowser(null, null, mediaId)
            }
        }
    }

    private fun navigateToBrowser(
        title: String?,
        subtitle: String?,
        mediaId: String?,
        selectedTrackId: String? = null
    ) {
        Timber.d("navigateToBrowser, mediaId=%s", mediaId)
        var fragment = browseFragment
        if (fragment == null || !TextUtils.equals(fragment.getMediaId(), mediaId)) {
            fragment = MediaBrowserFragment().apply {
                setMediaId(title, subtitle, mediaId, selectedTrackId)
            }
            val transaction: FragmentTransaction = supportFragmentManager.beginTransaction()
            transaction.setCustomAnimations(
                R.animator.slide_in_from_right,
                R.animator.slide_out_to_left,
                R.animator.slide_in_from_left,
                R.animator.slide_out_to_right
            )
            transaction.replace(R.id.container, fragment, FRAGMENT_TAG)
            if (mediaId != null) {
                transaction.addToBackStack(null)
            }
            transaction.commit()
        }
    }

    fun getMediaId(): String? = browseFragment?.getMediaId()

    private val browseFragment: MediaBrowserFragment?
        get() = supportFragmentManager.findFragmentByTag(FRAGMENT_TAG) as? MediaBrowserFragment

    override fun onMediaControllerConnected() {
        voiceSearchParams?.let { params ->
            val query = params.getString(SearchManager.QUERY)
            getSupportMediaController()?.transportControls?.playFromSearch(query, params)
            voiceSearchParams = null
        }
        browseFragment?.onConnected()
    }

    companion object {
        private const val SAVED_MEDIA_ID = "com.example.android.uamp.MEDIA_ID"
        private const val FRAGMENT_TAG = "uamp_list_container"

        const val EXTRA_START_FULLSCREEN = "com.example.android.uamp.EXTRA_START_FULLSCREEN"
        const val EXTRA_CURRENT_MEDIA_DESCRIPTION =
            "com.example.android.uamp.CURRENT_MEDIA_DESCRIPTION"
        const val EXTRA_SHOW_MEDIA_ID = "com.example.android.uamp.EXTRA_SHOW_MEDIA_ID"
        const val EXTRA_SELECTED_TRACK_ID = "com.example.android.uamp.EXTRA_SELECTED_TRACK_ID"
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
