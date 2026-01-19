package com.bayapps.android.robophish.utils

import android.content.Context
import android.net.ConnectivityManager

/**
 * Generic reusable network methods.
 */
object NetworkHelper {
    /**
     * @return true if connected, false otherwise.
     */
    @JvmStatic
    fun isOnline(context: Context): Boolean {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkInfo = connMgr.activeNetworkInfo
        return networkInfo != null && networkInfo.isConnected
    }
}
