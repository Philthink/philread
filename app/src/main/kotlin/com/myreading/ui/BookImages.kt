package com.myreading.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.myreading.core.imageSampleSize
import java.io.InputStream

internal fun decodeReadingImage(open: () -> InputStream?): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
    }
    return open()?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
}

// Limit retained decoded images by bytes rather than number of book resources.
internal object BookImageCache {
    val images = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }
}
