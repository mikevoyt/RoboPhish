package com.bayapps.android.robophish.cast

import android.content.Context
import com.bayapps.android.robophish.R
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import java.util.Collections

class RoboPhishCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val applicationId = context.getString(R.string.cast_application_id)
        return CastOptions.Builder()
            .setReceiverApplicationId(applicationId)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> {
        return Collections.emptyList()
    }
}
