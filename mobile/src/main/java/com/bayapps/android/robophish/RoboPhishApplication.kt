package com.bayapps.android.robophish

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_LOW
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.multidex.MultiDexApplication
import com.bayapps.android.robophish.model.MusicProvider
import com.bayapps.android.robophish.model.MusicProviderSource
import com.bayapps.android.robophish.model.PhishProviderSource
import com.bayapps.android.robophish.ui.FullScreenPlayerActivity
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.libraries.cast.companionlibrary.cast.CastConfiguration
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.android.androidCoreModule
import org.kodein.di.android.x.androidXModule
import org.kodein.di.generic.bind
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import org.kodein.di.jxinject.Jx
import org.kodein.di.jxinject.jxInjectorModule
import robophish.CACHE_DIR_TAG
import robophish.networkingModule
import robophish.phishin.PhishinApiKey
import timber.log.Timber
import timber.log.Timber.DebugTree
import java.io.File

const val MEDIA_PLAYER_NOTIFICATION = "MediaPlayer"

class RoboPhishApplication : MultiDexApplication(), KodeinAware {

    override val kodein = Kodein.lazy {
        import(androidCoreModule(this@RoboPhishApplication))
        import(androidXModule(this@RoboPhishApplication))
        import(jxInjectorModule)
        import(networkingModule)

        bind<VideoCastManager>() with singleton { VideoCastManager.getInstance() }
        bind<AlbumArtCache>() with singleton { AlbumArtCache.getInstance() }

        bind<GoogleApiAvailability>() with singleton { GoogleApiAvailability.getInstance() }

        bind<PhishinApiKey>() with singleton { PhishinApiKey(BuildConfig.PHISHIN_API_KEY) }
        bind<File>(tag = CACHE_DIR_TAG) with singleton { instance<Context>().cacheDir }

        bind<MusicProvider>() with singleton { MusicProvider(instance(), instance()) }
        bind<MusicProviderSource>() with singleton { PhishProviderSource(instance()) }
    }

    private val interceptors: MutableList<Interceptor> by instance()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
            interceptors.add(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        }

        val applicationId = resources.getString(R.string.cast_application_id)
        VideoCastManager.initialize(
                applicationContext,
                CastConfiguration.Builder(applicationId)
                        .enableWifiReconnection()
                        .enableAutoReconnect()
                        .enableDebug()
                        .setTargetActivity(FullScreenPlayerActivity::class.java)
                        .build())


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManagerCompat.from(this).createNotificationChannel(
                    NotificationChannel(
                            MEDIA_PLAYER_NOTIFICATION,
                            MEDIA_PLAYER_NOTIFICATION,
                            IMPORTANCE_LOW
                    )
            )
        }
    }
}

val Context.kodein get() = (applicationContext as RoboPhishApplication).kodein

fun Activity.inject() {
    Jx.of(kodein).inject(this)
}

fun Fragment.inject() {
    requireActivity().inject(this)
}

fun Context.inject(any: Any) {
    Jx.of(kodein).inject(any)
}
