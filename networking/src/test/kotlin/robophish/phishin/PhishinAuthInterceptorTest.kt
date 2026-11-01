package robophish.phishin

import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PhishinAuthInterceptorTest {

    private fun authorization(url: String): String? {
        val request = Request.Builder().url(url).header("Authorization", "untrusted").build()
        var sent: Request? = null
        val chain = Proxy.newProxyInstance(Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java)) { _, method, args ->
            when (method.name) {
                "request" -> request
                "proceed" -> {
                    sent = args[0] as Request
                    Response.Builder().request(sent!!).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("".toResponseBody()).build()
                }
                else -> error("Unexpected chain method: ${method.name}")
            }
        } as Interceptor.Chain
        PhishinAuthInterceptor(PhishinApiKey("test-key")).intercept(chain).close()
        return sent!!.header("Authorization")
    }

    @Test fun `adds bearer only to trusted HTTPS API origin and path`() {
        assertThat(authorization("https://phish.in/api/v1/shows")).isEqualTo("Bearer test-key")
    }

    @Test fun `strips credentials from artwork cleartext alternate ports and lookalike hosts`() {
        listOf("https://i.imgur.com/cover.jpg", "https://phish.in/audio/a.mp3",
            "http://phish.in/api/v1/shows", "https://phish.in:8443/api/v1/shows",
            "https://phish.in.example.com/api/v1/shows", "https://phish.in/api/v10/shows",
            "https://phish.in/api/v1/../private").forEach {
            assertThat(authorization(it)).isNull()
        }
    }
}
