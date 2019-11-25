package com.bayapps.android.robophish.network

import retrofit2.http.GET
import retrofit2.http.Path

interface PhishinService {
    @GET("api/v1/years.json")
    suspend fun years() : List<String>

    @GET("api/v1/shows/{id}.json")
    suspend fun shows(@Path("id") showId: String): List<String>
}
