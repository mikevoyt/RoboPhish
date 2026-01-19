package com.bayapps.android.robophish.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import timber.log.Timber

object CarHelper {
    private const val AUTO_APP_PACKAGE_NAME = "com.google.android.projection.gearhead"

    // Use these extras to reserve space for the corresponding actions, even when they are disabled
    // in the playbackstate, so the custom actions don't reflow.
    private const val SLOT_RESERVATION_SKIP_TO_NEXT =
        "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT"
    private const val SLOT_RESERVATION_SKIP_TO_PREV =
        "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS"
    private const val SLOT_RESERVATION_QUEUE =
        "com.google.android.gms.car.media.ALWAYS_RESERVE_SPACE_FOR.ACTION_QUEUE"

    /**
     * Action for an intent broadcast by Android Auto when a media app is connected or
     * disconnected. A "connected" media app is the one currently attached to the "media" facet
     * on Android Auto. So, this intent is sent by AA on:
     *
     * - connection: when the phone is projecting and at the moment the app is selected from the
     * list of media apps
     * - disconnection: when another media app is selected from the list of media apps or when
     * the phone stops projecting (when the user unplugs it, for example)
     *
     * The actual event (connected or disconnected) will come as an Intent extra,
     * with the key MEDIA_CONNECTION_STATUS (see below).
     */
    const val ACTION_MEDIA_STATUS = "com.google.android.gms.car.media.STATUS"

    /**
     * Key in Intent extras that contains the media connection event type (connected or disconnected)
     */
    const val MEDIA_CONNECTION_STATUS = "media_connection_status"

    /**
     * Value of the key MEDIA_CONNECTION_STATUS in Intent extras used when the current media app
     * is connected.
     */
    const val MEDIA_CONNECTED = "media_connected"

    /**
     * Value of the key MEDIA_CONNECTION_STATUS in Intent extras used when the current media app
     * is disconnected.
     */
    const val MEDIA_DISCONNECTED = "media_disconnected"

    @JvmStatic
    fun isValidCarPackage(packageName: String?): Boolean {
        return AUTO_APP_PACKAGE_NAME == packageName
    }

    @JvmStatic
    fun setSlotReservationFlags(
        extras: Bundle,
        reservePlayingQueueSlot: Boolean,
        reserveSkipToNextSlot: Boolean,
        reserveSkipToPrevSlot: Boolean
    ) {
        if (reservePlayingQueueSlot) {
            extras.putBoolean(SLOT_RESERVATION_QUEUE, true)
        } else {
            extras.remove(SLOT_RESERVATION_QUEUE)
        }
        if (reserveSkipToPrevSlot) {
            extras.putBoolean(SLOT_RESERVATION_SKIP_TO_PREV, true)
        } else {
            extras.remove(SLOT_RESERVATION_SKIP_TO_PREV)
        }
        if (reserveSkipToNextSlot) {
            extras.putBoolean(SLOT_RESERVATION_SKIP_TO_NEXT, true)
        } else {
            extras.remove(SLOT_RESERVATION_SKIP_TO_NEXT)
        }
    }

    /**
     * Returns true when running Android Auto or a car dock.
     *
     * A preferable way of detecting if your app is running in the context of an Android Auto
     * compatible car is by registering a BroadcastReceiver for the action
     * [CarHelper.ACTION_MEDIA_STATUS].
     */
    @JvmStatic
    fun isCarUiMode(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_CAR) {
            Timber.d("Running in Car mode")
            true
        } else {
            Timber.d("Running on a non-Car mode")
            false
        }
    }
}
