package robophish

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ScopedCredentialInterceptorTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()

    private fun server() = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false); start() }
    private fun client(origin: MockWebServer) = OkHttpClient.Builder()
        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        .addNetworkInterceptor(ScopedCredentialInterceptor(origin.url("/"), "/v5/", "X-API-KEY", "test-key"))
        .followSslRedirects(false).build()

    @Test fun `same origin API redirect retains credential`() {
        server().use { origin ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/v5/next"))
            origin.enqueue(MockResponse().setBody("ok"))
            client(origin).newCall(Request.Builder().url(origin.url("/v5/start")).build()).execute().close()
            repeat(2) { assertThat(origin.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-API-KEY")).isEqualTo("test-key") }
        }
    }

    @Test fun `redirect outside API path strips credential`() {
        server().use { origin ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/artwork.jpg"))
            origin.enqueue(MockResponse().setBody("ok"))
            client(origin).newCall(Request.Builder().url(origin.url("/v5/start")).build()).execute().close()
            origin.takeRequest(5, TimeUnit.SECONDS)
            assertThat(origin.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-API-KEY")).isNull()
        }
    }

    @Test fun `redirect to another TLS origin strips custom API key`() {
        server().use { origin -> server().use { destination ->
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/v5/trap")))
            destination.enqueue(MockResponse().setBody("ok"))
            client(origin).newCall(Request.Builder().url(origin.url("/v5/start")).build()).execute().close()
            assertThat(destination.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-API-KEY")).isNull()
        } }
    }

    @Test fun `HTTPS redirect cannot downgrade to cleartext`() {
        server().use { origin -> MockWebServer().use { destination ->
            destination.start()
            origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/v5/trap")))
            client(origin).newCall(Request.Builder().url(origin.url("/v5/start")).build()).execute().use {
                assertThat(it.code).isEqualTo(302)
            }
            assertThat(destination.requestCount).isEqualTo(0)
        } }
    }
}
