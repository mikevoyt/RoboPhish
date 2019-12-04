package robophish

import com.jakewharton.byteunits.DecimalByteUnit.MEGABYTES
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import robophish.moshi.HttpUrlAdapter
import robophish.phishin.PhishinAuthInterceptor
import robophish.phishin.PhishinRepository
import robophish.phishin.PhishinService
import java.io.File
import java.util.*

/**
 * Tag for networking module to provide the cache directory for http client
 */
const val CACHE_DIR_TAG = "CacheDir"

private const val PHISHIN_RETROFIT_TAG = "Phishin"

private val PHISHIN_API_URL: HttpUrl = requireNotNull("https://phish.in/".toHttpUrlOrNull())
private val DISK_CACHE_SIZE = MEGABYTES.toBytes(50).toInt()

val networkingModule = Kodein.Module(name = "NetworkingModule") {

    bind<PhishinService>() with singleton {
        instance<Retrofit>(tag = PHISHIN_RETROFIT_TAG).create(PhishinService::class.java)
    }

    bind<PhishinRepository>() with singleton { PhishinRepository(instance()) }

    bind<Retrofit>(tag = PHISHIN_RETROFIT_TAG) with singleton {
        Retrofit.Builder()
                .client(instance())
                .addConverterFactory(MoshiConverterFactory.create(instance()))
                .baseUrl(PHISHIN_API_URL)
                .build()
    }

    bind<Moshi>() with singleton {
        Moshi.Builder()
                .add(Date::class.java, Rfc3339DateJsonAdapter())
                .add(HttpUrlAdapter)
                .build()
    }

    bind<OkHttpClient>() with singleton {
        OkHttpClient.Builder()
                .cache(Cache(File(instance<File>(tag = CACHE_DIR_TAG), "http"), DISK_CACHE_SIZE.toLong()))
                .apply { instance<List<Interceptor>>().forEach { addNetworkInterceptor(it) } }
                .build()

    }

    bind<MutableList<Interceptor>>() with singleton {
        mutableListOf<Interceptor>(
                PhishinAuthInterceptor(instance())
        )
    }
}
