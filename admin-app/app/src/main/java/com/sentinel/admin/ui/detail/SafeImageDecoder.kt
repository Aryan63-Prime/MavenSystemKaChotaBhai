package com.sentinel.admin.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface PhotoPreviewState {
    data object Loading : PhotoPreviewState
    data class Ready(val bitmap: Bitmap) : PhotoPreviewState
    data class Error(val message: String) : PhotoPreviewState
}

/**
 * Calculates powers-of-two subsampling such that NEITHER dimension exceeds maxEdge.
 * Stopping condition accounts for EITHER dimension exceeding the cap.
 */
fun thumbnailSampleSize(width: Int, height: Int, maxEdge: Int = 512): Int {
    require(width > 0 && height > 0) { "Dimensions must be positive ($width x $height)" }
    require(maxEdge in 32..4096) { "maxEdge must be within 32..4096 ($maxEdge)" }
    var sample = 1
    fun exceedsCap(dimension: Int): Boolean =
        (dimension.toLong() + sample - 1L) / sample > maxEdge
    while (exceedsCap(width) || exceedsCap(height)) {
        sample = sample shl 1
    }
    return sample
}

object SafeImageDecoder {

    const val DEFAULT_MAX_ENCODED_CHARS = 20 * 1024 * 1024 // 20 MB encoded string cap
    const val DEFAULT_MAX_DECODED_BYTES = 15 * 1024 * 1024 // 15 MB raw byte cap
    const val DEFAULT_MAX_PIXELS = 16 * 1024 * 1024 // 16 MegaPixels cap

    /**
     * Safely decodes a Base64 image off the main thread with strict bounds checks:
     * 1. Rejects blank or oversized encoded strings prior to Base64 allocation.
     * 2. Inspects image bounds first (inJustDecodeBounds = true) without loading pixel memory.
     * 3. Validates positive dimensions and total pixel limit.
     * 4. Subsamples with thumbnailSampleSize ensuring neither dimension exceeds maxEdge.
     * 5. Respects coroutine cancellation.
     * 6. Does NOT rely on catching OutOfMemoryError.
     */
    suspend fun decode(
        base64Data: String,
        maxEdge: Int = 512,
        maxEncodedChars: Int = DEFAULT_MAX_ENCODED_CHARS,
        maxDecodedBytes: Int = DEFAULT_MAX_DECODED_BYTES,
        maxPixels: Int = DEFAULT_MAX_PIXELS
    ): Bitmap? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        val clean = base64Data.trim()
        if (clean.isEmpty() || clean.length > maxEncodedChars) {
            return@withContext null
        }

        val bytes = try {
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }

        if (bytes.isEmpty() || bytes.size > maxDecodedBytes) {
            return@withContext null
        }

        currentCoroutineContext().ensureActive()

        // 1. Inspect bounds
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) {
            return@withContext null
        }

        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels > maxPixels) {
            return@withContext null
        }

        currentCoroutineContext().ensureActive()

        // 2. Compute sample size
        val sampleSize = thumbnailSampleSize(width, height, maxEdge)

        // 3. Decode actual bitmap with bounded sample size and RGB_565 to conserve memory
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        currentCoroutineContext().ensureActive()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
        bitmap
    }
}
