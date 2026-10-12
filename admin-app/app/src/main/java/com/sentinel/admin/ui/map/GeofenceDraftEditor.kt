package com.sentinel.admin.ui.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentCoral
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaButtonSurface
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary


/**
 * Local geofence draft editor.
 *
 * Implements Pass 5 Work Orders 2 & 3:
 * - Single device-keyed editor state holder with revision, dirty tracking, and bounded Undo
 * - Advances editorRevision and marks isDirty = true on EVERY user keystroke/mutation
 * - 800 ms coalescing applies strictly to Undo snapshot capture, never mutation detection or revisions
 * - Asynchronous durable storage on IO dispatcher via GeofenceDraftStore with mutex serialization
 * - Validates loaded coordinates through GeofenceGeometryValidator before presentation
 * - Clean separation: "Clear Form" clears local inputs; "Delete Saved Draft" removes persisted record
 * - Preserves unsaved fields across tab switching and process recreation via GeofenceEditorStateHolder
 * - Honest truthfulness: Local draft only; visual map drawing handles unimplemented in coordinate form
 */
@Composable
fun GeofenceDraftEditor(
    deviceId: String,
    modifier: Modifier = Modifier,
    initialLat: Double? = null,
    initialLon: Double? = null,
    draftStore: GeofenceDraftStore? = null,
    stateHolder: GeofenceEditorStateHolder? = null,
    onDraftSaved: (GeofenceDraft) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val effectiveStore = draftStore ?: remember(context) { GeofenceDraftStore.getDefault(context) }

    val holder = stateHolder ?: remember(deviceId) {
        GeofenceEditorStateHolder(
            initialDeviceId = deviceId,
            draftStore = effectiveStore,
            ownerScope = coroutineScope,
            fallbackLat = initialLat,
            fallbackLon = initialLon
        )
    }

    var newVertexLat by rememberSaveable { mutableStateOf(initialLat?.toString() ?: "") }
    var newVertexLon by rememberSaveable { mutableStateOf(initialLon?.toString() ?: "") }

    LaunchedEffect(deviceId) {
        if (holder.deviceId != deviceId) {
            holder.resetForDevice(deviceId, initialLat, initialLon)
        } else if (holder.needsBaselineReconciliation) {
            holder.needsBaselineReconciliation = false
            holder.loadDraft(DraftLoadMode.RECONCILE_RESTORED_BASELINE)
        } else if (!holder.hasPersistedDraft && holder.editorRevision == 0L) {
            holder.loadDraft(DraftLoadMode.INITIAL_PRISTINE_FORM)
        }
    }

    val isCircle = holder.selectedType == "CIRCLE"
    val isPolygon = holder.selectedType == "POLYGON"
    val canUndo = holder.historyStack.isNotEmpty()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = FigmaCardSurface),
        border = BorderStroke(1.dp, FigmaCardBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Geofence Draft Editor",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )

                // Status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE09F3E).copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (holder.isDirty) "UNSAVED EDITS (rev ${holder.editorRevision})" else "LOCAL DRAFT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE09F3E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Truthful disclaimer banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141E28))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = FigmaAccentBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Local Draft Only: Server persistence and remote alarms are unsupported. Map visual drawing handles are not implemented in this coordinate form.",
                    fontSize = 12.sp,
                    color = FigmaTextMuted,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Shape selector pills: [ Circle Perimeter ] [ Polygon Zone ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCircle) FigmaAccentBlue.copy(alpha = 0.2f) else FigmaButtonSurface)
                        .border(
                            1.dp,
                            if (isCircle) FigmaAccentBlue else FigmaCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { holder.onTypeSelected("CIRCLE") }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Circle Perimeter",
                        fontSize = 13.sp,
                        fontWeight = if (isCircle) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCircle) FigmaAccentBlue else FigmaTextMuted
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isPolygon) FigmaAccentBlue.copy(alpha = 0.2f) else FigmaButtonSurface)
                        .border(
                            1.dp,
                            if (isPolygon) FigmaAccentBlue else FigmaCardBorder,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { holder.onTypeSelected("POLYGON") }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Polygon Zone",
                        fontSize = 13.sp,
                        fontWeight = if (isPolygon) FontWeight.Bold else FontWeight.Normal,
                        color = if (isPolygon) FigmaAccentBlue else FigmaTextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inputs according to selected shape
            if (isCircle) {
                Text(
                    text = "Center Coordinates & Radius:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = FigmaTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = holder.circleLatInput,
                        onValueChange = { holder.onCircleLatChanged(it) },
                        label = { Text("Latitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FigmaAccentBlue,
                            unfocusedBorderColor = FigmaCardBorder,
                            focusedTextColor = FigmaTextPrimary,
                            unfocusedTextColor = FigmaTextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = holder.circleLonInput,
                        onValueChange = { holder.onCircleLonChanged(it) },
                        label = { Text("Longitude") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FigmaAccentBlue,
                            unfocusedBorderColor = FigmaCardBorder,
                            focusedTextColor = FigmaTextPrimary,
                            unfocusedTextColor = FigmaTextPrimary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = holder.circleRadiusInput,
                    onValueChange = { holder.onCircleRadiusChanged(it) },
                    label = { Text("Radius (meters, min 10m - max 50km)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FigmaAccentBlue,
                        unfocusedBorderColor = FigmaCardBorder,
                        focusedTextColor = FigmaTextPrimary,
                        unfocusedTextColor = FigmaTextPrimary
                    )
                )
            } else {
                Text(
                    text = "Polygon Vertices (${holder.polygonVertices.size} added, min 3 required):",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = FigmaTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (holder.polygonVertices.isEmpty()) {
                    Text(
                        text = "No vertices added yet. Enter latitude and longitude below to add perimeter vertices.",
                        fontSize = 12.sp,
                        color = FigmaTextMuted
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF101720))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        holder.polygonVertices.forEachIndexed { idx, (vLat, vLon) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "V${idx + 1}: $vLat, $vLon",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = FigmaTextPrimary
                                )
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Remove vertex ${idx + 1}",
                                    tint = FigmaAccentCoral,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clickable { holder.onRemovePolygonVertex(idx) }
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newVertexLat,
                        onValueChange = { newVertexLat = it },
                        label = { Text("Lat") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FigmaAccentBlue,
                            unfocusedBorderColor = FigmaCardBorder,
                            focusedTextColor = FigmaTextPrimary,
                            unfocusedTextColor = FigmaTextPrimary
                        )
                    )

                    OutlinedTextField(
                        value = newVertexLon,
                        onValueChange = { newVertexLon = it },
                        label = { Text("Lon") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FigmaAccentBlue,
                            unfocusedBorderColor = FigmaCardBorder,
                            focusedTextColor = FigmaTextPrimary,
                            unfocusedTextColor = FigmaTextPrimary
                        )
                    )

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(FigmaButtonSurface)
                            .border(1.dp, FigmaCardBorder, RoundedCornerShape(8.dp))
                            .clickable {
                                val lat = newVertexLat.toDoubleOrNull()
                                val lon = newVertexLon.toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    holder.onAddPolygonVertex(lat, lon)
                                } else {
                                    holder.validationError = "Please enter valid numeric latitude and longitude for new vertex"
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Vertex",
                            tint = FigmaAccentBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Validation error banner
            holder.validationError?.let { err ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FigmaAccentCoral.copy(alpha = 0.15f))
                        .border(1.dp, FigmaAccentCoral.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = FigmaAccentCoral,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = err,
                        fontSize = 12.sp,
                        color = FigmaAccentCoral,
                        lineHeight = 16.sp
                    )
                }
            }

            // Status feedback message
            holder.statusMessage?.let { msg ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(FigmaAccentGreen.copy(alpha = 0.15f))
                        .border(1.dp, FigmaAccentGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = FigmaAccentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        color = FigmaAccentGreen,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action row: [ Undo ] [ Clear Form ] [ Save Draft ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Undo button (48dp target)
                Box(
                    modifier = Modifier
                        .weight(0.9f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FigmaButtonSurface)
                        .border(1.dp, FigmaCardBorder, RoundedCornerShape(10.dp))
                        .clickable(enabled = canUndo) { holder.undo() }
                        .semantics {
                            role = Role.Button
                            contentDescription = if (canUndo) "Undo last edit (${holder.historyStack.size} available)" else "Nothing to undo"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = null,
                            tint = if (canUndo) FigmaTextMuted else FigmaTextMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Undo",
                            fontSize = 13.sp,
                            color = if (canUndo) FigmaTextMuted else FigmaTextMuted.copy(alpha = 0.4f)
                        )
                    }
                }

                // Clear Form button (48dp target) - Distinct from Delete Draft
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(FigmaButtonSurface)
                        .border(1.dp, FigmaCardBorder, RoundedCornerShape(10.dp))
                        .clickable { holder.clearForm() }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Clear form inputs"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Clear, contentDescription = null, tint = FigmaTextMuted, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Clear Form", fontSize = 12.sp, color = FigmaTextMuted)
                    }
                }

                // Save Local Draft button (48dp target) - Dispatched to IO coroutine
                Box(
                    modifier = Modifier
                        .weight(1.4f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (holder.isSaving) FigmaAccentBlue.copy(alpha = 0.5f) else FigmaAccentBlue)
                        .clickable(enabled = !holder.isSaving) {
                            holder.saveDraft(onDraftSaved)
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = if (holder.isSaving) "Saving geofence draft" else "Save local geofence draft"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (holder.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Saving…", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "Save Draft", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }

            // Separate Delete Saved Draft button if stored draft exists
            if (holder.hasPersistedDraft) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { holder.deletePersistedDraft() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete persisted draft from storage",
                        tint = FigmaAccentCoral,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete Saved Draft from Storage",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FigmaAccentCoral
                    )
                }
            }
        }
    }
}
