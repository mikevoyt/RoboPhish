package robophish.phishin

import retrofit2.http.GET
import retrofit2.http.Path
import robophish.model.Show
import robophish.model.SuccessfulResponse
import robophish.model.YearData

internal interface PhishinService {
    @GET("api/v1/years?include_show_counts=true")
    suspend fun years(): SuccessfulResponse<List<YearData>>

    @GET("api/v1/years/{year}")
    suspend fun shows(@Path("year") year: String): SuccessfulResponse<List<Show>>

    @GET("api/v1/shows/{id}")
    suspend fun show(@Path("id") showId: String): SuccessfulResponse<Show>
}
