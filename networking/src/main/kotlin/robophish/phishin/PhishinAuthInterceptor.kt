package robophish.phishin

import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import robophish.ScopedCredentialInterceptor

class PhishinAuthInterceptor(apiKey: PhishinApiKey) : Interceptor by ScopedCredentialInterceptor(
    "https://phish.in/".toHttpUrl(), "/api/v1/", "Authorization", "Bearer ${apiKey.apiKey}"
)
