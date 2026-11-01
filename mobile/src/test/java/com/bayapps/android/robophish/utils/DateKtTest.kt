package com.bayapps.android.robophish.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.util.*

class DateKtTest {
    @Test
    fun shouldFormatDate() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertThat(Date.from(Instant.EPOCH).toSimpleFormat()).isEqualTo("1969.12.31")
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertThat(Date.from(Instant.EPOCH).toSimpleFormat()).isEqualTo("1970.01.01")
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
