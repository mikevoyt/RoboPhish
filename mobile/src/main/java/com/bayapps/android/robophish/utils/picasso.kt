package com.bayapps.android.robophish.utils

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import com.squareup.picasso.Picasso
import com.squareup.picasso.Target
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val MAX_ART_WIDTH = 800
private const val MAX_ART_HEIGHT = 480

private const val MAX_ART_WIDTH_ICON = 128
private const val MAX_ART_HEIGHT_ICON = 128

data class ImageAndIcon(val image: Bitmap, val icon: Bitmap)

fun Picasso.loadLargeAndSmallImage(url: String, callback: (ImageAndIcon) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        val large = async { loadImage(url, maxWidth = MAX_ART_WIDTH, maxHeight = MAX_ART_HEIGHT) }
        val icon = async { loadImage(url, maxWidth = MAX_ART_WIDTH_ICON, maxHeight = MAX_ART_HEIGHT_ICON) }

        val bitmap = large.await()
        val iconBitmap = icon.await()

        callback(ImageAndIcon(image = bitmap, icon = iconBitmap))
        cancel()
    }
}

private suspend fun Picasso.loadImage(url: String, maxWidth: Int, maxHeight: Int): Bitmap {
    return suspendCoroutine { continuation ->
        load(url).resize(maxWidth, maxHeight)
                .centerInside()
                .into(object : SimpleTarget() {
                    override fun onBitmapLoaded(bitmap: Bitmap, from: Picasso.LoadedFrom?) {
                        continuation.resume(bitmap)
                    }

                    override fun onBitmapFailed(e: Exception, errorDrawable: Drawable) {
                        continuation.resumeWithException(e)
                    }
                })
    }
}

abstract class SimpleTarget : Target {
    override fun onPrepareLoad(placeHolderDrawable: Drawable?) {}
    override fun onBitmapFailed(e: Exception, errorDrawable: Drawable) {}
}
