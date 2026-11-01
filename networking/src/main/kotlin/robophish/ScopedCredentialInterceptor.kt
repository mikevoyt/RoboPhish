package robophish

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Register as a network interceptor so every redirect hop is checked independently. */
class ScopedCredentialInterceptor(
    private val origin: HttpUrl,
    private val pathPrefix: String,
    private val headerName: String,
    private val credential: String
) : Interceptor {
    init {
        require(origin.isHttps)
        require(pathPrefix.startsWith('/') && pathPrefix.endsWith('/'))
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val allowed = url.isHttps && url.host == origin.host && url.port == origin.port &&
            url.encodedPath.startsWith(pathPrefix)
        val builder = request.newBuilder().removeHeader(headerName)
        if (allowed && credential.isNotBlank()) builder.header(headerName, credential)
        return chain.proceed(builder.build())
    }
}
