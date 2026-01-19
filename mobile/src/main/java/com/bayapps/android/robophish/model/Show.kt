package com.bayapps.android.robophish.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Show {
    var id: Long = 0
    var date: Date? = null
    var duration: Long = 0
    var isSbd: Boolean = false
    var tourId: Long = 0
    var venueName: String? = null
    var location: String? = null
    var taperNotes: String? = null
    val tracks: MutableList<Track> = mutableListOf()

    fun dateSimple(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val value = date ?: return ""
        return formatter.format(value)
    }

    fun getTrack(index: Int): Track = tracks[index]

    fun addTrack(track: Track) {
        tracks.add(track)
    }
}
