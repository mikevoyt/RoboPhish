package robophish.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Venue(
        val name: String,
        val location: String
)
