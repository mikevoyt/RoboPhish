package com.bayapps.android.robophish.model

import java.util.concurrent.TimeUnit

class Track(
    var id: Long,
    var title: String,
    trackUrl: String
) {
    var duration: Long = 0
    var set: String? = null
    var setName: String? = null
    var url: String = trackUrl.replaceFirst("https://", "http://")

    fun durationString(): String {
        return String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(duration),
            TimeUnit.MILLISECONDS.toSeconds(duration) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration))
        )
    }
}
