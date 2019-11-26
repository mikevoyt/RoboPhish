package com.bayapps.android.robophish

import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_LOW
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.multidex.MultiDexApplication
import com.bayapps.android.robophish.ui.FullScreenPlayerActivity
import com.google.android.libraries.cast.companionlibrary.cast.CastConfiguration
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager
import timber.log.Timber
import timber.log.Timber.DebugTree

const val MEDIA_PLAYER_NOTIFICATION = "MediaPlayer"

class RoboPhishApplication : MultiDexApplication() {
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
