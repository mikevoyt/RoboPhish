package com.bayapps.android.robophish.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.viewpager.widget.ViewPager
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.BuildConfig
import com.bayapps.android.robophish.utils.Downloader
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.bayapps.android.robophish.utils.NetworkHelper
import com.loopj.android.http.AsyncHttpClient
import com.loopj.android.http.JsonHttpResponseHandler
import com.loopj.android.http.RequestParams
import com.google.android.material.tabs.TabLayout
import cz.msebera.android.httpclient.Header
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * A Fragment that lists all the various browsable queues available
 * from a MediaBrowserService.
 */
class MediaBrowserFragment : Fragment() {
    private var browserAdapter: BrowseAdapter? = null
    private var mediaId: String? = null
    private var mediaFragmentListener: MediaFragmentListener? = null
    private var errorView: View? = null
    private var errorMessage: TextView? = null
    private var progressBar: ProgressBar? = null
    private var showData: JSONObject? = null

    private var oldOnline = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handleNetworkChange(true)
        }

        override fun onLost(network: Network) {
            handleNetworkChange(false)
        }
    }

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            if (metadata == null) return
            Timber.d("Received metadata change to media %s", metadata.description.mediaId)
            browserAdapter?.notifyDataSetChanged()
            progressBar?.visibility = View.INVISIBLE
        }

        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Timber.d("Received state change: %s", state)
            checkForUserVisibleErrors(false)
            browserAdapter?.notifyDataSetChanged()
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(
            parentId: String,
            children: List<MediaBrowserCompat.MediaItem>
        ) {
            try {
                Timber.d("fragment onChildrenLoaded, parentId=%s, count=%s", parentId, children.size)
                checkForUserVisibleErrors(children.isEmpty())
                progressBar?.visibility = View.INVISIBLE
                browserAdapter?.clear()
                children.forEach { item -> browserAdapter?.add(item) }
                browserAdapter?.notifyDataSetChanged()
            } catch (t: Throwable) {
                Timber.e(t, "Error on childrenloaded")
            }
        }

        override fun onError(id: String) {
            Timber.e("browse fragment subscription onError, id=%s", id)
            Toast.makeText(activity, R.string.error_loading_media, Toast.LENGTH_LONG).show()
            checkForUserVisibleErrors(true)
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

            val setlistParams = RequestParams().apply {
                put("showdate", getSubTitle()?.replace(".", "-"))
                put("apikey", "C01AEE2002E80723E9E7")
            }
            val setlistClient = AsyncHttpClient()
            setlistClient.get(
                "https://api.phish.net/v3/setlists/get",
                setlistParams,
                object : JsonHttpResponseHandler() {
                    override fun onSuccess(
                        statusCode: Int,
                        headers: Array<Header>,
                        response: JSONObject
                    ) {
                        super.onSuccess(statusCode, headers, response)
                        try {
                            val result = response
                                .getJSONObject("response")
                                .getJSONArray("data")
                                .getJSONObject(0)
                            val showId = result.getInt("showid")
                            val location = result.getString("location")
                            val venue = result.getString("venue")
                            val header =
                                "<h1>$venue</h1><h2>$location</h2>"
                            val setlistdata = result.getString("setlistdata")
                            val setlistnotes = result.getString("setlistnotes")
                            setlistWebView.loadData(
                                header + setlistdata + setlistnotes,
                                "text/html",
                                null
                            )

                            val reviewsParams = RequestParams().apply {
                                put("showid", showId)
                                put("apikey", "C01AEE2002E80723E9E7")
                            }
                            val reviewsClient = AsyncHttpClient()
                            reviewsClient.get(
                                "https://api.phish.net/v3/reviews/query",
                                reviewsParams,
                                object : JsonHttpResponseHandler() {
                                    override fun onSuccess(
                                        statusCode: Int,
                                        headers: Array<Header>,
                                        response: JSONObject
                                    ) {
                                        super.onSuccess(statusCode, headers, response)
                                        try {
                                            val reviewsData = response
                                                .getJSONObject("response")
                                                .getJSONArray("data")
                                            val display = StringBuilder()
                                            for (i in 0 until reviewsData.length()) {
                                                val entry = reviewsData.getJSONObject(i)
                                                val author = entry.getString("username")
                                                val review = entry.getString("reviewtext")
                                                val reviewDate = entry.getString("posted_date")
                                                val reviewSubs = review.replace("\n", "<br/>")
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
                                            reviewsWebView.loadData(
                                                "<div>Error loading Reviews</div>",
                                                "text/html",
                                                null
                                            )
                                        }
                                    }

                                    override fun onFailure(
                                        statusCode: Int,
                                        headers: Array<Header>,
                                        throwable: Throwable,
                                        errorResponse: JSONObject?
                                    ) {
                                        super.onFailure(statusCode, headers, throwable, errorResponse)
                                        reviewsWebView.loadData(
                                            "<div>Error loading Reviews</div>",
                                            "text/html",
                                            null
                                        )
                                    }
                                }
                            )
                        } catch (e: JSONException) {
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

                    override fun onFailure(
                        statusCode: Int,
                        headers: Array<Header>,
                        throwable: Throwable,
                        errorResponse: JSONObject?
                    ) {
                        super.onFailure(statusCode, headers, throwable, errorResponse)
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
            )

            val tapernotesWebview = view.findViewById<WebView>(R.id.tapernotes_webview)
            tapernotesWebview.settings.javaScriptEnabled = true

            val showId = MediaIDHelper.extractShowFromMediaID(mediaId)
            val tapernotesClient = AsyncHttpClient()
            tapernotesClient.addHeader("Authorization", "Bearer ${BuildConfig.PHISHIN_API_KEY}")
            tapernotesClient.get(
                "https://phish.in/api/v1/shows/$showId",
                null,
                object : JsonHttpResponseHandler() {
                    override fun onSuccess(
                        statusCode: Int,
                        headers: Array<Header>,
                        response: JSONObject
                    ) {
                        super.onSuccess(statusCode, headers, response)
                        try {
                            showData = response
                            val data = response.getJSONObject("data")
                            var tapernotes = data.getString("taper_notes")
                            if (tapernotes == "null") tapernotes = "Not available"
                            val notesSubs = tapernotes.replace("\n", "<br/>")
                            tapernotesWebview.loadData(notesSubs, "text/html", null)
                        } catch (e: JSONException) {
                            tapernotesWebview.loadData(
                                "<div>Error loading Taper Notes</div>",
                                "text/html",
                                null
                            )
                        }
                    }

                    override fun onFailure(
                        statusCode: Int,
                        headers: Array<Header>,
                        throwable: Throwable,
                        errorResponse: JSONObject?
                    ) {
                        super.onFailure(statusCode, headers, throwable, errorResponse)
                        tapernotesWebview.loadData(
                            "<div>Error loading Taper Notes</div>",
                            "text/html",
                            null
                        )
                    }
                }
            )
            view
        } else {
            inflater.inflate(R.layout.fragment_list, container, false)
        }

        errorView = rootView.findViewById(R.id.playback_error)
        errorMessage = rootView.findViewById(R.id.error_message)
        progressBar = rootView.findViewById(R.id.progress_bar)
        progressBar?.visibility = View.VISIBLE

        browserAdapter = BrowseAdapter(requireActivity())

        val listView = rootView.findViewById<ListView>(R.id.list_view)
        listView.adapter = browserAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            checkForUserVisibleErrors(false)
            val item = browserAdapter?.getItem(position)
            if (item != null) {
                mediaFragmentListener?.onMediaItemSelected(item)
            }
        }

        return rootView
    }

    override fun onStart() {
        super.onStart()
        val mediaBrowser = mediaFragmentListener?.mediaBrowser
        Timber.d("fragment.onStart, mediaId=%s onConnected=%s", mediaId, mediaBrowser?.isConnected)
        if (mediaBrowser?.isConnected == true) {
            onConnected()
        }
        oldOnline = NetworkHelper.isOnline(requireContext())
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    override fun onStop() {
        super.onStop()
        val mediaBrowser = mediaFragmentListener?.mediaBrowser
        if (mediaBrowser != null && mediaBrowser.isConnected && mediaId != null) {
            mediaBrowser.unsubscribe(mediaId!!)
        }
        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        controller?.unregisterCallback(mediaControllerCallback)
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

    fun setMediaId(title: String?, subtitle: String?, mediaId: String?) {
        val args = Bundle(3)
        args.putString(ARG_MEDIA_ID, mediaId)
        args.putString(ARG_TITLE, title)
        args.putString(ARG_SUBTITLE, subtitle)
        arguments = args
    }

    fun onConnected() {
        if (isDetached) {
            return
        }
        mediaId = getMediaId()
        if (mediaId == null) {
            mediaId = mediaFragmentListener?.mediaBrowser?.root
        }
        updateTitle()

        val resolvedMediaId = mediaId ?: return
        mediaFragmentListener?.mediaBrowser?.unsubscribe(resolvedMediaId)
        mediaFragmentListener?.mediaBrowser?.subscribe(resolvedMediaId, subscriptionCallback)

        val controller = (activity as? BaseActivity)?.getSupportMediaController()
        controller?.registerCallback(mediaControllerCallback)
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

    private fun checkForUserVisibleErrors(forceError: Boolean) {
        var showError = forceError
        val activity = activity ?: return
        if (!NetworkHelper.isOnline(activity)) {
            errorMessage?.setText(R.string.error_no_connection)
            showError = true
        } else {
            val controller = (activity as? BaseActivity)?.getSupportMediaController()
            if (controller?.metadata != null &&
                controller.playbackState != null &&
                controller.playbackState.state == PlaybackStateCompat.STATE_ERROR &&
                controller.playbackState.errorMessage != null
            ) {
                errorMessage?.text = controller.playbackState.errorMessage
                showError = true
            } else if (forceError) {
                errorMessage?.setText(R.string.error_loading_media)
                showError = true
            }
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

        mediaFragmentListener?.mediaBrowser?.getItem(
            currentMediaId,
            object : MediaBrowserCompat.ItemCallback() {
                override fun onItemLoaded(item: MediaBrowserCompat.MediaItem) {
                    mediaFragmentListener?.setToolbarTitle(item.description.title)
                }
            }
        )
    }

    private class BrowseAdapter(context: Activity) :
        ArrayAdapter<MediaBrowserCompat.MediaItem>(context, R.layout.media_list_item, mutableListOf()) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            var itemState = MediaItemViewHolder.STATE_NONE
            if (item?.isPlayable == true) {
                itemState = MediaItemViewHolder.STATE_PLAYABLE
                val controller = (context as BaseActivity).getSupportMediaController()
                if (controller?.metadata != null) {
                    val currentPlaying = controller.metadata.description.mediaId
                    val musicId = item.description.mediaId?.let {
                        MediaIDHelper.extractMusicIDFromMediaID(it)
                    }
                    if (currentPlaying != null && currentPlaying == musicId) {
                        val pbState = controller.playbackState
                        itemState = when (pbState?.state) {
                            null, PlaybackStateCompat.STATE_ERROR -> MediaItemViewHolder.STATE_NONE
                            PlaybackStateCompat.STATE_PLAYING -> MediaItemViewHolder.STATE_PLAYING
                            else -> MediaItemViewHolder.STATE_PAUSED
                        }
                    }
                }
            }

            return MediaItemViewHolder.setupView(
                context as Activity,
                convertView,
                parent,
                item!!.description,
                itemState
            )
        }
    }

    interface MediaFragmentListener : MediaBrowserProvider {
        fun onMediaItemSelected(item: MediaBrowserCompat.MediaItem)
        fun setToolbarTitle(title: CharSequence?)
        fun setToolbarSubTitle(title: CharSequence?)
        fun updateDrawerToggle()
    }

    companion object {
        private const val ARG_MEDIA_ID = "media_id"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
    }
}
