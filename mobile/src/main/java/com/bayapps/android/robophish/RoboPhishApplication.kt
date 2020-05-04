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
import com.squareup.picasso.OkHttp3Downloader
import com.squareup.picasso.Picasso
import okhttp3.OkHttpClient
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
import java.io.File

const val MEDIA_PLAYER_NOTIFICATION = "MediaPlayer"

class RoboPhishApplication : MultiDexApplication(), KodeinAware {

    override val kodein = Kodein.lazy {
        import(androidCoreModule(this@RoboPhishApplication))
        import(androidXModule(this@RoboPhishApplication))
        import(jxInjectorModule)
        import(networkingModule)

        /**
         * Each build variant should provide a list of modules it would like to provide, this
         * allows for these variants to provide different functionality, based on the the
         * build type. For example, [Timber] logging w/ the [Timber.DebugTree] should only
         * be done in the debug module.
         */
        buildSpecificModules.forEach {
            import(it)
        }

        bind<NotificationManagerCompat>() with singleton { NotificationManagerCompat.from(instance()) }
        bind<VideoCastManager>() with singleton { VideoCastManager.getInstance() }

        bind<GoogleApiAvailability>() with singleton { GoogleApiAvailability.getInstance() }

        bind<PhishinApiKey>() with singleton { PhishinApiKey(BuildConfig.PHISHIN_API_KEY) }
        bind<File>(tag = CACHE_DIR_TAG) with singleton { instance<Context>().cacheDir }

        bind<MusicProvider>() with singleton { MusicProvider(instance(), instance()) }
        bind<MusicProviderSource>() with singleton { PhishProviderSource(instance(), instance()) }

        bind<Picasso>() with singleton {
            Picasso.Builder(instance())
                    .downloader(OkHttp3Downloader(instance<OkHttpClient>()))
                    .listener { _, uri, exception ->
                        Timber.e(exception, "Error while loading image %s", uri)
                    }
                    .build()
        }
    }

    private val appInitializer: AppInitializer by instance()
    private val notificationManagerCompat: NotificationManagerCompat by instance()

    override fun onCreate() {
        super.onCreate()

        appInitializer()

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
            notificationManagerCompat.createNotificationChannel(
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
