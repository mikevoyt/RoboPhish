package robophish.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal object HttpUrlAdapter {
    @ToJson fun toJson(httpUrl: HttpUrl) = httpUrl.toString()
    @FromJson fun fromJson(json: String) = json.toHttpUrl()
}
