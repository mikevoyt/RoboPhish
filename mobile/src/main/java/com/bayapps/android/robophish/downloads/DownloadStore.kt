package com.bayapps.android.robophish.downloads

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class DownloadStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("downloads_store", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val entries = state.asStateFlow()
    fun getAll(): List<DownloadEntry> = state.value
    fun find(trackId: String?): DownloadEntry? = trackId?.let { id -> state.value.firstOrNull { it.trackId == id || it.key == id } }

    @Synchronized fun upsert(entry: DownloadEntry) {
        replaceAll(state.value.filterNot { it.key == entry.key } + entry)
    }

    @Synchronized fun delete(entry: DownloadEntry) {
        replaceAll(state.value.filterNot { it.key == entry.key })
    }

    @Synchronized fun replaceAll(entries: List<DownloadEntry>) {
        if (state.value == entries) return
        val json = JSONArray().apply { entries.forEach { put(DownloadJson.encode(it)) } }
        // All mutations run on the repository's IO dispatcher. Persist before publishing.
        check(prefs.edit().putString("entries", json.toString()).commit()) { "Unable to save downloads" }
        state.value = entries.toList()
    }

    private fun read(): List<DownloadEntry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching { DownloadJson.decode(array.getJSONObject(index)) }
                .onFailure { Timber.w(it, "Ignoring damaged download record") }.getOrNull()
        }.distinctBy { it.key }
    }.onFailure { Timber.w(it, "Unable to read downloads") }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore = instance ?: synchronized(this) {
            instance ?: DownloadStore(context).also { instance = it }
        }
    }
}

internal object DownloadJson {
    fun encode(e: DownloadEntry) = JSONObject().apply {
        put("downloadId", e.downloadId); put("trackId", e.trackId); put("showId", e.showId)
        put("title", e.title); put("artist", e.artist); put("album", e.album)
        put("showDate", e.showDate); put("venue", e.venue); put("location", e.location)
        put("durationMs", e.durationMs); put("artUrl", e.artUrl); put("fileUri", e.fileUri)
        put("status", e.status.name); put("addedAt", e.addedAt); put("sourceUrl", e.sourceUrl)
        put("trackNumber", e.trackNumber); put("setName", e.setName)
        put("bytesDownloaded", e.bytesDownloaded); put("totalBytes", e.totalBytes)
        put("failureReason", e.failureReason)
        put("transferUri", e.transferUri)
        put("recordId", e.recordId)
    }

    fun decode(j: JSONObject) = DownloadEntry(
        downloadId = j.optLong("downloadId").takeIf { it > 0 },
        trackId = j.text("trackId"), showId = j.text("showId")?.substringBefore('|'),
        title = j.getString("title"), artist = j.text("artist") ?: "Phish",
        album = j.text("album"), showDate = j.text("showDate"), venue = j.text("venue"),
        location = j.text("location"), durationMs = j.optLong("durationMs").takeIf { it > 0 },
        artUrl = j.text("artUrl"), fileUri = j.text("fileUri"),
        status = runCatching { DownloadStatus.valueOf(j.optString("status")) }.getOrDefault(DownloadStatus.FAILED),
        addedAt = j.optLong("addedAt"), sourceUrl = j.text("sourceUrl"),
        trackNumber = j.optInt("trackNumber"), setName = j.text("setName"),
        bytesDownloaded = j.optLong("bytesDownloaded"), totalBytes = j.optLong("totalBytes", -1),
        failureReason = j.optInt("failureReason"), transferUri = j.text("transferUri"),
        recordId = j.text("recordId") ?: java.util.UUID.nameUUIDFromBytes(j.toString().toByteArray(Charsets.UTF_8)).toString()
    )

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else
        optString(key).takeIf { it.isNotBlank() && it != "null" }
}
