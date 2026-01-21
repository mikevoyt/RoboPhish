package com.bayapps.android.robophish.utils

import timber.log.Timber
import java.util.Arrays

/**
 * Utility class to help on queue related tasks.
 */
object MediaIDHelper {
    // Media IDs used on browseable items of MediaBrowser
    const val MEDIA_ID_ROOT = "__ROOT__"
    const val MEDIA_ID_MUSICS_BY_GENRE = "__BY_GENRE__"
    const val MEDIA_ID_MUSICS_BY_YEAR = "__BY_YEAR__"
    const val MEDIA_ID_MUSICS_BY_SEARCH = "__BY_SEARCH__"
    const val MEDIA_ID_SHOWS_BY_YEAR = "__SHOWS_BY_YEAR__"
    const val MEDIA_ID_TRACKS_BY_SHOW = "__TRACKS_BY_SHOW__"

    private const val CATEGORY_SEPARATOR = '/'
    private const val LEAF_SEPARATOR = '|'

    /**
     * Create a String value that represents a playable or a browsable media.
     *
     * Encode the media browseable categories, if any, and the unique music ID, if any,
     * into a single String mediaID.
     */
    @JvmStatic
    fun createMediaID(musicID: String?, vararg categories: String?): String {
        val sb = StringBuilder()
        categories.forEachIndexed { index, category ->
            if (!isValidCategory(category)) {
                throw IllegalArgumentException("Invalid category: ${categories.firstOrNull()}")
            }
            if (category != null) {
                sb.append(category)
            }
            if (index < categories.size - 1) {
                sb.append(CATEGORY_SEPARATOR)
            }
        }
        if (musicID != null) {
            sb.append(LEAF_SEPARATOR).append(musicID)
        }
        return sb.toString()
    }

    private fun isValidCategory(category: String?): Boolean {
        return category == null ||
            (category.indexOf(CATEGORY_SEPARATOR) < 0 && category.indexOf(LEAF_SEPARATOR) < 0)
    }

    /**
     * Extracts unique musicID from the mediaID.
     */
    @JvmStatic
    fun extractMusicIDFromMediaID(mediaID: String): String? {
        val pos = mediaID.indexOf(LEAF_SEPARATOR)
        return if (pos >= 0) {
            mediaID.substring(pos + 1)
        } else {
            null
        }
    }

    @JvmStatic
    fun extractShowFromMediaID(mediaID: String): String? {
        val pos = mediaID.indexOf(CATEGORY_SEPARATOR)
        return if (pos >= 0) {
            mediaID.substring(pos + 1)
        } else {
            null
        }
    }

    /**
     * Extracts category and categoryValue from the mediaID.
     */
    @JvmStatic
    fun getHierarchy(mediaID: String): Array<String> {
        val pos = mediaID.indexOf(LEAF_SEPARATOR)
        val trimmed = if (pos >= 0) mediaID.substring(0, pos) else mediaID
        return trimmed.split(CATEGORY_SEPARATOR).toTypedArray()
    }

    @JvmStatic
    fun extractBrowseCategoryValueFromMediaID(mediaID: String): String? {
        val hierarchy = getHierarchy(mediaID)
        return if (hierarchy.size == 2) hierarchy[1] else null
    }

    @JvmStatic
    fun isBrowseable(mediaID: String): Boolean {
        return mediaID.indexOf(LEAF_SEPARATOR) < 0
    }

    @JvmStatic
    fun isShow(mediaID: String): Boolean {
        Timber.d(mediaID)
        val hierarchy = getHierarchy(mediaID)
        return hierarchy.isNotEmpty() && hierarchy[0].matches(MEDIA_ID_TRACKS_BY_SHOW.toRegex())
    }

    @JvmStatic
    fun getParentMediaID(mediaID: String): String {
        val hierarchy = getHierarchy(mediaID)
        if (!isBrowseable(mediaID)) {
            return createMediaID(null, *hierarchy)
        }
        if (hierarchy.size <= 1) {
            return MEDIA_ID_ROOT
        }
        val parentHierarchy = Arrays.copyOf(hierarchy, hierarchy.size - 1)
        return createMediaID(null, *parentHierarchy)
    }
}
