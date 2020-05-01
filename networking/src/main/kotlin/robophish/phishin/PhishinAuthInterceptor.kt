package robophish.phishin

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal class PhishinAuthInterceptor(apiKey: PhishinApiKey): Interceptor {

    private val authString = "Bearer ${apiKey.apiKey}"

    override fun intercept(chain: Interceptor.Chain): Response {
        val original: Request = chain.request()

        val builder: Request.Builder = original.newBuilder()
                .header("Authorization", authString)

        val request: Request = builder.build()
        return chain.proceed(request)
    }
}
