package robophish.model

import com.squareup.moshi.JsonClass
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class Track(
        val id: String,
        val title: String,
        val mp3: HttpUrl,
        val duration: Long
) {
    val formatedDuration: String get() {
        return String.format("%d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(duration),
                TimeUnit.MILLISECONDS.toSeconds(duration) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration))
        )
    }
}
