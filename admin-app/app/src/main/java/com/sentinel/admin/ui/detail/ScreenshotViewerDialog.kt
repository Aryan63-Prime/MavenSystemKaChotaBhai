package com.sentinel.admin.ui.detail

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ScreenshotViewerDialog(
    payload: Map<String, Any?>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val imageBase64 = payload["imageBase64"] as? String ?: ""
    val width = (payload["width"] as? Number)?.toInt() ?: 1080
    val height = (payload["height"] as? Number)?.toInt() ?: 2400
    val timestamp = (payload["timestamp"] as? Number)?.toLong() ?: (System.currentTimeMillis() / 1000)

    val bitmap: Bitmap? = remember(imageBase64) {
        if (imageBase64.isNotBlank()) {
            try {
                val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
                decodeSubsampledBitmap(bytes, 2048)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        if (scale > 1f) offset += offsetChange else offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Host Screen Capture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        val formattedTime = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
                            .format(Date(timestamp * 1000))
                        Text(formattedTime, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Row {
                        IconButton(onClick = {
                            if (bitmap != null) {
                                saveBitmapToGallery(context, bitmap)
                                Toast.makeText(context, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.SaveAlt, contentDescription = "Save")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                // Interactive Image View with Zoom/Pan
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .transformable(state = transformState),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Screen Capture",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("Unable to render image payload", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap) {
    val filename = "Sentinel_Screenshot_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sentinel")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        resolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }
    }
}

private fun decodeSubsampledBitmap(bytes: ByteArray, maxEdge: Int = 2048): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    var sampleSize = 1
    if (width > maxEdge || height > maxEdge) {
        val halfW = width / 2
        val halfH = height / 2
        while ((halfW / sampleSize) >= maxEdge || (halfH / sampleSize) >= maxEdge) {
            sampleSize *= 2
        }
    }
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize.coerceAtLeast(1)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
}
