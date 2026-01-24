package com.bayapps.android.robophish.utils

import java.util.concurrent.ConcurrentHashMap

object ShowDetailsCache {
    data class Html(
        val setlist: String? = null,
        val reviews: String? = null,
        val taperNotes: String? = null,
        val showDate: String? = null
    )

    private val cache = ConcurrentHashMap<String, Html>()

    fun get(showId: String): Html? = cache[showId]

    fun put(showId: String, html: Html) {
        cache[showId] = html
    }

    fun clear() {
        cache.clear()
    }
}
