package com.kartingtracker.ui.comparison

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TrackMapBitmapStore {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    suspend fun load(path: String?): Bitmap? {
        if (path.isNullOrBlank()) {
            return null
        }
        cache.get(path)?.takeIf { bitmap -> !bitmap.isRecycled }?.let { cachedBitmap ->
            return cachedBitmap
        }
        return withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.also { bitmap ->
                cache.put(path, bitmap)
            }
        }
    }
}
