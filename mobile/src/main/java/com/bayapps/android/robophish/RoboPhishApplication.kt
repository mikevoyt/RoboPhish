package com.bayapps.android.robophish

import androidx.multidex.MultiDexApplication
import com.bayapps.android.robophish.ui.FullScreenPlayerActivity
import com.google.android.libraries.cast.companionlibrary.cast.CastConfiguration
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager
import timber.log.Timber
import timber.log.Timber.DebugTree


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
    }
}
