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
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.viewpager2.widget.ViewPager2
import com.bayapps.android.robophish.BuildConfig
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.utils.Downloader
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.bayapps.android.robophish.utils.NetworkHelper
import com.bayapps.android.robophish.utils.ShowDetailsCache
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale

/**
 * A Fragment that lists music catalog items from a MediaBrowser and
 * sends selected media items to the attached listener to play.
 */
class MediaBrowserFragment : Fragment() {
    private var mediaId: String? = null
    private var mediaFragmentListener: MediaFragmentListener? = null
    private var browserAdapter: BrowseAdapter? = null
    private var showTrackAdapter: SectionedTracksAdapter? = null
    private var errorView: View? = null
    private var errorMessage: View? = null
    private var progressBar: View? = null
    private var showData: JSONObject? = null
    private var setlistWebView: WebView? = null
    private var reviewsWebView: WebView? = null
    private var tapernotesWebView: WebView? = null
    private var setlistHtml: String? = null
    private var reviewsHtml: String? = null
    private var taperNotesHtml: String? = null

    private var listView: ListView? = null
    private var pendingListState: Parcelable? = null
    private var pendingSelectedTrackId: String? = null
    private var isShowView: Boolean = false

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
            showTrackAdapter?.notifyDataSetChanged()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            browserAdapter?.notifyDataSetChanged()
            showTrackAdapter?.notifyDataSetChanged()
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
            isShowView = true
            val view = inflater.inflate(R.layout.fragment_list_show, container, false)
            registerMenuProvider()

            val viewPager = view.findViewById<ViewPager2>(R.id.viewpager)
            val cachedShowId = MediaIDHelper.extractShowFromMediaID(mediaId)
            val cachedHtml = cachedShowId?.let { ShowDetailsCache.get(it) }
            setlistHtml = cachedHtml?.setlist
            reviewsHtml = cachedHtml?.reviews
            taperNotesHtml = cachedHtml?.taperNotes
            val pagerAdapter = ShowPagerAdapter(
                onTracksViewCreated = { pageView ->
                    val list = pageView.findViewById<ListView>(R.id.list_view)
                    setupListView(list, isShowTracks = true)
                },
                onSetlistViewCreated = { pageView ->
                    setlistWebView = pageView.findViewById(R.id.setlist_webview)
                    setlistWebView?.settings?.javaScriptEnabled = true
                    setlistHtml?.let { html -> setlistWebView?.loadData(html, "text/html", null) }
                },
                onReviewsViewCreated = { pageView ->
                    reviewsWebView = pageView.findViewById(R.id.reviews_webview)
                    reviewsWebView?.settings?.javaScriptEnabled = true
                    reviewsHtml?.let { html -> reviewsWebView?.loadData(html, "text/html", null) }
                },
                onTaperNotesViewCreated = { pageView ->
                    tapernotesWebView = pageView.findViewById(R.id.tapernotes_webview)
                    tapernotesWebView?.settings?.javaScriptEnabled = true
                    taperNotesHtml?.let { html -> tapernotesWebView?.loadData(html, "text/html", null) }
                }
            )
            viewPager.adapter = pagerAdapter
            viewPager.offscreenPageLimit = 3

            val tabLayout = view.findViewById<TabLayout>(R.id.sliding_tabs)
            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = pagerAdapter.getPageTitle(position)
            }.attach()

            val setlistDate = getSubTitle()?.replace(".", "-")
                ?: cachedHtml?.showDate
            if (!setlistDate.isNullOrBlank()) {
                loadSetlistAndReviews(setlistDate, cachedShowId)
            }

            val showId = MediaIDHelper.extractShowFromMediaID(mediaId)
            if (!showId.isNullOrBlank()) {
                lifecycleScope.launch {
                    if (!taperNotesHtml.isNullOrBlank()) {
                        return@launch
                    }
                    val response = fetchJson(
                        "https://phish.in/api/v1/shows/$showId",
                        headers = mapOf("Authorization" to "Bearer ${BuildConfig.PHISHIN_API_KEY}")
                    )
                    if (!isAdded) return@launch
                    if (response == null) {
                        taperNotesHtml = "<div>Error loading Taper Notes</div>"
                        tapernotesWebView?.loadData(taperNotesHtml!!, "text/html", null)
                        updateCache(showId, null, null, taperNotesHtml)
                        return@launch
                    }
                    try {
                        showData = response
                        val data = response.getJSONObject("data")
                        val responseDate = data.optString("date", "").ifBlank { null }
                        var tapernotes = data.getString("taper_notes")
                        if (tapernotes == "null") tapernotes = "Not available"
                        val notesSubs = tapernotes.replace("\n", "<br/>")
                        taperNotesHtml = notesSubs
                        tapernotesWebView?.loadData(taperNotesHtml!!, "text/html", null)
                        updateCache(showId, null, null, taperNotesHtml, responseDate)
                        if (!responseDate.isNullOrBlank()) {
                            loadSetlistAndReviews(responseDate, showId)
                        }
                    } catch (e: JSONException) {
                        Timber.e(e, "Error parsing taper notes response")
                        taperNotesHtml = "<div>Error loading Taper Notes</div>"
                        tapernotesWebView?.loadData(taperNotesHtml!!, "text/html", null)
                        updateCache(showId, null, null, taperNotesHtml)
                    }
                }
            }
            view
        } else {
            isShowView = false
            inflater.inflate(R.layout.fragment_list, container, false)
        }

        errorView = rootView.findViewById(R.id.playback_error)
        errorMessage = rootView.findViewById(R.id.error_message)
        progressBar = rootView.findViewById(R.id.progress_bar)
        progressBar?.visibility = View.VISIBLE

        browserAdapter = BrowseAdapter(requireActivity())

        if (mediaId == null || !MediaIDHelper.isShow(mediaId)) {
            val list = rootView.findViewById<ListView>(R.id.list_view)
            setupListView(list)
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
                            if (isShowView) {
                                showTrackAdapter?.setItems(items)
                            } else {
                                browserAdapter?.setItems(items)
                                browserAdapter?.notifyDataSetChanged()
                            }
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
            showTrackAdapter?.notifyDataSetChanged()
        }
    }

    private fun restoreListStateIfNeeded(): Boolean {
        val state = pendingListState ?: return false
        listView?.onRestoreInstanceState(state)
        pendingListState = null
        return true
    }

    private fun setupListView(list: ListView, isShowTracks: Boolean = false) {
        listView = list
        if (isShowTracks) {
            listView?.divider = null
            listView?.dividerHeight = 0
            showTrackAdapter = SectionedTracksAdapter(requireActivity())
            listView?.adapter = showTrackAdapter
        } else {
            listView?.adapter = browserAdapter
        }
        listView?.setOnItemClickListener { _, _, position, _ ->
            checkForUserVisibleErrors(false)
            val item = if (isShowTracks) {
                showTrackAdapter?.getTrackItem(position)
            } else {
                browserAdapter?.getItem(position)
            }
            if (item != null) {
                val siblings = if (isShowTracks) {
                    showTrackAdapter?.getTrackItems() ?: emptyList()
                } else {
                    browserAdapter?.items ?: emptyList()
                }
                mediaFragmentListener?.onMediaItemSelected(item, siblings)
            }
        }
    }

    private fun updateCache(
        showId: String?,
        setlist: String? = null,
        reviews: String? = null,
        taperNotes: String? = null,
        showDate: String? = null
    ) {
        if (showId.isNullOrBlank()) return
        val existing = ShowDetailsCache.get(showId)
        ShowDetailsCache.put(
            showId,
            ShowDetailsCache.Html(
                setlist = setlist ?: existing?.setlist,
                reviews = reviews ?: existing?.reviews,
                taperNotes = taperNotes ?: existing?.taperNotes,
                showDate = showDate ?: existing?.showDate
            )
        )
    }

    private fun loadSetlistAndReviews(showDate: String, showId: String?) {
        lifecycleScope.launch {
            if (!setlistHtml.isNullOrBlank() && !reviewsHtml.isNullOrBlank()) {
                return@launch
            }
            val setlistResponse = fetchJson(
                "https://api.phish.net/v3/setlists/get",
                mapOf(
                    "showdate" to showDate,
                    "apikey" to BuildConfig.PHISHNET_API_KEY
                ),
                client = okHttpNoAuthClient
            )
            if (!isAdded) return@launch
            if (setlistResponse == null) {
                setlistHtml = "<div>Error loading Setlist</div>"
                reviewsHtml = "<div>Error loading Reviews</div>"
                setlistWebView?.loadData(setlistHtml!!, "text/html", null)
                reviewsWebView?.loadData(reviewsHtml!!, "text/html", null)
                updateCache(showId, setlistHtml, reviewsHtml, null, showDate)
                return@launch
            }
            try {
                val result = setlistResponse
                    .getJSONObject("response")
                    .getJSONArray("data")
                    .getJSONObject(0)
                val phishNetShowId = result.getInt("showid")
                val location = result.getString("location")
                val venue = result.getString("venue")
                val header = "<h1>$venue</h1><h2>$location</h2>"
                val setlistdata = result.getString("setlistdata")
                val setlistnotes = result.getString("setlistnotes")
                setlistHtml = header + setlistdata + setlistnotes
                setlistWebView?.loadData(setlistHtml!!, "text/html", null)

                val reviewsResponse = fetchJson(
                    "https://api.phish.net/v3/reviews/query",
                    mapOf(
                        "showid" to phishNetShowId.toString(),
                        "apikey" to BuildConfig.PHISHNET_API_KEY
                    ),
                    client = okHttpNoAuthClient
                )
                if (!isAdded) return@launch
                if (reviewsResponse == null) {
                    reviewsHtml = "<div>Error loading Reviews</div>"
                    reviewsWebView?.loadData(reviewsHtml!!, "text/html", null)
                    updateCache(showId, setlistHtml, reviewsHtml, null, showDate)
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
                reviewsHtml = display.toString()
                reviewsWebView?.loadData(reviewsHtml!!, "text/html", null)
                updateCache(showId, setlistHtml, reviewsHtml, null, showDate)
            } catch (e: JSONException) {
                Timber.e(e, "Error parsing setlist/reviews response")
                setlistHtml = "<div>Error loading Setlist</div>"
                reviewsHtml = "<div>Error loading Reviews</div>"
                setlistWebView?.loadData(setlistHtml!!, "text/html", null)
                reviewsWebView?.loadData(reviewsHtml!!, "text/html", null)
                updateCache(showId, setlistHtml, reviewsHtml, null, showDate)
            }
        }
    }

    private fun restoreSelectionIfNeeded() {
        val selectedTrackId = pendingSelectedTrackId ?: return
        val position = if (isShowView) {
            showTrackAdapter?.findPositionByTrackId(selectedTrackId)
        } else {
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
            targetPosition
        }
        val resolvedPosition = position ?: return
        listView?.post { listView?.setSelection(resolvedPosition) }
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

    private class SectionedTracksAdapter(private val activity: android.app.Activity) : BaseAdapter() {
        private val rows: MutableList<TrackRow> = mutableListOf()
        private var trackItems: List<MediaItem> = emptyList()

        fun setItems(items: List<MediaItem>) {
            trackItems = items
            rows.clear()
            rows.addAll(buildTrackRows(items))
            notifyDataSetChanged()
        }

        fun getTrackItem(position: Int): MediaItem? {
            return (getItem(position) as? TrackRow.Track)?.item
        }

        fun getTrackItems(): List<MediaItem> = trackItems

        fun findPositionByTrackId(trackId: String): Int? {
            for (i in rows.indices) {
                val row = rows[i]
                if (row is TrackRow.Track) {
                    val mediaId = row.item.mediaId
                    val rowTrackId = MediaIDHelper.extractMusicIDFromMediaID(mediaId)
                    if (rowTrackId == trackId) {
                        return i
                    }
                }
            }
            return null
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): TrackRow = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is TrackRow.Header -> VIEW_TYPE_HEADER
                is TrackRow.Track -> VIEW_TYPE_TRACK
            }
        }

        override fun getViewTypeCount(): Int = 2

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = rows[position] is TrackRow.Track

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            return when (row) {
                is TrackRow.Header -> {
                    val view = convertView ?: LayoutInflater.from(activity)
                        .inflate(R.layout.show_track_header_item, parent, false)
                    view.findViewById<TextView>(R.id.set_header_title).text = row.title
                    view
                }
                is TrackRow.Track -> {
                    val item = row.item
                    var itemState = MediaItemViewHolder.STATE_NONE
                    if (item.mediaMetadata.isPlayable == true) {
                        itemState = MediaItemViewHolder.STATE_PLAYABLE
                        val provider = activity as? MediaBrowserProvider
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
                    MediaItemViewHolder.setupView(
                        activity,
                        convertView,
                        parent,
                        item,
                        itemState
                    )
                }
            }
        }

        private fun buildTrackRows(items: List<MediaItem>): List<TrackRow> {
            val rows = mutableListOf<TrackRow>()
            var currentSet: String? = null
            items.forEach { item ->
                val setLabel = resolveSetLabel(item) ?: DEFAULT_SET_LABEL
                if (setLabel != currentSet) {
                    rows.add(TrackRow.Header(setLabel))
                    currentSet = setLabel
                }
                rows.add(TrackRow.Track(item))
            }
            return rows
        }

        private fun resolveSetLabel(item: MediaItem): String? {
            val extras = item.mediaMetadata.extras ?: return null
            val setName = extras.getString(MusicProvider.EXTRA_TRACK_SET_NAME)
            if (!setName.isNullOrBlank()) {
                return setName.trim()
            }
            val rawSet = extras.getString(MusicProvider.EXTRA_TRACK_SET)?.trim()
            if (rawSet.isNullOrBlank()) {
                return null
            }
            return when (rawSet.uppercase(Locale.US)) {
                "1", "SET 1", "SET1" -> "Set 1"
                "2", "SET 2", "SET2" -> "Set 2"
                "3", "SET 3", "SET3" -> "Set 3"
                "E", "ENCORE" -> "Encore"
                else -> rawSet.replaceFirstChar { it.uppercase(Locale.US) }
            }
        }

        companion object {
            private const val VIEW_TYPE_HEADER = 0
            private const val VIEW_TYPE_TRACK = 1
            private const val DEFAULT_SET_LABEL = "Tracks"
        }
    }

    private sealed class TrackRow {
        data class Header(val title: String) : TrackRow()
        data class Track(val item: MediaItem) : TrackRow()
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
