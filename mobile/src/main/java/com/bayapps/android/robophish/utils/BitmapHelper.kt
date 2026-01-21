package com.bayapps.android.robophish.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

object BitmapHelper {
    // Max read limit that we allow our input stream to mark/reset.
    private const val MAX_READ_LIMIT_PER_IMG = 1024 * 1024

    @JvmStatic
    fun scaleBitmap(src: Bitmap?, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (src == null) return null
        val scaleFactor = min(
            maxWidth.toDouble() / src.width.toDouble(),
            maxHeight.toDouble() / src.height.toDouble()
        )
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scaleFactor).toInt(),
            (src.height * scaleFactor).toInt(),
            false
        )
    }

    @JvmStatic
    fun scaleBitmap(scaleFactor: Int, inputStream: InputStream): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = scaleFactor
        }
        return BitmapFactory.decodeStream(inputStream, null, options)
    }

    @JvmStatic
    fun findScaleFactor(targetW: Int, targetH: Int, inputStream: InputStream): Int {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        val actualW = options.outWidth
        val actualH = options.outHeight
        return min(actualW / targetW, actualH / targetH)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun fetchAndRescaleBitmap(uri: String, width: Int, height: Int): Bitmap? {
        val url = URL(uri)
        var inputStream: BufferedInputStream? = null
        try {
            val urlConnection = url.openConnection() as HttpURLConnection
            inputStream = BufferedInputStream(urlConnection.inputStream)
            inputStream.mark(MAX_READ_LIMIT_PER_IMG)
            val scaleFactor = findScaleFactor(width, height, inputStream)
            Timber.d(
                "Scaling bitmap %s by factor %s to support %s x %s requested dimension",
                uri,
                scaleFactor,
                width,
                height
            )
            inputStream.reset()
            return scaleBitmap(scaleFactor, inputStream)
        } finally {
            inputStream?.close()
        }
    }
}
