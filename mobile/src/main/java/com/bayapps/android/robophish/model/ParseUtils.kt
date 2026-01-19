package com.bayapps.android.robophish.model

import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ParseUtils {
    fun parseShow(json: JSONObject): Show? {
        return runCatching {
            val data = json.getJSONObject("data")
            parseShowData(data)
        }.getOrElse {
            Timber.d("failed to parse show!")
            Timber.e(it)
            null
        }
    }

    private fun parseShowData(data: JSONObject): Show? {
        return runCatching {
            val show = Show()
            show.id = data.getLong("id")

            val dateString = data.getString("date")
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateString)
            show.date = date

            val venue = data.optJSONObject("venue")
            if (venue != null) {
                show.venueName = venue.getString("name")
                show.location = venue.getString("location")
            } else {
                show.venueName = data.getString("venue_name")
                show.location = data.getString("location")
            }

            val tracks = data.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val jsonTrack = tracks.getJSONObject(i)
                    Timber.d(jsonTrack.toString())

                    val id = jsonTrack.getInt("id")
                    val title = jsonTrack.getString("title")
                    val url = jsonTrack.getString("mp3")
                    val duration = jsonTrack.getLong("duration")

                    val track = Track(id.toLong(), title, url)
                    track.duration = duration
                    show.addTrack(track)
                }
            }

            val taperNotes = data.optString("taper_notes", null)
            show.taperNotes = taperNotes

            show.isSbd = data.getBoolean("sbd")
            show
        }.getOrElse {
            Timber.d("failed to parse show!")
            Timber.e(it)
            null
        }
    }
}
