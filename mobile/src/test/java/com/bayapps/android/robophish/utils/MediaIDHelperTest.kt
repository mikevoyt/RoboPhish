package com.bayapps.android.robophish.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit tests for the MediaIDHelper class.
 */
@RunWith(JUnit4::class)
class MediaIDHelperTest {
    @Test
    fun testNormalMediaIDStructure() {
        val mediaID = MediaIDHelper.createMediaID("784343", "BY_GENRE", "Classic 70's")
        assertEquals("Classic 70's", MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaID))
        assertEquals("784343", MediaIDHelper.extractMusicIDFromMediaID(mediaID))
    }

    @Test
    fun testSpecialSymbolsMediaIDStructure() {
        val mediaID = MediaIDHelper.createMediaID("78A_88|X/3", "BY_GENRE", "Classic 70's")
        assertEquals("Classic 70's", MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaID))
        assertEquals("78A_88|X/3", MediaIDHelper.extractMusicIDFromMediaID(mediaID))
    }

    @Test
    fun testNullMediaIDStructure() {
        val mediaID = MediaIDHelper.createMediaID(null, "BY_GENRE", "Classic 70's")
        assertEquals("Classic 70's", MediaIDHelper.extractBrowseCategoryValueFromMediaID(mediaID))
        assertNull(MediaIDHelper.extractMusicIDFromMediaID(mediaID))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidSymbolsInMediaIDStructure() {
        MediaIDHelper.createMediaID(null, "BY|GENRE/2", "Classic 70's")
    }

    @Test
    fun testCreateBrowseCategoryMediaID() {
        val browseMediaID = MediaIDHelper.createMediaID(null, "BY_GENRE", "Rock & Roll")
        assertEquals("Rock & Roll", MediaIDHelper.extractBrowseCategoryValueFromMediaID(browseMediaID))
        val categories = MediaIDHelper.getHierarchy(browseMediaID)
        assertArrayEquals(arrayOf("BY_GENRE", "Rock & Roll"), categories)
    }

    @Test
    fun testGetParentOfPlayableMediaID() {
        val mediaID = MediaIDHelper.createMediaID("23423423", "BY_GENRE", "Rock & Roll")
        val expectedParentID = MediaIDHelper.createMediaID(null, "BY_GENRE", "Rock & Roll")
        assertEquals(expectedParentID, MediaIDHelper.getParentMediaID(mediaID))
    }

    @Test
    fun testGetParentOfBrowsableMediaID() {
        val mediaID = MediaIDHelper.createMediaID(null, "BY_GENRE", "Rock & Roll")
        val expectedParentID = MediaIDHelper.createMediaID(null, "BY_GENRE")
        assertEquals(expectedParentID, MediaIDHelper.getParentMediaID(mediaID))
    }

    @Test
    fun testGetParentOfCategoryMediaID() {
        assertEquals(
            MediaIDHelper.MEDIA_ID_ROOT,
            MediaIDHelper.getParentMediaID(MediaIDHelper.createMediaID(null, "BY_GENRE"))
        )
    }

    @Test
    fun testGetParentOfRoot() {
        assertEquals(
            MediaIDHelper.MEDIA_ID_ROOT,
            MediaIDHelper.getParentMediaID(MediaIDHelper.MEDIA_ID_ROOT)
        )
    }

    @Test(expected = NullPointerException::class)
    fun testGetParentOfNull() {
        MediaIDHelper.getParentMediaID(null as String)
    }
}
