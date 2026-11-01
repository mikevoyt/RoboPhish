package com.bayapps.android.robophish.ui

import android.app.DownloadManager
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.ServiceLocator
import com.bayapps.android.robophish.downloads.*
import com.bayapps.android.robophish.utils.MediaIDHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class DownloadsActivity : BaseActivity() {
    private val repository by lazy { DownloadRepository.get(this) }
    private lateinit var list: RecyclerView
    private lateinit var adapter: CollectionAdapter
    private var entries = emptyList<DownloadEntry>()
    private var freeBytes = 0L
    private var readyOnly = false
    private val expanded = mutableSetOf<String>()
    private var pendingPlay: Pair<String, String?>? = null
    private val playbackListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) { message(getString(R.string.offline_playback_error)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.downloads_screen)
        initializeToolbar()
        findViewById<View>(R.id.toolbar).setBackgroundColor(ContextCompat.getColor(this, R.color.offline_ink))
        window.statusBarColor = ContextCompat.getColor(this, R.color.offline_ink)
        findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer_layout)
            .setStatusBarBackgroundColor(ContextCompat.getColor(this, R.color.offline_ink))
        title = getString(R.string.offline_downloads)
        readyOnly = savedInstanceState?.getBoolean("ready_only") ?: false
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded") ?: emptyList())
        list = findViewById(R.id.downloads_list)
        adapter = CollectionAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { repository.store.entries.collect { entries = it; render() } }
                launch {
                    while (true) {
                        try {
                            freeBytes = withContext(Dispatchers.IO) {
                                repository.reconcile()
                                getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.usableSpace ?: 0
                            }
                            render()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Retry on next poll; keep the last known collection. */ }
                        delay(if (entries.any { it.isActive }) 1500 else 5000)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("ready_only", readyOnly)
        outState.putStringArrayList("expanded", ArrayList(expanded))
        super.onSaveInstanceState(outState)
    }

    override fun onMediaControllerConnected() {
        mediaBrowser?.addListener(playbackListener)
        pendingPlay?.let { (show, track) -> pendingPlay = null; play(show, track) }
    }

    override fun onStop() {
        mediaBrowser?.removeListener(playbackListener)
        pendingPlay = null
        super.onStop()
    }

    private fun render() {
        val shows = DownloadShow.group(entries)
        val filtered = shows.filter { !readyOnly || it.ready.isNotEmpty() }
        adapter.submitList(buildList {
            add(Row.Header(shows.size, entries.count { it.status == DownloadStatus.COMPLETED },
                entries.sumOf { it.bytesDownloaded.coerceAtLeast(0) }, freeBytes, repository.wifiOnly, readyOnly))
            if (filtered.isEmpty()) add(Row.Empty(readyOnly))
            else filtered.forEach { add(Row.Show(it, expanded.contains(it.key))) }
        })
    }

    private fun play(showKey: String, trackKey: String? = null) {
        if (mediaBrowser == null) {
            pendingPlay = showKey to trackKey
            message(getString(R.string.offline_loading))
            return
        }
        lifecycleScope.launch {
            val ready = try {
                withContext(Dispatchers.IO) {
                    DownloadShow.group(repository.reconcile()).firstOrNull { it.key == showKey }
                        ?.tracks?.filter(OfflineMedia::isPlayable).orEmpty()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(getString(R.string.offline_operation_failed)); return@launch }
            val index = if (trackKey == null) 0 else ready.indexOfFirst { it.key == trackKey }
            if (ready.isEmpty() || index < 0) { message(getString(R.string.offline_unavailable)); return@launch }
            val controller = mediaBrowser ?: return@launch
            controller.setMediaItems(ready.map(OfflineMedia::toMediaItem), index, 0)
            controller.prepare()
            controller.play()
        }
    }

    private fun retry(tracks: List<DownloadEntry>) = operation {
        val failed = tracks.filter { it.status == DownloadStatus.FAILED }
        val legacy = failed.filter { it.sourceUrl.isNullOrBlank() }
        var recoveryFailed = 0
        legacy.groupBy { it.showId }.forEach { (showId, old) ->
            if (showId == null) { recoveryFailed += old.size; return@forEach }
            val available = ServiceLocator.get(this).musicProvider.getTracksForShow(showId)
                .filter { item -> old.any { it.trackId == MediaIDHelper.extractMusicIDFromMediaID(item.mediaId) } }
            recoveryFailed += repository.retry(old, available).failed
        }
        val result = repository.retry(failed.filter { !it.sourceUrl.isNullOrBlank() })
        when {
            recoveryFailed > 0 -> getString(R.string.offline_missing_source)
            result.failed > 0 -> getString(R.string.offline_enqueue_failed, result.failed)
            else -> null
        }
    }

    private fun confirmRemoval(show: DownloadShow) {
        if (mediaBrowser == null) { message(getString(R.string.offline_loading)); return }
        AlertDialog.Builder(this).setTitle(R.string.offline_remove_title)
            .setMessage(getString(R.string.offline_remove_message, show.tracks.size, size(show.bytes)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.offline_remove) { _, _ ->
                val controller = mediaBrowser
                val ids = show.tracks.map { it.trackId ?: it.key }.toSet()
                if (MediaIDHelper.extractMusicIDFromMediaID(controller?.currentMediaItem?.mediaId ?: "") in ids) {
                    controller?.stop(); controller?.clearMediaItems()
                } else if (controller != null) {
                    for (index in controller.mediaItemCount - 1 downTo 0) {
                        if (MediaIDHelper.extractMusicIDFromMediaID(controller.getMediaItemAt(index).mediaId) in ids) {
                            controller.removeMediaItem(index)
                        }
                    }
                }
                operation { repository.remove(show.tracks); getString(R.string.offline_removed) }
            }.show()
    }

    private fun changeWifi(value: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(if (value) R.string.offline_wifi_only else R.string.offline_mobile_title)
            .setMessage(if (value) R.string.offline_wifi_message else R.string.offline_mobile_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (value) android.R.string.ok else R.string.offline_allow) { _, _ ->
                operation { repository.setWifiOnly(value); null }
            }.show()
    }

    private fun operation(block: suspend () -> String?) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }?.let(::message)
                render()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(getString(R.string.offline_operation_failed)) }
        }
    }

    private fun message(text: String) = Snackbar.make(list, text, Snackbar.LENGTH_LONG).show()
    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)
    private fun duration(ms: Long): String = String.format(Locale.getDefault(), "%d:%02d", ms / 60_000, ms / 1000 % 60)
    private fun status(entry: DownloadEntry): String = when (entry.status) {
        DownloadStatus.COMPLETED -> duration(entry.durationMs ?: 0) + " · " + getString(R.string.offline_saved)
        DownloadStatus.QUEUED -> getString(R.string.offline_queued_status)
        DownloadStatus.DOWNLOADING -> if (entry.progress != null) getString(R.string.offline_downloading, entry.progress) else getString(R.string.offline_in_progress)
        DownloadStatus.WAITING -> getString(when (entry.failureReason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> R.string.offline_waiting_wifi
            DownloadManager.PAUSED_WAITING_TO_RETRY -> R.string.offline_waiting_retry
            else -> if (repository.wifiOnly) R.string.offline_waiting_wifi else R.string.offline_waiting_network
        })
        DownloadStatus.FAILED -> getString(when (entry.failureReason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.offline_no_space
            DownloadReconciler.MISSING_FILE -> R.string.offline_missing_file
            else -> R.string.offline_failed
        })
    }

    private sealed class Row(val id: String) {
        data class Header(val shows: Int, val tracks: Int, val bytes: Long, val free: Long, val wifi: Boolean, val ready: Boolean) : Row("header")
        data class Empty(val filtered: Boolean) : Row("empty")
        data class Show(val show: DownloadShow, val expanded: Boolean) : Row(show.key)
    }

    private inner class CollectionAdapter : ListAdapter<Row, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is Row.Header -> R.layout.offline_header
            is Row.Empty -> R.layout.offline_empty
            is Row.Show -> R.layout.item_download
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = getItem(position)) {
                is Row.Header -> bindHeader(view, row)
                is Row.Empty -> {
                    view.findViewById<TextView>(R.id.offline_empty_title).setText(if (row.filtered) R.string.offline_empty_ready else R.string.offline_empty_title)
                    view.findViewById<View>(R.id.offline_empty_body).visibility = if (row.filtered) View.GONE else View.VISIBLE
                    view.findViewById<View>(R.id.offline_browse).setOnClickListener {
                        startActivity(Intent(this@DownloadsActivity, MusicPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    }
                }
                is Row.Show -> bindShow(view, row)
            }
        }
        private fun bindHeader(view: View, row: Row.Header) {
            view.findViewById<TextView>(R.id.offline_headline).apply {
                setText(if (row.shows > 0) R.string.offline_collection_headline else R.string.offline_headline)
                textSize = if (row.shows > 0) 26f else 30f
            }
            view.findViewById<View>(R.id.offline_intro).visibility = if (row.shows > 0) View.GONE else View.VISIBLE
            listOf(R.id.offline_stats, R.id.offline_stats_divider, R.id.offline_filters).forEach {
                view.findViewById<View>(it).visibility = if (row.shows > 0) View.VISIBLE else View.GONE
            }
            view.findViewById<TextView>(R.id.offline_stats).text = getString(R.string.offline_stats,
                resources.getQuantityString(R.plurals.offline_show_count, row.shows, row.shows),
                resources.getQuantityString(R.plurals.offline_track_count, row.tracks, row.tracks), size(row.bytes))
            view.findViewById<TextView>(R.id.offline_free).text = getString(R.string.offline_free, size(row.free))
            view.findViewById<TextView>(R.id.offline_wifi_hint).setText(if (row.wifi) R.string.offline_wifi_hint else R.string.offline_mobile_hint)
            view.findViewById<SwitchCompat>(R.id.offline_wifi).apply {
                setOnCheckedChangeListener(null); isChecked = row.wifi
                setOnCheckedChangeListener { _, value ->
                    setOnCheckedChangeListener(null); isChecked = row.wifi
                    changeWifi(value)
                    // Rebind after a cancellation too; keep the switch interactive.
                    post { notifyItemChanged(0) }
                }
            }
            listOf(R.id.offline_all to false, R.id.offline_ready_filter to true).forEach { (id, filter) ->
                view.findViewById<AppCompatButton>(id).apply {
                    val selected = row.ready == filter
                    setBackgroundResource(if (selected) R.drawable.offline_primary_bg else R.drawable.offline_secondary_bg)
                    backgroundTintList = null
                    setTextColor(ContextCompat.getColor(context, if (selected) android.R.color.white else R.color.offline_ink))
                    isSelected = selected
                    setOnClickListener { readyOnly = filter; render() }
                }
            }
        }
        private fun bindShow(view: View, row: Row.Show) {
            val show = row.show
            val first = show.first
            val date = runCatching { LocalDate.parse(first.showDate?.replace('.', '-')) }.getOrNull()
            view.findViewById<TextView>(R.id.download_date_tile).text = date?.format(DateTimeFormatter.ofPattern("MMM\ndd", Locale.getDefault())) ?: "PHISH"
            view.findViewById<TextView>(R.id.download_show_date).text = date?.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())) ?: first.showDate ?: getString(R.string.app_name)
            view.findViewById<TextView>(R.id.download_venue).text = first.venue ?: first.album
            view.findViewById<View>(R.id.download_more).apply {
                contentDescription = getString(R.string.offline_show_options, first.showDate ?: first.title)
                setOnClickListener { PopupMenu(this@DownloadsActivity, this).apply {
                    menu.add(R.string.offline_remove).setOnMenuItemClickListener { confirmRemoval(show); true }
                    show()
                } }
            }
            view.findViewById<TextView>(R.id.download_status).apply {
                text = if (show.complete) getString(R.string.offline_ready_status,
                    resources.getQuantityString(R.plurals.offline_track_count, show.tracks.size, show.tracks.size), size(show.bytes))
                    else getString(R.string.offline_partial_status, show.ready.size, show.tracks.size, size(show.bytes))
                setTextColor(ContextCompat.getColor(context, if (show.failed) R.color.offline_orange else R.color.offline_green))
            }
            view.findViewById<ProgressBar>(R.id.download_progress).apply {
                visibility = if (show.complete) View.GONE else View.VISIBLE
                progress = show.progress
            }
            view.findViewById<TextView>(R.id.download_detail).apply {
                visibility = if (show.complete) View.GONE else View.VISIBLE
                text = when {
                    show.complete -> ""
                    show.tracks.any { it.status == DownloadStatus.DOWNLOADING } -> getString(R.string.offline_downloading, show.progress)
                    show.active -> status(show.tracks.first { it.isActive })
                    else -> status(show.tracks.first { it.status == DownloadStatus.FAILED })
                }
            }
            view.findViewById<AppCompatButton>(R.id.download_play).apply {
                backgroundTintList = null
                isEnabled = show.ready.isNotEmpty(); alpha = if (isEnabled) 1f else 0.4f
                setText(if (show.complete) R.string.offline_play else R.string.offline_play_saved)
                setOnClickListener { play(show.key) }
            }
            view.findViewById<AppCompatButton>(R.id.download_expand).apply {
                backgroundTintList = null
                setText(if (row.expanded) R.string.offline_collapse else R.string.offline_expand)
                setOnClickListener { if (!expanded.add(show.key)) expanded.remove(show.key); render() }
            }
            view.findViewById<View>(R.id.download_retry).apply {
                visibility = if (show.failed) View.VISIBLE else View.GONE
                setOnClickListener { retry(show.tracks) }
            }
            view.findViewById<LinearLayout>(R.id.download_tracks).apply {
                removeAllViews()
                visibility = if (row.expanded) View.VISIBLE else View.GONE
                if (row.expanded) {
                    var previousSet: String? = null
                    show.tracks.forEachIndexed { index, track ->
                        if (track.setName != null && track.setName != previousSet) {
                            addView(TextView(context).apply {
                                text = track.setName; textSize = 12f
                                setTextColor(ContextCompat.getColor(context, R.color.offline_green))
                                setPadding(0, (20 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                            }); previousSet = track.setName
                        }
                        val trackView = layoutInflater.inflate(R.layout.offline_track, this, false)
                        trackView.findViewById<TextView>(R.id.offline_track_number).text = (index + 1).toString()
                        trackView.findViewById<TextView>(R.id.offline_track_title).text = track.title
                        trackView.findViewById<TextView>(R.id.offline_track_status).text = status(track)
                        trackView.findViewById<ImageView>(R.id.offline_track_icon).setImageResource(when (track.status) {
                            DownloadStatus.COMPLETED -> R.drawable.ic_offline_play
                            DownloadStatus.FAILED -> R.drawable.ic_offline_retry
                            else -> R.drawable.ic_offline_pending
                        })
                        trackView.isEnabled = !track.isActive
                        trackView.contentDescription = "${track.title}, ${status(track)}"
                        trackView.setOnClickListener {
                            if (track.status == DownloadStatus.COMPLETED) play(show.key, track.key) else retry(listOf(track))
                        }
                        addView(trackView)
                    }
                }
            }
        }
    }
}
