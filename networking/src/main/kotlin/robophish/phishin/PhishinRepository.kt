package robophish.phishin

import robophish.model.Show
import robophish.model.YearData
import kotlin.Exception

sealed class PhishinResponse<T>
data class PhishinSuccess<T>(val data: T): PhishinResponse<T>()
data class PhishinError<T>(val exception: Exception): PhishinResponse<T>()

class PhishinRepository internal constructor(
        private val phishinService: PhishinService
) {
    suspend fun years() : PhishinResponse<List<YearData>> {
        return try {
            PhishinSuccess(phishinService.years().data)
        } catch (e: Exception) {
            PhishinError(e)
        }
    }

    suspend fun shows(year: String): PhishinResponse<List<Show>> {
        return try {
            PhishinSuccess(phishinService.shows(year).data)
        } catch (e: Exception) {
            PhishinError(e)
        }
    }

    suspend fun show(showId: String): PhishinResponse<Show> {
        return try {
            PhishinSuccess(phishinService.show(showId).data)
        } catch (e: Exception) {
            PhishinError(e)
        }
    }
}
