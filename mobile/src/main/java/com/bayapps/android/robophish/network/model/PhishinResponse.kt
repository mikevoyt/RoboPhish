package com.bayapps.android.robophish.network.model

import com.squareup.moshi.JsonClass

sealed class PhishinResponse {
    @JsonClass(generateAdapter = true)
    data class SuccessfulResponse(
            val total_entries: Int,
            val total_pages: Int,
            val page: Int,
            val data: List<Any>
    ): PhishinResponse()

    @JsonClass(generateAdapter = true)
    data class ErrorResponse(
            val message: String
    ): PhishinResponse()
}
