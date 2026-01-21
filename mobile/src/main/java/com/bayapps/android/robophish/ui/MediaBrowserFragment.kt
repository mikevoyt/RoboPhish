package com.bayapps.android.robophish.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.viewpager.widget.ViewPager
import com.bayapps.android.robophish.BuildConfig
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.bayapps.android.robophish.utils.Downloader
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.bayapps.android.robophish.utils.NetworkHelper
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * A Fragment that lists music catalog items from a MediaBrowser and
 * sends selected media items to the attached listener to play.
 */
class MediaBrowserFragment : Fragment() {
    private var mediaId: String? = null
    private var mediaFragmentListener: MediaFragmentListener? = null
    private var browserAdapter: BrowseAdapter? = null
    private var errorView: View? = null
    private var errorMessage: View? = null
    private var progressBar: View? = null
    private var showData: JSONObject? = null

    private var listView: ListView? = null
    private var pendingListState: Parcelable? = null
    private var pendingSelectedTrackId: String? = null

    private val okHttpClient by lazy { ServiceLocator.get(requireContext()).okHttpClient }
    private val okHttpNoAuthClient by lazy { ServiceLocator.get(requireContext()).okHttpNoAuthClient }

    private var oldOnline = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(true)
        }

        override fun onLost(network: Network) {
            handleNetworkChange(false)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            checkForUserVisibleErrors(false)
            browserAdapter?.notifyDataSetChanged()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            browserAdapter?.notifyDataSetChanged()
            progressBar?.visibility = View.INVISIBLE
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        mediaFragmentListener = activity as? MediaFragmentListener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Timber.d("fragment.onCreateView")

        val mediaId = getMediaId()
        val rootView: View = if (mediaId != null && MediaIDHelper.isShow(mediaId)) {
            val view = inflater.inflate(R.layout.fragment_list_show, container, false)
            registerMenuProvider()

            val viewPager = view.findViewById<ViewPager>(R.id.viewpager)
            viewPager.adapter = ShowPagerAdapter(view)
            viewPager.offscreenPageLimit = 3

            val tabLayout = view.findViewById<TabLayout>(R.id.sliding_tabs)
            tabLayout.setupWithViewPager(viewPager)

            val setlistWebView = view.findViewById<WebView>(R.id.setlist_webview)
            setlistWebView.settings.javaScriptEnabled = true

            val reviewsWebView = view.findViewById<WebView>(R.id.reviews_webview)
            reviewsWebView.settings.javaScriptEnabled = true

            val setlistDate = getSubTitle()?.replace(".", "-")
            if (!setlistDate.isNullOrBlank()) {
                lifecycleScope.launch {
                    val setlistResponse = fetchJson(
                        "https://api.phish.net/v3/setlists/get",
                        mapOf(
                            "showdate" to setlistDate,
                            "apikey" to BuildConfig.PHISHNET_API_KEY
                        ),
                        client = okHttpNoAuthClient
                    )
                    if (!isAdded) return@launch
                    if (setlistResponse == null) {
                        setlistWebView.loadData(
                            "<div>Error loading Setlist</div>",
                            "text/html",
                            null
                        )
                        reviewsWebView.loadData(
                            "<div>Error loading Reviews</div>",
                            "text/html",
                            null
                        )
                        return@launch
                    }
                    try {
                        val result = setlistResponse
                            .getJSONObject("response")
                            .getJSONArray("data")
                            .getJSONObject(0)
                        val showId = result.getInt("showid")
                        val location = result.getString("location")
                        val venue = result.getString("venue")
                        val header = "<h1>$venue</h1><h2>$location</h2>"
                        val setlistdata = result.getString("setlistdata")
                        val setlistnotes = result.getString("setlistnotes")
                        setlistWebView.loadData(
                            header + setlistdata + setlistnotes,
                            "text/html",
                            null
                        )

                        val reviewsResponse = fetchJson(
                            "https://api.phish.net/v3/reviews/query",
                            mapOf(
                                "showid" to showId.toString(),
                                "apikey" to BuildConfig.PHISHNET_API_KEY
                            ),
                            client = okHttpNoAuthClient
                        )
                        if (!isAdded) return@launch
                        if (reviewsResponse == null) {
                            reviewsWebView.loadData(
                                "<div>Error loading Reviews</div>",
                                "text/html",
                                null
                            )
                            return@launch
                        }
                        val reviewsData = reviewsResponse
                            .getJSONObject("response")
                            .getJSONArray("data")
                        val display = StringBuilder()
                        for (i in 0 until reviewsData.length()) {
                            val entry = reviewsData.getJSONObject(i)
                            val author = entry.getString("username")
                            val review = entry.getString("reviewtext")
                            val reviewDate = entry.getString("posted_date")
                            val reviewSubs = formatReviewText(review)
                            display.append("<h2>")
                                .append(author)
                                .append("</h2><h4>")
                                .append(reviewDate)
                                .append("</h4>")
                            display.append(reviewSubs).append("<br/>")
                        }
                        reviewsWebView.loadData(
                            display.toString(),
                            "text/html",
                            null
                        )
                    } catch (e: JSONException) {
                        Timber.e(e, "Error parsing setlist/reviews response")
                        setlistWebView.loadData(
                            "<div>Error loading Setlist</div>",
                            "text/html",
                            null
                        )
                        reviewsWebView.loadData(
                            "<div>Error loading Reviews</div>",
                            "text/html",
                            null
                        )
                    }
                }
            }

            val tapernotesWebview = view.findViewById<WebView>(R.id.tapernotes_webview)
            tapernotesWebview.settings.javaScriptEnabled = true

            val showId = MediaIDHelper.extractShowFromMediaID(mediaId)
            if (!showId.isNullOrBlank()) {
                lifecycleScope.launch {
                    val response = fetchJson(
                        "https://phish.in/api/v1/shows/$showId",
                        headers = mapOf("Authorization" to "Bearer ${BuildConfig.PHISHIN_API_KEY}")
                    )
                    if (!isAdded) return@launch
                    if (response == null) {
                        tapernotesWebview.loadData(
                            "<div>Error loading Taper Notes</div>",
                            "text/html",
                            null
                        )
                        return@launch
                    }
                    try {
                        showData = response
                        val data = response.getJSONObject("data")
                        var tapernotes = data.getString("taper_notes")
                        if (tapernotes == "null") tapernotes = "Not available"
                        val notesSubs = tapernotes.replace("\n", "<br/>")
                        tapernotesWebview.loadData(notesSubs, "text/html", null)
                    } catch (e: JSONException) {
                        Timber.e(e, "Error parsing taper notes response")
                        tapernotesWebview.loadData(
                            "<div>Error loading Taper Notes</div>",
                            "text/html",
                            null
                        )
                    }
                }
            }
            view
        } else {
            inflater.inflate(R.layout.fragment_list, container, false)
        }

        errorView = rootView.findViewById(R.id.playback_error)
        errorMessage = rootView.findViewById(R.id.error_message)
        progressBar = rootView.findViewById(R.id.progress_bar)
        progressBar?.visibility = View.VISIBLE

        browserAdapter = BrowseAdapter(requireActivity())

        listView = rootView.findViewById(R.id.list_view)
        listView?.adapter = browserAdapter
        listView?.setOnItemClickListener { _, _, position, _ ->
            checkForUserVisibleErrors(false)
            val item = browserAdapter?.getItem(position)
            if (item != null) {
                val siblings = browserAdapter?.items ?: emptyList()
                mediaFragmentListener?.onMediaItemSelected(item, siblings)
            }
        }
        pendingListState = savedInstanceState?.getParcelableCompat(LIST_STATE_KEY) ?: pendingListState
        pendingSelectedTrackId = savedInstanceState?.getString(SELECTED_TRACK_ID_KEY)
            ?: arguments?.getString(ARG_SELECTED_TRACK_ID)

        return rootView
    }

    private fun formatReviewText(raw: String): String {
        var text = raw
        text = text.replace("[b]", "<b>").replace("[/b]", "</b>")
        text = text.replace("[i]", "<i>").replace("[/i]", "</i>")
        text = text.replace("[u]", "<u>").replace("[/u]", "</u>")
        text = text.replace("[quote]", "<blockquote>").replace("[/quote]", "</blockquote>")
        text = text.replace(
            Regex("(?i)\\[url=(.+?)](.*?)\\[/url]"),
            "<a href=\"$1\">$2</a>"
        )
        text = text.replace(
            Regex("(?i)\\[url](.+?)\\[/url]"),
            "<a href=\"$1\">$1</a>"
        )
        text = text.replace(
            Regex("(?i)\\[img](.+?)\\[/img]"),
            "<img src=\"$1\" />"
        )
        return text.replace("\n", "<br/>")
    }

    private suspend fun fetchJson(
        url: String,
        queryParams: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        client: okhttp3.OkHttpClient = okHttpClient
    ): JSONObject? = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrl().newBuilder().apply {
            queryParams.forEach { (key, value) ->
                addQueryParameter(key, value)
            }
        }.build()
        val request = Request.Builder()
            .url(httpUrl)
            .apply {
                headers.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        try {
            Timber.d("Requesting %s", httpUrl)
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Timber.w("Request failed: %s body=%s", response.code, body)
                    return@withContext null
                }
                if (body.isNullOrBlank()) {
                    Timber.w("Request empty body for %s", httpUrl)
                    return@withContext null
                }
                JSONObject(body)
            }
        } catch (e: Exception) {
            Timber.e(e, "Request failed for %s", url)
            null
        }
    }

    override fun onStart() {
        super.onStart()
        val mediaBrowser = mediaFragmentListener?.mediaBrowser
        Timber.d("fragment.onStart, mediaId=%s onConnected=%s", mediaId, mediaBrowser != null)
        if (mediaBrowser != null) {
            onConnected()
        }
        oldOnline = NetworkHelper.isOnline(requireContext())
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = listView?.onSaveInstanceState()
        if (state != null) {
            outState.putParcelable(LIST_STATE_KEY, state)
            pendingListState = state
        }
        pendingSelectedTrackId?.let { outState.putString(SELECTED_TRACK_ID_KEY, it) }
    }

    override fun onPause() {
        super.onPause()
        pendingListState = listView?.onSaveInstanceState()
    }

    override fun onStop() {
        super.onStop()
        mediaFragmentListener?.mediaBrowser?.removeListener(playerListener)
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onDetach() {
        super.onDetach()
        mediaFragmentListener = null
    }

    fun getMediaId(): String? = arguments?.getString(ARG_MEDIA_ID)

    private fun getTitleText(): String? = arguments?.getString(ARG_TITLE)

    private fun getSubTitle(): String? = arguments?.getString(ARG_SUBTITLE)

    fun setMediaId(
        title: String?,
        subtitle: String?,
        mediaId: String?,
        selectedTrackId: String? = null
    ) {
        val args = Bundle(4)
        args.putString(ARG_MEDIA_ID, mediaId)
        args.putString(ARG_TITLE, title)
        args.putString(ARG_SUBTITLE, subtitle)
        if (!selectedTrackId.isNullOrEmpty()) {
            args.putString(ARG_SELECTED_TRACK_ID, selectedTrackId)
            pendingSelectedTrackId = selectedTrackId
        }
        arguments = args
    }

    fun onConnected() {
        if (isDetached) return
        mediaId = getMediaId()
        if (mediaId == null) {
            mediaId = MediaIDHelper.MEDIA_ID_ROOT
        }
        updateTitle()

        val resolvedMediaId = mediaId ?: return
        loadChildren(resolvedMediaId)

        mediaFragmentListener?.mediaBrowser?.addListener(playerListener)
    }

    private fun loadChildren(parentId: String) {
        val browser = mediaFragmentListener?.mediaBrowser ?: return
        val future = browser.getChildren(parentId, 0, Int.MAX_VALUE, null)
        val mainHandler = Handler(Looper.getMainLooper())
        future.addListener(
            {
                mainHandler.post {
                    try {
                        val result = future.get()
                        if (result.resultCode == LibraryResult.RESULT_SUCCESS) {
                            val items = result.value ?: emptyList()
                            checkForUserVisibleErrors(items.isEmpty())
                            progressBar?.visibility = View.INVISIBLE
                            browserAdapter?.setItems(items)
                            browserAdapter?.notifyDataSetChanged()
                            val restored = restoreListStateIfNeeded()
                            if (!restored) {
                                restoreSelectionIfNeeded()
                            }
                        } else {
                            Timber.e("browse fragment error resultCode=%s", result.resultCode)
                            checkForUserVisibleErrors(true)
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "Error loading children")
                        checkForUserVisibleErrors(true)
                    }
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private fun registerMenuProvider() {
        val menuHost: MenuHost = requireActivity()
        menuHost.addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                }

                override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                    return when (menuItem.itemId) {
                        0 -> {
                            val showId = MediaIDHelper.extractShowFromMediaID(getMediaId() ?: "")
                            val data = showData
                            if (showId != null && data != null && activity != null) {
                                Downloader(requireActivity(), data)
                            }
                            true
                        }
                        else -> false
                    }
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun handleNetworkChange(isOnline: Boolean) {
        if (mediaId == null || isOnline == oldOnline) return
        oldOnline = isOnline
        checkForUserVisibleErrors(false)
        if (isOnline) {
            browserAdapter?.notifyDataSetChanged()
        }
    }

    private fun restoreListStateIfNeeded(): Boolean {
        val state = pendingListState ?: return false
        listView?.onRestoreInstanceState(state)
        pendingListState = null
        return true
    }

    private fun restoreSelectionIfNeeded() {
        val selectedTrackId = pendingSelectedTrackId ?: return
        val adapter = browserAdapter ?: return
        val count = adapter.count
        if (count == 0) return
        var targetPosition: Int? = null
        for (i in 0 until count) {
            val item = adapter.getItem(i) ?: continue
            val mediaId = item.mediaId
            val trackId = MediaIDHelper.extractMusicIDFromMediaID(mediaId)
            if (trackId == selectedTrackId) {
                targetPosition = i
                break
            }
        }
        val position = targetPosition ?: return
        listView?.post { listView?.setSelection(position) }
        pendingSelectedTrackId = null
    }

    private inline fun <reified T : Parcelable> Bundle.getParcelableCompat(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }
    }

    private fun checkForUserVisibleErrors(forceError: Boolean) {
        var showError = forceError
        val activity = activity ?: return
        if (!NetworkHelper.isOnline(activity)) {
            (errorMessage as? android.widget.TextView)?.setText(R.string.error_no_connection)
            showError = true
        } else if (forceError) {
            (errorMessage as? android.widget.TextView)?.setText(R.string.error_loading_media)
            showError = true
        }
        errorView?.visibility = if (showError) View.VISIBLE else View.GONE
        if (showError) progressBar?.visibility = View.INVISIBLE
        Timber.d(
            "checkForUserVisibleErrors. forceError=%s  showError=%s  isOnline=%s",
            forceError,
            showError,
            NetworkHelper.isOnline(activity)
        )
    }

    private fun updateTitle() {
        mediaFragmentListener?.updateDrawerToggle()
        val currentMediaId = mediaId ?: return

        if (currentMediaId.startsWith(MediaIDHelper.MEDIA_ID_SHOWS_BY_YEAR)) {
            val year = MediaIDHelper.getHierarchy(currentMediaId)[1]
            mediaFragmentListener?.setToolbarTitle(year)
            mediaFragmentListener?.setToolbarSubTitle("")
            return
        }

        if (currentMediaId.startsWith(MediaIDHelper.MEDIA_ID_TRACKS_BY_SHOW)) {
            mediaFragmentListener?.setToolbarTitle(getTitleText())
            mediaFragmentListener?.setToolbarSubTitle(getSubTitle())
            return
        }

        if (MediaIDHelper.MEDIA_ID_ROOT == currentMediaId) {
            mediaFragmentListener?.setToolbarTitle(null)
            return
        }

        val browser = mediaFragmentListener?.mediaBrowser ?: return
        val future = browser.getItem(currentMediaId)
        future.addListener(
            {
                try {
                    val result = future.get()
                    val item = result.value
                    if (item != null) {
                        mediaFragmentListener?.setToolbarTitle(item.mediaMetadata.title)
                    }
                } catch (_: Exception) {
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    private class BrowseAdapter(context: android.app.Activity) :
        ArrayAdapter<MediaItem>(context, R.layout.media_list_item, mutableListOf()) {
        val items: MutableList<MediaItem> get() = (0 until count).mapNotNull { getItem(it) }.toMutableList()

        fun setItems(newItems: List<MediaItem>) {
            clear()
            addAll(newItems)
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            var itemState = MediaItemViewHolder.STATE_NONE
            if (item?.mediaMetadata?.isPlayable == true) {
                itemState = MediaItemViewHolder.STATE_PLAYABLE
                val provider = (context as? MediaBrowserProvider)
                val controller = provider?.mediaBrowser
                val currentPlayingId = controller?.currentMediaItem?.mediaId
                if (currentPlayingId != null && currentPlayingId == item.mediaId) {
                    itemState = if (controller.isPlaying) {
                        MediaItemViewHolder.STATE_PLAYING
                    } else {
                        MediaItemViewHolder.STATE_PAUSED
                    }
                }
            }

            return MediaItemViewHolder.setupView(
                context as android.app.Activity,
                convertView,
                parent,
                item ?: throw IllegalStateException("Missing item"),
                itemState
            )
        }
    }

    interface MediaFragmentListener : MediaBrowserProvider {
        fun onMediaItemSelected(item: MediaItem, siblings: List<MediaItem>)
        fun setToolbarTitle(title: CharSequence?)
        fun setToolbarSubTitle(title: CharSequence?)
        fun updateDrawerToggle()
    }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_SELECTED_TRACK_ID = "selected_track_id"
        private const val LIST_STATE_KEY = "list_state"
        private const val SELECTED_TRACK_ID_KEY = "selected_track_id_state"
    }
}
