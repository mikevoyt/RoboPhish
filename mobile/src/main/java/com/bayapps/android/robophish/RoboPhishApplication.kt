package com.bayapps.android.robophish

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_LOW
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.multidex.MultiDexApplication
import com.bayapps.android.robophish.ui.FullScreenPlayerActivity
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.libraries.cast.companionlibrary.cast.CastConfiguration
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.android.androidCoreModule
import org.kodein.di.android.x.androidXModule
import org.kodein.di.generic.bind
import org.kodein.di.generic.singleton
import org.kodein.di.jxinject.Jx
import org.kodein.di.jxinject.jxInjectorModule
import timber.log.Timber
import timber.log.Timber.DebugTree

const val MEDIA_PLAYER_NOTIFICATION = "MediaPlayer"

class RoboPhishApplication : MultiDexApplication(), KodeinAware {

    override val kodein = Kodein.lazy {
        import(androidCoreModule(this@RoboPhishApplication))
        import(androidXModule(this@RoboPhishApplication))
        import(jxInjectorModule)

        bind<VideoCastManager>() with singleton { VideoCastManager.getInstance() }
        bind<AlbumArtCache>() with singleton { AlbumArtCache.getInstance() }
        bind<GoogleApiAvailability>() with singleton { GoogleApiAvailability.getInstance() }
    }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
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
