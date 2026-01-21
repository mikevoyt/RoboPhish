package com.bayapps.android.robophish

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.model.MusicProviderSource
import com.bayapps.android.robophish.model.PhishProviderSource
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import coil.ImageLoader
import coil.util.DebugLogger
import okhttp3.Cache
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import robophish.moshi.HttpUrlAdapter
import robophish.phishin.PhishinApiKey
import robophish.phishin.PhishinAuthInterceptor
import robophish.phishin.PhishinRepository
import robophish.phishin.PhishinService
import timber.log.Timber
import java.io.File
import java.util.Date

class ServiceLocator private constructor(private val appContext: Context) {
    private val phishinApiUrl: HttpUrl =
        requireNotNull("https://phish.in/".toHttpUrlOrNull())

    private val cacheDir: File by lazy { appContext.cacheDir }

    val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(appContext)
    }

    val phishinApiKey: PhishinApiKey by lazy {
        PhishinApiKey(BuildConfig.PHISHIN_API_KEY)
    }

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(Date::class.java, Rfc3339DateJsonAdapter())
            .add(HttpUrlAdapter)
            .build()
    }

    val okHttpClient: OkHttpClient by lazy {
        val cacheSize = 50 * 1024 * 1024
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http"), cacheSize.toLong()))
            .addNetworkInterceptor(PhishinAuthInterceptor(phishinApiKey))
            .build()
    }

    val okHttpNoAuthClient: OkHttpClient by lazy {
        val cacheSize = 20 * 1024 * 1024
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "http-no-auth"), cacheSize.toLong()))
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .baseUrl(phishinApiUrl)
            .build()
    }

    val phishinService: PhishinService by lazy {
        retrofit.create(PhishinService::class.java)
    }

    val phishinRepository: PhishinRepository by lazy {
        PhishinRepository(phishinService)
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient(okHttpClient)
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }

    val musicProviderSource: MusicProviderSource by lazy {
        PhishProviderSource(appContext, phishinRepository, imageLoader)
    }

    val musicProvider: MusicProvider by lazy {
        MusicProvider(appContext, musicProviderSource)
    }

    fun initializeApp() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    companion object {
        @Volatile private var instance: ServiceLocator? = null

        fun get(context: Context): ServiceLocator {
            return instance ?: synchronized(this) {
                instance ?: ServiceLocator(context.applicationContext).also { instance = it }
            }
        }
    }
}
