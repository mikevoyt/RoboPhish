package com.bayapps.android.robophish

import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_LOW
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.multidex.MultiDexApplication
import com.google.android.gms.cast.framework.CastContext

const val MEDIA_PLAYER_NOTIFICATION = "MediaPlayer"

class RoboPhishApplication : MultiDexApplication() {
    private val deps by lazy { ServiceLocator.get(this) }

    override fun onCreate() {
        super.onCreate()

        deps.initializeApp()

        CastContext.getSharedInstance(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            deps.notificationManager.createNotificationChannel(
                    NotificationChannel(
                            MEDIA_PLAYER_NOTIFICATION,
                            MEDIA_PLAYER_NOTIFICATION,
                            IMPORTANCE_LOW
                    )
            )
        }
    }
}
