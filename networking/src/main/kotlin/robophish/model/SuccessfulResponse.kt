package robophish.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SuccessfulResponse<T>(
        val total_entries: Int,
        val total_pages: Int,
        val page: Int,
        val data: T
)
