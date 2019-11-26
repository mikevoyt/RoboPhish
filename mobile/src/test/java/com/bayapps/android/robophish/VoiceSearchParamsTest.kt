package com.bayapps.android.robophish

import android.os.Bundle
import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import org.junit.Test

class VoiceSearchParamsTest {
    @Test fun `on empty query`() {
        val classUnderTest = VoiceSearchParams("", null)

        assertThat(classUnderTest.toString())
                .isEqualTo("query= isAny=true isUnstructured=false isGenreFocus=false isArtistFocus=false isAlbumFocus=false isSongFocus=false genre=null artist=null album=null song=null")
    }

    @Test fun `on non-empty query`() {
        val classUnderTest = VoiceSearchParams("play music", null)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=play music isAny=false isUnstructured=true isGenreFocus=false isArtistFocus=false isAlbumFocus=false isSongFocus=false genre=null artist=null album=null song=null")
    }

    @Test fun `EXTRA_MEDIA_FOCUS is Genres_ENTRY_CONTENT_TYPE`() {
        val extras = mock<Bundle> {
            on { getString(MediaStore.EXTRA_MEDIA_FOCUS) } doReturn MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE
            on { getString(MediaStore.EXTRA_MEDIA_GENRE) } doReturn "Jam"
        }
        val classUnderTest = VoiceSearchParams("Play Phish", extras)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=Play Phish isAny=false isUnstructured=false isGenreFocus=true isArtistFocus=false isAlbumFocus=false isSongFocus=false genre=Jam artist=null album=null song=null")
    }

    @Test fun `EXTRA_MEDIA_FOCUS is Artists_ENTRY_CONTENT_TYPE`() {
        val extras = mock<Bundle> {
            on { getString(MediaStore.EXTRA_MEDIA_FOCUS) } doReturn MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            on { getString(MediaStore.EXTRA_MEDIA_GENRE) } doReturn "Jam"
            on { getString(MediaStore.EXTRA_MEDIA_ARTIST) } doReturn "Phish"
        }
        val classUnderTest = VoiceSearchParams("Play Phish", extras)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=Play Phish isAny=false isUnstructured=false isGenreFocus=false isArtistFocus=true isAlbumFocus=false isSongFocus=false genre=Jam artist=Phish album=null song=null")
    }

    @Test fun `EXTRA_MEDIA_FOCUS is Albums_ENTRY_CONTENT_TYPE`() {
        val extras = mock<Bundle> {
            on { getString(MediaStore.EXTRA_MEDIA_FOCUS) } doReturn MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE
            on { getString(MediaStore.EXTRA_MEDIA_GENRE) } doReturn "Jam"
            on { getString(MediaStore.EXTRA_MEDIA_ARTIST) } doReturn "Phish"
            on { getString(MediaStore.EXTRA_MEDIA_ALBUM) } doReturn "Dick's Sporting Goods Park 2015-09-05"
        }
        val classUnderTest = VoiceSearchParams("Play Phish", extras)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=Play Phish isAny=false isUnstructured=false isGenreFocus=false isArtistFocus=false isAlbumFocus=true isSongFocus=false genre=Jam artist=Phish album=Dick's Sporting Goods Park 2015-09-05 song=null")
    }

    @Test fun `EXTRA_MEDIA_FOCUS is Media_ENTRY_CONTENT_TYPE`() {
        val extras = mock<Bundle> {
            on { getString(MediaStore.EXTRA_MEDIA_FOCUS) } doReturn MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
            on { getString(MediaStore.EXTRA_MEDIA_GENRE) } doReturn "Jam"
            on { getString(MediaStore.EXTRA_MEDIA_ARTIST) } doReturn "Phish"
            on { getString(MediaStore.EXTRA_MEDIA_ALBUM) } doReturn "Dick's Sporting Goods Park 2015-09-05"
            on { getString(MediaStore.EXTRA_MEDIA_TITLE) } doReturn "The Moma Dance"
        }

        val classUnderTest = VoiceSearchParams("Play Mama Dance by Phish at Dicks's Sporting Goods Park 2015", extras)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=Play Mama Dance by Phish at Dicks's Sporting Goods Park 2015 isAny=false isUnstructured=false isGenreFocus=false isArtistFocus=false isAlbumFocus=false isSongFocus=true genre=Jam artist=Phish album=Dick's Sporting Goods Park 2015-09-05 song=The Moma Dance")
    }

    @Test fun `unstructured extras`() {
        val extras = mock<Bundle> {
            on { getString(MediaStore.EXTRA_MEDIA_FOCUS) } doReturn "This is what space smells like"
        }
        val classUnderTest = VoiceSearchParams("Play Phish", extras)

        assertThat(classUnderTest.toString())
                .isEqualTo("query=Play Phish isAny=false isUnstructured=true isGenreFocus=false isArtistFocus=false isAlbumFocus=false isSongFocus=false genre=null artist=null album=null song=null")
    }
}
