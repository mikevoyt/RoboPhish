package com.bayapps.android.robophish.audio.offline

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID

/** Synchronous durable writes happen on an IO thread; the UI observes immutable snapshots. */
class EnhancementStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("audio_enhancements", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val entries: StateFlow<List<EnhancementEntry>> = state.asStateFlow()

    fun getAll(): List<EnhancementEntry> = state.value
    fun find(trackKey: String): EnhancementEntry? = state.value.firstOrNull { it.trackKey == trackKey }

    @Synchronized internal fun put(entry: EnhancementEntry) = save(
        state.value.filterNot { it.trackKey == entry.trackKey } + entry)

    @Synchronized internal fun remove(trackKey: String) = save(state.value.filterNot { it.trackKey == trackKey })

    @Synchronized internal fun update(trackKey: String, generation: String,
                                     transform: (EnhancementEntry) -> EnhancementEntry): Boolean {
        val old = find(trackKey)?.takeIf { it.generation == generation } ?: return false
        val next = transform(old)
        if (next != old) put(next)
        return true
    }

    private fun save(entries: List<EnhancementEntry>) {
        val json = JSONArray().apply { entries.forEach { put(encode(it)) } }
        check(prefs.edit().putString("entries", json.toString()).commit()) { "Unable to save audio processing state" }
        state.value = entries
    }

    private fun read(): List<EnhancementEntry> = runCatching {
        val json = JSONArray(prefs.getString("entries", "[]"))
        (0 until json.length()).mapNotNull { index ->
            runCatching { decode(json.getJSONObject(index)) }
                .onFailure { Timber.w(it, "Ignoring damaged enhanced audio record") }.getOrNull()
        }.distinctBy { it.trackKey }
    }.onFailure { Timber.w(it, "Unable to read enhanced audio records") }.getOrDefault(emptyList())

    companion object {
        @Volatile private var instance: EnhancementStore? = null
        fun get(context: Context): EnhancementStore = instance ?: synchronized(this) {
            instance ?: EnhancementStore(context.applicationContext).also { instance = it }
        }

        internal fun encode(e: EnhancementEntry) = JSONObject().apply {
            put("trackKey", e.trackKey); put("title", e.title); put("generation", e.generation)
            put("sourceUri", e.sourceUri); put("sourceSize", e.sourceSize); put("sourceModified", e.sourceModified)
            put("algorithmVersion", e.algorithmVersion); put("sourceSha256", e.sourceSha256)
            put("status", e.status.name); put("progress", e.progress); put("fileUri", e.fileUri)
            put("outputBytes", e.outputBytes); put("error", e.error); put("preferEnhanced", e.preferEnhanced)
            put("profileSummary", e.profileSummary)
            put("createdAt", e.createdAt)
        }

        internal fun decode(j: JSONObject): EnhancementEntry {
            val generation = j.getString("generation")
            require(UUID.fromString(generation).toString() == generation) { "Invalid render generation" }
            return EnhancementEntry(
            trackKey = j.getString("trackKey"), title = j.getString("title"), generation = j.getString("generation"),
            sourceUri = j.getString("sourceUri"), sourceSize = j.getLong("sourceSize"),
            sourceModified = j.getLong("sourceModified"), algorithmVersion = j.getString("algorithmVersion"),
            sourceSha256 = j.optional("sourceSha256"), status = EnhancementStatus.valueOf(j.getString("status")),
            progress = j.optInt("progress").coerceIn(0, 100), fileUri = j.optional("fileUri"),
            outputBytes = j.optLong("outputBytes"), error = j.optional("error"),
            preferEnhanced = j.optBoolean("preferEnhanced", false), profileSummary = j.optional("profileSummary"),
            createdAt = j.optLong("createdAt"))
        }

        private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
