package robophish.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.util.*

@JsonClass(generateAdapter = true)
data class Show(
        val id: String,
        val date: Date,
        val venue_name: String,
        val taper_notes: String?,
        val venue: Venue,
        val tracks: List<Track>,
        @Json(name = "sbd")
        val soundBoard: Boolean
)
