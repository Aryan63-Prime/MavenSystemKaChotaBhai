package com.sentinel.admin.ui.detail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaScreenBackground
import com.sentinel.admin.ui.theme.FigmaTabSelected
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val id: String,
    val timeLabel: String,
    val cameraFacing: String,
    val base64Data: String
)

/**
 * Media Gallery view matching Figma concept:
 * "Concept / 05 / Media gallery".
 *
 * Implements Work Orders 5 & 8:
 * - Removed 6 hardcoded demo cards from production gallery
 * - Safe off-main-thread image decoding with inJustDecodeBounds & inSampleSize limits
 * - Stable media identity based on hash/timestamp
 * - Read-only modal preview on tap (never triggers remote capture)
 * - Truthful empty state when no captures exist in the active session
 * - 48dp minimum touch targets with accessibility semantics
 */
@Composable
fun MediaGalleryView(
    capturedPhotoBase64: String = "",
    capturedPhotoFacing: String = "REAR",
    sessionMediaItems: List<MediaItem> = emptyList(),
    modifier: Modifier = Modifier
) {
    var selectedFilterIndex by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val filters = listOf("All", "Rear camera", "Front")

    var previewItem by remember { mutableStateOf<MediaItem?>(null) }

    // Work Order 5: Build media list ONLY from genuine captured media
    val mediaItems = remember(capturedPhotoBase64, capturedPhotoFacing, sessionMediaItems) {
        if (sessionMediaItems.isNotEmpty()) {
            sessionMediaItems
        } else {
            val list = mutableListOf<MediaItem>()
            if (capturedPhotoBase64.isNotBlank()) {
                list.add(
                    MediaItem(
                        id = "capture-${capturedPhotoBase64.hashCode()}",
                        timeLabel = "Session capture",
                        cameraFacing = if (capturedPhotoFacing.equals("FRONT", ignoreCase = true)) "Front camera" else "Rear camera",
                        base64Data = capturedPhotoBase64
                    )
                )
            }
            list
        }
    }

    val filteredItems = remember(selectedFilterIndex, mediaItems) {
        when (selectedFilterIndex) {
            1 -> mediaItems.filter { it.cameraFacing.contains("Rear", ignoreCase = true) }
            2 -> mediaItems.filter { it.cameraFacing.contains("Front", ignoreCase = true) }
            else -> mediaItems
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Filter pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEachIndexed { index, filter ->
                val isSelected = selectedFilterIndex == index
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) FigmaTabSelected else FigmaScreenBackground)
                        .border(
                            1.dp,
                            if (isSelected) FigmaAccentBlue else FigmaCardBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { selectedFilterIndex = index }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter,
                        maxLines = 1,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) FigmaAccentBlue else FigmaTextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredItems.isEmpty()) {
            // Work Order 5: Truthful empty state
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
                border = BorderStroke(1.dp, FigmaCardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = FigmaTextMuted,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No media in current session",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = FigmaTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Historical server media persistence is not supported in the backend API. Photos captured using Air Commands will appear here during this session.",
                        fontSize = 12.sp,
                        color = FigmaTextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            // 2-column grid of genuine media
            val chunked = filteredItems.chunked(2)
            chunked.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { item ->
                        MediaThumbnailCard(
                            item = item,
                            onClick = { previewItem = item },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Read-only photo preview dialog
    previewItem?.let { item ->
        ReadOnlyPhotoPreviewDialog(
            item = item,
            onDismiss = { previewItem = null }
        )
    }
}

/**
 * Safely decodes base64 thumbnail with bounds inspection and subsampling off the main thread.
 */
@Composable
private fun MediaThumbnailCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(item.id) { mutableStateOf(true) }
    var isError by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(item.base64Data) {
        isLoading = true
        isError = false
        bitmap = SafeImageDecoder.decode(item.base64Data, maxEdge = 512)
        isLoading = false
        if (bitmap == null) isError = true
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .semantics {
                role = Role.Button
                contentDescription = "${item.cameraFacing} photo, ${item.timeLabel}. Tap for read-only preview."
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
        border = BorderStroke(1.dp, FigmaCardBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.33f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Color(0xFF1E2833)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = FigmaAccentBlue,
                        strokeWidth = 2.dp
                    )
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Capture preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    else -> Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = "Invalid image",
                        tint = FigmaTextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Text(
                    text = item.timeLabel,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.cameraFacing,
                    fontSize = 11.sp,
                    color = FigmaTextMuted
                )
            }
        }
    }
}

/**
 * Read-only preview dialog that strictly does not trigger any remote capture or mutate state.
 * Employs explicit Loading, Ready, and Error states with retry and close actions.
 */
@Composable
private fun ReadOnlyPhotoPreviewDialog(
    item: MediaItem,
    onDismiss: () -> Unit
) {
    var previewState by remember(item.id) { mutableStateOf<PhotoPreviewState>(PhotoPreviewState.Loading) }
    var retryCount by remember(item.id) { mutableIntStateOf(0) }

    LaunchedEffect(item.base64Data, retryCount) {
        previewState = PhotoPreviewState.Loading
        val bitmap = SafeImageDecoder.decode(item.base64Data, maxEdge = 2048)
        previewState = if (bitmap != null) {
            PhotoPreviewState.Ready(bitmap)
        } else {
            PhotoPreviewState.Error("Invalid or unsupported capture format")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xF0080C14)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = item.cameraFacing,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaTextPrimary
                        )
                        Text(
                            text = "${item.timeLabel} · Read-only preview",
                            fontSize = 12.sp,
                            color = FigmaTextMuted
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (previewState is PhotoPreviewState.Ready) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val readyBitmap = (previewState as PhotoPreviewState.Ready).bitmap
                            IconButton(onClick = {
                                saveBitmapToGallery(context, readyBitmap)
                                android.widget.Toast.makeText(context, "Saved photo to Gallery", android.widget.Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.SaveAlt,
                                    contentDescription = "Save to Gallery",
                                    tint = FigmaAccentBlue
                                )
                            }
                        }

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close preview",
                                tint = FigmaTextPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF101720)),
                    contentAlignment = Alignment.Center
                ) {
                    when (val state = previewState) {
                        is PhotoPreviewState.Loading -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    color = FigmaAccentBlue,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Decoding capture…",
                                    fontSize = 13.sp,
                                    color = FigmaTextMuted
                                )
                            }
                        }
                        is PhotoPreviewState.Ready -> {
                            Image(
                                bitmap = state.bitmap.asImageBitmap(),
                                contentDescription = "Full photo preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        is PhotoPreviewState.Error -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BrokenImage,
                                    contentDescription = null,
                                    tint = FigmaTextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = state.message,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = FigmaTextPrimary
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Retry button
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(FigmaAccentBlue)
                                            .clickable { retryCount++ }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Retry",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    // Close button
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF242C38))
                                            .clickable { onDismiss() }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Close",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = FigmaTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun saveBitmapToGallery(context: android.content.Context, bitmap: Bitmap) {
    val filename = "Sentinel_Recon_${System.currentTimeMillis()}.jpg"
    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Sentinel")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        resolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)
        }
    }
}
