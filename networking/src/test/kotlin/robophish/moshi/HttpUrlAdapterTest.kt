package robophish.moshi

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import robophish.model.Track
import robophish.networkingModule

/**
 * Test both json adapters in the moshi package.
 */
class HttpUrlAdapterTest: KodeinAware {

    private val moshi: Moshi by instance()

    @Test
    fun `should be bijective`() {

        val testData = Track(
                id = "Rift",
                title = "Rift",
                mp3 = "http://example.com".toHttpUrl(),
                duration = 10L
        )

        val classUnderTest = moshi.adapter(Track::class.java)
        val json = classUnderTest.toJson(testData)
        val result = classUnderTest.fromJson(json)

        assertThat(result).isEqualTo(testData)
    }

    override val kodein: Kodein = Kodein.lazy {
        import(networkingModule)
    }
}
