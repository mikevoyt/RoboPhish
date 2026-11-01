package com.bayapps.android.robophish.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bayapps.android.robophish.R
import com.bayapps.android.robophish.audio.offline.EnhancementEntry
import com.bayapps.android.robophish.audio.offline.EnhancementRepository
import com.bayapps.android.robophish.audio.offline.EnhancementStatus
import com.bayapps.android.robophish.audio.SavedVersionPlayback
import com.bayapps.android.robophish.downloads.DownloadEntry
import com.bayapps.android.robophish.downloads.DownloadRepository
import com.bayapps.android.robophish.downloads.DownloadShow
import com.bayapps.android.robophish.downloads.DownloadStatus
import com.bayapps.android.robophish.downloads.OfflineMedia
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved versions are explicit choices. Neither progress nor completion changes the player. */
class SavedAudioActivity : BaseActivity() {
    private val repository by lazy { EnhancementRepository.get(this) }
    private val downloads by lazy { DownloadRepository.get(this) }
    private lateinit var list: RecyclerView
    private lateinit var adapter: VersionsAdapter
    private var originals = emptyList<DownloadEntry>()
    private var versions = emptyList<EnhancementEntry>()
    private var freeBytes = 0L
    private val busy = mutableSetOf<String>()
    private var pendingPlay: String? = null

    private val playbackListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = render()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = render()
        override fun onPlayerError(error: PlaybackException) {
            val item = mediaBrowser?.currentMediaItem
            val canFallback = item != null && SavedVersionPlayback.originalUri(item) != null
            message(getString(if (canFallback) R.string.saved_audio_falling_back else R.string.offline_playback_error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.saved_audio_screen)
        initializeToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val ink = ContextCompat.getColor(this, R.color.offline_ink)
        findViewById<View>(R.id.toolbar).setBackgroundColor(ink)
        window.statusBarColor = ink
        title = getString(R.string.saved_audio_title)
        list = findViewById(R.id.saved_audio_list)
        adapter = VersionsAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        (list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { downloads.store.entries.collect { originals = it; render() } }
                launch { repository.store.entries.collect { versions = it; render() } }
                launch {
                    while (true) {
                        try {
                            freeBytes = withContext(Dispatchers.IO) {
                                downloads.reconcile()
                                getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.usableSpace ?: 0L
                            }
                            render()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Retain the last known state and retry on the next poll. */ }
                        delay(5_000)
                    }
                }
            }
        }
    }

    override fun onMediaControllerConnected() {
        mediaBrowser?.addListener(playbackListener)
        render()
        pendingPlay?.let { key -> pendingPlay = null; play(key) }
    }

    override fun onStop() {
        mediaBrowser?.removeListener(playbackListener)
        pendingPlay = null
        super.onStop()
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        val byTrack = versions.associateBy { it.trackKey }
        val readyOriginals = DownloadShow.group(originals.filter { it.status == DownloadStatus.COMPLETED })
            .flatMap { it.tracks }
        val originalKeys = readyOriginals.map { it.key }.toSet()
        val current = mediaBrowser?.currentMediaItem
        val nowPlaying = current?.mediaMetadata?.title?.toString()?.let { name ->
            val enhanced = current.mediaMetadata.extras?.getBoolean(SavedVersionPlayback.ENHANCED) == true
            getString(R.string.saved_audio_now_playing, name,
                getString(if (enhanced) R.string.saved_audio_enhanced else R.string.saved_audio_original))
        }
        adapter.submitList(buildList {
            add(Row.Header(versions.count { it.status == EnhancementStatus.READY },
                versions.count { it.status == EnhancementStatus.UNCHANGED }, versions.count { it.isActive },
                versions.sumOf { it.outputBytes.coerceAtLeast(0) }, freeBytes, nowPlaying))
            readyOriginals.forEach { source -> add(Row.Track(source.key, source, byTrack[source.key], source.key in busy)) }
            // Keep orphan records visible so their files can still be removed from this screen.
            versions.filterNot { it.trackKey in originalKeys }.forEach {
                add(Row.Track(it.trackKey, null, it, it.trackKey in busy))
            }
            if (size == 1) add(Row.Empty)
        })
    }

    private fun choose(key: String, enhanced: Boolean) = operation(key) {
        if (!repository.select(key, enhanced)) {
            repository.select(key, false)
            getString(R.string.saved_audio_unavailable)
        }
        else getString(if (enhanced) R.string.saved_audio_selected_enhanced else R.string.saved_audio_selected_original)
    }

    private fun play(key: String) {
        if (mediaBrowser == null) {
            pendingPlay = key
            message(getString(R.string.saved_audio_loading))
            return
        }
        lifecycleScope.launch {
            val source = try {
                withContext(Dispatchers.IO) { downloads.reconcile().firstOrNull { it.key == key && OfflineMedia.isPlayable(it) } }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(getString(R.string.saved_audio_operation_failed)); return@launch }
            if (source == null) { message(getString(R.string.saved_audio_missing_original)); return@launch }
            val controller = mediaBrowser ?: return@launch
            // The service resolves the chosen saved version. It always has the original as fallback.
            controller.setMediaItem(OfflineMedia.toMediaItem(source))
            controller.prepare()
            controller.play()
        }
    }

    private fun action(row: Row.Track) {
        val entry = row.version
        when {
            entry?.isActive == true -> operation(row.key) { repository.cancel(row.key); null }
            entry?.status == EnhancementStatus.READY -> confirmRemoval(entry)
            row.original == null -> operation(row.key) { repository.remove(row.key); null }
            entry != null -> operation(row.key) { repository.retry(row.key); null }
            else -> operation(row.key) { repository.enqueue(row.original); null }
        }
    }

    private fun confirmRemoval(entry: EnhancementEntry) {
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_audio_remove_title)
            .setMessage(getString(R.string.saved_audio_remove_message, entry.title, size(entry.outputBytes)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.saved_audio_remove) { _, _ ->
                operation(entry.trackKey) {
                    repository.remove(entry.trackKey)
                    getString(R.string.saved_audio_removed)
                }
            }.show()
    }

    private fun showAboutProcessing() {
        val details = listOf(R.string.saved_audio_eyebrow, R.string.saved_audio_quality_hint,
            R.string.saved_audio_storage_hint, R.string.saved_audio_choose_hint).joinToString("\n\n") { getString(it) }
        AlertDialog.Builder(this).setTitle(R.string.saved_audio_about_title)
            .setMessage(details).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun operation(key: String, block: suspend () -> String?) {
        if (!busy.add(key)) return
        render()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }?.let(::message)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message(getString(R.string.saved_audio_operation_failed)) }
            finally { busy.remove(key); render() }
        }
    }

    private fun status(row: Row.Track): String {
        val entry = row.version
        if (row.original == null) return getString(R.string.saved_audio_missing_original)
        return when (entry?.status) {
            null -> getString(R.string.saved_audio_original_ready)
            EnhancementStatus.QUEUED -> getString(R.string.saved_audio_queued)
            EnhancementStatus.ANALYZING -> getString(R.string.saved_audio_analyzing, entry.progress)
            EnhancementStatus.PROCESSING -> getString(R.string.saved_audio_processing, entry.progress)
            EnhancementStatus.READY -> getString(R.string.saved_audio_ready, size(entry.outputBytes))
            EnhancementStatus.UNCHANGED -> getString(R.string.saved_audio_unchanged)
            EnhancementStatus.FAILED -> entry.error?.takeIf { it.isNotBlank() }
                ?: getString(R.string.saved_audio_failed)
            EnhancementStatus.CANCELLED -> getString(R.string.saved_audio_cancelled)
        }
    }

    private fun message(text: String) = Snackbar.make(list, text, Snackbar.LENGTH_LONG).show()
    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes.coerceAtLeast(0))

    private sealed class Row(val id: String) {
        data class Header(val ready: Int, val unchanged: Int, val active: Int, val bytes: Long, val free: Long, val nowPlaying: String?) : Row("header")
        data class Track(val key: String, val original: DownloadEntry?, val version: EnhancementEntry?, val busy: Boolean) : Row("track:$key")
        data object Empty : Row("empty")
    }

    private inner class VersionsAdapter : ListAdapter<Row, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
        override fun getItemViewType(position: Int) = when (getItem(position)) {
            is Row.Header -> R.layout.saved_audio_header
            is Row.Track -> R.layout.saved_audio_track
            Row.Empty -> R.layout.saved_audio_empty
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(viewType, parent, false)) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val row = getItem(position)) {
                is Row.Header -> {
                    view.findViewById<View>(R.id.saved_audio_about).setOnClickListener { showAboutProcessing() }
                    view.findViewById<TextView>(R.id.saved_audio_counts).text =
                        getString(R.string.saved_audio_counts, row.ready, row.unchanged, row.active)
                    view.findViewById<TextView>(R.id.saved_audio_storage).text =
                        getString(R.string.saved_audio_storage, size(row.bytes), size(row.free))
                    view.findViewById<TextView>(R.id.saved_audio_now_playing).apply {
                        text = row.nowPlaying
                        visibility = if (row.nowPlaying == null) View.GONE else View.VISIBLE
                    }
                }
                Row.Empty -> view.findViewById<View>(R.id.saved_audio_downloads).setOnClickListener {
                    startActivity(Intent(this@SavedAudioActivity, DownloadsActivity::class.java))
                }
                is Row.Track -> bindTrack(view, row)
            }
        }

        private fun bindTrack(view: View, row: Row.Track) {
            val original = row.original
            val version = row.version
            val ready = version?.status == EnhancementStatus.READY && original != null
            val unchanged = version?.status == EnhancementStatus.UNCHANGED
            val enhanced = ready && version?.preferEnhanced == true
            val name = original?.title ?: version?.title.orEmpty()
            view.findViewById<TextView>(R.id.saved_audio_track_title).text = name
            view.findViewById<TextView>(R.id.saved_audio_track_detail).apply {
                text = listOfNotNull(original?.showDate ?: original?.album, original?.venue).joinToString(" · ")
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            val statusText = status(row)
            view.findViewById<TextView>(R.id.saved_audio_track_status).apply {
                text = statusText
                setTextColor(ContextCompat.getColor(context,
                    if (original == null || version?.status == EnhancementStatus.FAILED) R.color.offline_orange else R.color.offline_green))
            }
            view.findViewById<ProgressBar>(R.id.saved_audio_track_progress).apply {
                visibility = if (version?.isActive == true) View.VISIBLE else View.GONE
                isIndeterminate = version?.status == EnhancementStatus.QUEUED
                progress = version?.progress ?: 0
                contentDescription = statusText
            }
            view.findViewById<TextView>(R.id.saved_audio_track_estimate).apply {
                val duration = original?.durationMs ?: 0
                visibility = if (duration > 0 && !ready && !unchanged) View.VISIBLE else View.GONE
                text = getString(R.string.saved_audio_estimate, size(duration * 176_400 / 1_000 + 44))
            }
            view.findViewById<TextView>(R.id.saved_audio_track_summary).apply {
                text = version?.profileSummary
                visibility = if ((ready || unchanged) && !text.isNullOrBlank()) View.VISIBLE else View.GONE
            }
            view.findViewById<View>(R.id.saved_audio_version_label).visibility = if (ready) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.saved_audio_version_hint).visibility = if (ready) View.VISIBLE else View.GONE
            view.findViewById<RadioGroup>(R.id.saved_audio_version_choice).apply {
                setOnCheckedChangeListener(null)
                visibility = if (ready) View.VISIBLE else View.GONE
                check(if (enhanced) R.id.saved_audio_choose_enhanced else R.id.saved_audio_choose_original)
                for (index in 0 until childCount) getChildAt(index).isEnabled = ready && !row.busy
                setOnCheckedChangeListener { _, checked ->
                    val selected = checked == R.id.saved_audio_choose_enhanced
                    if (selected != enhanced) choose(row.key, selected)
                }
            }
            view.findViewById<AppCompatButton>(R.id.saved_audio_play).apply {
                val label = getString(if (enhanced) R.string.saved_audio_play_enhanced else R.string.saved_audio_play_original)
                text = label
                contentDescription = getString(R.string.saved_audio_play_accessibility, label, name)
                isEnabled = original != null && !row.busy
                setOnClickListener { play(row.key) }
            }
            view.findViewById<AppCompatButton>(R.id.saved_audio_action).apply {
                setText(when {
                    version?.isActive == true -> R.string.saved_audio_cancel
                    version?.status == EnhancementStatus.READY -> R.string.saved_audio_remove
                    original == null -> R.string.saved_audio_dismiss
                    unchanged -> R.string.saved_audio_retry_analysis
                    version != null -> R.string.saved_audio_retry
                    else -> R.string.saved_audio_create
                })
                val secondary = version?.isActive == true || version?.status == EnhancementStatus.READY || original == null
                setBackgroundResource(if (secondary) R.drawable.offline_secondary_bg else R.drawable.offline_primary_bg)
                backgroundTintList = null
                setTextColor(ContextCompat.getColor(context, if (secondary) R.color.offline_ink else android.R.color.white))
                isEnabled = !row.busy
                setOnClickListener { action(row) }
            }
            view.findViewById<AppCompatButton>(R.id.saved_audio_clear).apply {
                visibility = if (original != null && (version?.status == EnhancementStatus.FAILED ||
                        version?.status == EnhancementStatus.CANCELLED || unchanged)) View.VISIBLE else View.GONE
                isEnabled = !row.busy
                setOnClickListener { operation(row.key) { repository.remove(row.key); null } }
            }
        }
    }
}
