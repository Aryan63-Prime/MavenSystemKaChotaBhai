package com.sentinel.admin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HostAppLogsCard(
    logs: List<String>,
    isOnline: Boolean,
    isFetching: Boolean,
    onFetchLogs: (fullDevice: Boolean, filter: String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    var showFullScreen by remember { mutableStateOf(false) }
    var isFullDevice by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val chipScrollState = rememberScrollState()

    // Filter logs client-side instantly
    val filteredLogs = remember(logs, filterQuery) {
        if (filterQuery.isBlank()) {
            logs
        } else {
            val query = filterQuery.trim().lowercase()
            val aliases = when (query) {
                "facebook", "fb" -> listOf("facebook", "katana", "fb", "com.facebook")
                "yt", "youtube" -> listOf("youtube", "google.android.youtube", "yt")
                "host", "sentinel" -> listOf("sentinel", "host", "cmdproc")
                else -> listOf(query)
            }
            logs.filter { line ->
                aliases.any { alias -> line.contains(alias, ignoreCase = true) }
            }
        }
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Realtime background auto-poll every 5 seconds while expanded & device is online
    LaunchedEffect(expanded, isOnline, isFullDevice, filterQuery) {
        if (expanded && isOnline) {
            if (!isFetching) {
                onFetchLogs(isFullDevice, filterQuery)
            }
            while (true) {
                kotlinx.coroutines.delay(5000L)
                if (!isFetching) {
                    onFetchLogs(isFullDevice, filterQuery)
                }
            }
        }
    }

    // Full Screen Overlay Dialog
    if (showFullScreen) {
        FullScreenLogsDialog(
            logs = logs,
            isOnline = isOnline,
            isFetching = isFetching,
            initialFullDevice = isFullDevice,
            initialFilter = filterQuery,
            onFetchLogs = { full, flt ->
                isFullDevice = full
                filterQuery = flt
                onFetchLogs(full, flt)
            },
            onClearLogs = onClearLogs,
            onDismiss = { showFullScreen = false }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171F28)),
        border = BorderStroke(1.dp, Color(0xFF293542)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isFullDevice) "DEVICE LOGCAT (SYSTEM-WIDE)" else "REALTIME HOST APP LOGS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = Color(0xFF34D399)
                    )
                    Text(
                        text = if (filteredLogs.isNotEmpty()) {
                            "${filteredLogs.size} events" + (if (filterQuery.isNotBlank()) " · \"$filterQuery\"" else "")
                        } else {
                            "Stream idle"
                        },
                        fontSize = 10.sp,
                        color = Color(0xFF8B97A5)
                    )
                }

                if (isFetching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF10B981)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Fullscreen Expansion Button
                IconButton(
                    onClick = { showFullScreen = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Expand Fullscreen",
                        tint = Color(0xFF60A5FA),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Refresh Button
                IconButton(
                    onClick = { onFetchLogs(isFullDevice, filterQuery) },
                    enabled = isOnline && !isFetching,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Logs",
                        tint = if (isOnline) Color(0xFFAAC7E8) else Color(0xFF4A5568),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Collapse/Expand
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse logs" else "Expand logs",
                        tint = Color(0xFF9AA7B6),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    // Scope Selection (Host Only vs Full Device)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Scope:",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace
                        )

                        FilterChip(
                            selected = !isFullDevice,
                            onClick = {
                                isFullDevice = false
                                onFetchLogs(false, filterQuery)
                            },
                            label = { Text("Host App", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0F766E),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF151C24),
                                labelColor = Color(0xFF94A3B8)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = !isFullDevice,
                                borderColor = Color(0xFF24303E),
                                selectedBorderColor = Color(0xFF14B8A6)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        )

                        FilterChip(
                            selected = isFullDevice,
                            onClick = {
                                isFullDevice = true
                                onFetchLogs(true, filterQuery)
                            },
                            label = { Text("Full Device (Logcat)", fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1E3A8A),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF151C24),
                                labelColor = Color(0xFF94A3B8)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isFullDevice,
                                borderColor = Color(0xFF24303E),
                                selectedBorderColor = Color(0xFF3B82F6)
                            ),
                            shape = RoundedCornerShape(6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Preset Filter Chips (All, Host, YouTube, Facebook)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScrollState),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val filterPresets = listOf(
                            "All" to "",
                            "Host" to "Host",
                            "YouTube" to "yt",
                            "Facebook" to "facebook"
                        )

                        filterPresets.forEach { (label, term) ->
                            val isSelected = (term.isEmpty() && filterQuery.isEmpty()) ||
                                    (term.isNotEmpty() && filterQuery.equals(term, ignoreCase = true))

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    filterQuery = term
                                    onFetchLogs(isFullDevice, term)
                                },
                                label = { Text(label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.25f),
                                    selectedLabelColor = Color(0xFF34D399),
                                    containerColor = Color(0xFF121921),
                                    labelColor = Color(0xFF718096)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = Color(0xFF222C39),
                                    selectedBorderColor = Color(0xFF10B981)
                                ),
                                shape = RoundedCornerShape(6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Filter Search Field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0F151C))
                            .border(1.dp, Color(0xFF253342), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Filter",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        BasicTextField(
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(Color(0xFF34D399)),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (filterQuery.isEmpty()) {
                                    Text(
                                        text = "Filter (e.g. facebook, yt, host)...",
                                        color = Color(0xFF4B5563),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (filterQuery.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    filterQuery = ""
                                    onFetchLogs(isFullDevice, "")
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Terminal Screen
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF090D12))
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        if (filteredLogs.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                filteredLogs.forEach { line ->
                                    val textColor = when {
                                        line.contains("ERROR") || line.contains("FATAL") || line.contains(" E/") -> Color(0xFFF87171)
                                        line.contains("WARN") || line.contains(" W/") -> Color(0xFFFBBF24)
                                        line.contains("HOST:CORE") || line.contains("SERVICE") || line.contains("CmdProc") -> Color(0xFF38BDF8)
                                        line.contains("NET") || line.contains("WEBSOCKET") -> Color(0xFF34D399)
                                        line.contains("AUDIO") || line.contains("CAMERA") -> Color(0xFFA78BFA)
                                        line.contains("katana") || line.contains("facebook", ignoreCase = true) -> Color(0xFF60A5FA)
                                        line.contains("youtube", ignoreCase = true) || line.contains("yt", ignoreCase = true) -> Color(0xFFF87171)
                                        else -> Color(0xFF94A3B8)
                                    }
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp,
                                        color = textColor
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isOnline) {
                                        if (filterQuery.isNotBlank()) "No logs matching \"$filterQuery\". Tap Pull Trace."
                                        else "Tap Pull Trace below to stream live logs"
                                    } else {
                                        "Host offline. Logs available upon connection."
                                    },
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Tactical actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onFetchLogs(isFullDevice, filterQuery) },
                                enabled = isOnline && !isFetching,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(28.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFFAAC7E8))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Pull Trace", fontSize = 10.sp, color = Color(0xFFAAC7E8))
                            }

                            OutlinedButton(
                                onClick = { showFullScreen = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(28.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Fullscreen, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF60A5FA))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Fullscreen", fontSize = 10.sp, color = Color(0xFF60A5FA))
                            }
                        }

                        OutlinedButton(
                            onClick = onClearLogs,
                            enabled = logs.isNotEmpty(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.ClearAll, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFF94A3B8))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear", fontSize = 10.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }
    }
}
