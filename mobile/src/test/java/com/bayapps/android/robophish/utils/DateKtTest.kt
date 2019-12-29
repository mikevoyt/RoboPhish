package com.bayapps.android.robophish.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

class DateKtTest {
    @Test
    fun shouldFormatDate() {
        val data = GregorianCalendar(1999, 11, 31).time
        val result = data.toSimpleFormat()
        assertThat(result).isEqualTo("1999.12.31")
    }
}
