package com.bayapps.android.robophish.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.util.*

class DateKtTest {
    @Test
    fun shouldFormatDate() {
        val result = Date.from(Instant.ofEpochSecond(0)).toSimpleFormat()
        assertThat(result).isEqualTo("1969.12.31")
    }
}
