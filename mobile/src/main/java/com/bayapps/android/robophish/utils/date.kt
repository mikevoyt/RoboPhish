package com.bayapps.android.robophish.utils

import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.*

private val SIMPLE_DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.US)

fun Date.toSimpleFormat(): String = SIMPLE_DATE_FORMAT.format(this)
