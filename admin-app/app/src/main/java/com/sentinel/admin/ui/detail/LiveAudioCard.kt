package com.sentinel.admin.ui.detail

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale
import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentCoral
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaButtonSurface
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary
import kotlin.math.sin

/**
 * Live Audio surveillance & PTT intercom card aligned with Figma concept:
 * "Concept / 02 / Telemetry · expanded" & "Concept / 07 / Telemetry · collapsed".
 *
 * Implements Work Orders 1, 2, 7 & 8:
 * - Restored PTT safety arm switch and admin-local RECORD_AUDIO permission check
 * - Distinct PTT states: Disarmed/Locked, Armed/Idle, Permission Denied, Pending, Transmitting, and Failed
 * - A held finger alone never displays "Transmitting" without hardware/ViewModel confirmation
 * - Stop/cancel availability: Stop is available for paused/offline/connecting/buffering sessions and recordings
 * - Authoritative duration derived from Opus audio statistics framesDecoded * 20ms
 * - Clean teardown on screen exit (DisposableEffect)
 * - 48dp minimum touch targets, accessibility semantics, and reduced-motion compliance
 */
@Composable
fun LiveAudioCard(
    playbackState: PlaybackState,
    audioStats: AudioStatistics,
    onListenClick: () -> Unit,
    onStopClick: () -> Unit,
    isOnline: Boolean,
    isPttArmed: Boolean = false,
    onPttArmedChange: (Boolean) -> Unit = {},
    isPttTransmitting: Boolean = false,
    pttAudioLevel: Float = 0f,
    incomingAudioLevel: Float = 0f,
    incomingAudioDbFs: Int = -60,
    incomingAudioFreqHz: Int = 0,
    incomingAcousticClass: String = "QUIET ROOM",
    onPttStart: () -> Unit = {},
    onPttStop: () -> Unit = {},
    isRecording: Boolean = false,
    recordingDurationMs: Long = 0L,
    onRecordToggle: () -> Unit = {},
    onRecordingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    // Admin local microphone permission state
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    // Platform reduced-motion check
    val isReducedMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    val isPlaying = playbackState is PlaybackState.Playing
    val isConnecting = playbackState is PlaybackState.Connecting
    val isBuffering = playbackState is PlaybackState.Buffering
    val isPaused = playbackState is PlaybackState.Paused
    val isError = playbackState is PlaybackState.Error

    // Work Order 2: Paused playback or active recording is an existing session, not idle
    val isSessionActive = LiveAudioPresentationPolicy.isSessionActive(playbackState, isRecording)

    // Work Order 7: Authoritative session duration derived from decoded Opus frame count (20ms each)
    val authoritativeSeconds = LiveAudioPresentationPolicy.calculateAuthoritativeSeconds(audioStats)

    // Local gesture press pending intent
    var isPressPending by remember { mutableStateOf(false) }

    val pttOwner = remember { PttActivationOwner() }
    val currentIsTransmitting by rememberUpdatedState(isPttTransmitting)
    val currentOnPttStop by rememberUpdatedState(onPttStop)
    val currentOnPttStart by rememberUpdatedState(onPttStart)

    // Shared idempotent cleanup owner for overlapping cancellation paths (dispose, gesture cancel, permission denial, disarm, offline)
    val performPttCleanup = remember {
        {
            isPressPending = false
            val activeToken = pttOwner.current()
            if (activeToken != null && pttOwner.consume(activeToken)) {
                currentOnPttStop()
            }
        }
    }

    // Clean teardown if composable leaves composition or navigation occurs
    DisposableEffect(Unit) {
        onDispose {
            performPttCleanup()
        }
    }

    // Local cleanup if disarmed or going offline while locally active
    LaunchedEffect(isPttArmed, isOnline) {
        if (!isPttArmed || !isOnline) {
            performPttCleanup()
        }
    }

    // Continuous wave animation phase when playing or transmitting (suspended when idle or reduced-motion)
    val infiniteTransition = rememberInfiniteTransition(label = "waveAnim")
    val phase by if ((isPlaying || isPttTransmitting) && !isReducedMotion) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wavePhase"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

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
            // --- 1. Header: "Live audio" + Status Pill ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live audio",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )

                val (badgeText, badgeColor, badgeBg) = when {
                    isPlaying -> Triple("ACTIVE", FigmaAccentGreen, FigmaAccentGreen.copy(alpha = 0.16f))
                    isConnecting -> Triple("CONNECTING", Color(0xFFE09F3E), Color(0xFFE09F3E).copy(alpha = 0.16f))
                    isBuffering -> Triple("BUFFERING", Color(0xFFE09F3E), Color(0xFFE09F3E).copy(alpha = 0.16f))
                    isPaused -> Triple("PAUSED", FigmaAccentBlue, FigmaAccentBlue.copy(alpha = 0.16f))
                    isError -> Triple("ERROR", FigmaAccentCoral, FigmaAccentCoral.copy(alpha = 0.16f))
                    !isOnline -> Triple("OFFLINE", FigmaTextMuted, FigmaButtonSurface)
                    else -> Triple("STANDBY", FigmaTextMuted, FigmaButtonSurface)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // --- 2. Live Audio Waveform Visualizer ---
            WaveformVisualizer(
                isPlaying = isPlaying,
                isPttTransmitting = isPttTransmitting,
                pttAudioLevel = pttAudioLevel,
                phase = phase,
                incomingAudioLevel = incomingAudioLevel,
                incomingAudioDbFs = incomingAudioDbFs,
                incomingAudioFreqHz = incomingAudioFreqHz,
                incomingAcousticClass = incomingAcousticClass,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // --- 3. Session Metadata: Authoritative Elapsed Time & State ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val timeText = when {
                    isPlaying -> {
                        val m = authoritativeSeconds / 60
                        val s = authoritativeSeconds % 60
                        String.format(java.util.Locale.US, "%02d:%02d received", m, s)
                    }
                    isConnecting -> "Connecting…"
                    isBuffering -> "Buffering…"
                    isPaused -> "Paused"
                    else -> "00:00 standby"
                }

                Text(
                    text = timeText,
                    fontSize = 13.sp,
                    color = FigmaTextMuted,
                    fontFamily = FontFamily.Monospace
                )

                val stateText = when {
                    isPlaying -> "Receiving"
                    isConnecting -> "Handshake…"
                    isBuffering -> "Jitter buffer…"
                    isPaused -> "Stream paused"
                    isError -> "Stream failed"
                    !isOnline -> "Host offline"
                    else -> "Idle"
                }
                val stateColor = when {
                    isPlaying -> FigmaAccentGreen
                    isConnecting || isBuffering -> Color(0xFFE09F3E)
                    isPaused -> FigmaAccentBlue
                    isError -> FigmaAccentCoral
                    else -> FigmaTextMuted
                }

                Text(
                    text = stateText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- 4. PTT Intercom Safety Arm Switch (Work Order 1) ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(FigmaButtonSurface)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Intercom PTT safety arm",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = FigmaTextPrimary
                    )
                    Text(
                        text = if (isPttArmed) "Armed · Hold button to broadcast" else "Disarmed · Mic transmission locked",
                        fontSize = 11.sp,
                        color = if (isPttArmed) FigmaAccentGreen else FigmaTextMuted
                    )
                }

                Switch(
                    checked = isPttArmed,
                    onCheckedChange = { armed ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPttArmedChange(armed)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = FigmaAccentGreen,
                        checkedTrackColor = FigmaAccentGreen.copy(alpha = 0.35f),
                        uncheckedThumbColor = FigmaTextMuted,
                        uncheckedTrackColor = FigmaCardBorder
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = "PTT Safety Arm Switch"
                        stateDescription = if (isPttArmed) "Armed" else "Disarmed"
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // --- 5. Controls: [ Stop / Listen ] and [ Hold to talk / Locked ] ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left action button: Stop or Listen
                // Work Order 2: Stop is available for paused/offline/connecting/buffering sessions and recordings
                // Listen is only available when not active and online
                val isStopEligible = LiveAudioPresentationPolicy.isStopEligible(playbackState, isRecording)
                val isListenEligible = LiveAudioPresentationPolicy.isListenEligible(playbackState, isRecording, isOnline)
                val isLeftButtonEnabled = isStopEligible || isListenEligible

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isLeftButtonEnabled) FigmaButtonSurface else FigmaButtonSurface.copy(alpha = 0.4f))
                        .border(1.dp, FigmaCardBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = isLeftButtonEnabled) {
                            if (isStopEligible) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStopClick()
                            } else if (isListenEligible) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onListenClick()
                            }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = if (isStopEligible) "Stop live audio stream" else "Listen to live audio stream"
                            stateDescription = if (isStopEligible) "Session active" else if (isOnline) "Ready" else "Host offline"
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        if (isStopEligible) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = null,
                                tint = FigmaAccentCoral,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Stop",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = FigmaTextPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = null,
                                tint = if (isListenEligible) FigmaAccentBlue else FigmaTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Listen",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isListenEligible) FigmaTextPrimary else FigmaTextMuted
                            )
                        }
                    }
                }

                // Right action button: PTT Hold-to-Talk (Work Order 1)
                // Distinct states: Disarmed/Locked, Permission Denied, Pending, Transmitting, and Armed/Idle
                val pttStateLabel = LiveAudioPresentationPolicy.resolvePttLabel(
                    isPttArmed = isPttArmed,
                    hasMicPermission = hasMicPermission,
                    isPressPending = isPressPending,
                    isPttTransmitting = isPttTransmitting
                )

                val pttStateBg = when {
                    isPttTransmitting -> FigmaAccentGreen
                    isPressPending -> Color(0xFFE09F3E)
                    !isPttArmed -> FigmaButtonSurface.copy(alpha = 0.5f)
                    else -> FigmaButtonSurface
                }

                val pttTextColor = when {
                    isPttTransmitting -> Color(0xFF0B0F14)
                    isPressPending -> Color(0xFF0B0F14)
                    !isPttArmed -> FigmaTextMuted
                    else -> FigmaTextPrimary
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(pttStateBg)
                        .border(
                            1.dp,
                            if (isPttTransmitting) FigmaAccentGreen else FigmaCardBorder,
                            RoundedCornerShape(12.dp)
                        )
                        .pointerInput(isPttArmed, hasMicPermission, isOnline) {
                            if (!isPttArmed || !isOnline) return@pointerInput
                            if (!hasMicPermission) {
                                detectTapGestures(
                                    onTap = {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                )
                                return@pointerInput
                            }

                            detectTapGestures(
                                onPress = {
                                    val token = pttOwner.begin() ?: return@detectTapGestures
                                    isPressPending = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    try {
                                        currentOnPttStart()
                                        awaitRelease()
                                    } finally {
                                        isPressPending = false
                                        if (pttOwner.consume(token)) {
                                            currentOnPttStop()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                }
                            )
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = "Push-to-talk intercom button"
                            stateDescription = pttStateLabel
                            onClick(label = if (isPttTransmitting || isPressPending) "Stop transmitting" else "Start transmitting") {
                                if (!isPttArmed || !isOnline) {
                                    false
                                } else if (!hasMicPermission) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    true
                                } else {
                                    if (isPttTransmitting || isPressPending) {
                                        performPttCleanup()
                                    } else {
                                        val token = pttOwner.begin()
                                        if (token != null) {
                                            try {
                                                currentOnPttStart()
                                            } catch (e: Exception) {
                                                pttOwner.consume(token)
                                                throw e
                                            }
                                        }
                                    }
                                    true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        val iconVector = when {
                            !isPttArmed -> Icons.Default.Lock
                            !hasMicPermission -> Icons.Default.MicOff
                            else -> Icons.Default.Mic
                        }

                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = pttTextColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = pttStateLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = pttTextColor
                        )
                    }
                }
            }

            // --- 6. Secondary Tools: Record toggle & Saved recordings ---
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Recording toggle
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = isSessionActive || isRecording) {
                            onRecordToggle()
                        }
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (isRecording) "Stop audio recording" else "Record stream"
                            stateDescription = if (isRecording) "Recording active" else "Recording inactive"
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = if (isRecording) FigmaAccentCoral else FigmaTextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRecording) {
                            val sec = (recordingDurationMs / 1000) % 60
                            val min = (recordingDurationMs / (1000 * 60)) % 60
                            String.format(java.util.Locale.US, "REC %02d:%02d", min, sec)
                        } else {
                            "Record stream"
                        },
                        fontSize = 12.sp,
                        fontWeight = if (isRecording) FontWeight.Bold else FontWeight.Normal,
                        color = if (isRecording) FigmaAccentCoral else FigmaTextMuted
                    )
                }

                // Link to saved recordings
                Text(
                    text = "Saved recordings ›",
                    fontSize = 12.sp,
                    color = FigmaAccentBlue,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRecordingsClick() }
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "View saved recordings gallery"
                        }
                )
            }
        }
    }
}

/**
 * 46-bar dynamic waveform visualizer.
 *
 * Implements Work Order 7 & 9:
 * - When transmitting (PTT): driven by real measured [pttAudioLevel] (0.0f..1.0f).
 * - When receiving (Listening): displays a decorative harmonic wave with an explicit truthful label
 *   stating that incoming stream is decorative spectrum (measured dBFS unavailable).
 * - Idle / Standby: static baseline bars with zero animation overhead.
 */
@Composable
private fun WaveformVisualizer(
    isPlaying: Boolean,
    isPttTransmitting: Boolean,
    pttAudioLevel: Float,
    phase: Float,
    incomingAudioLevel: Float = 0f,
    incomingAudioDbFs: Int = -60,
    incomingAudioFreqHz: Int = 0,
    incomingAcousticClass: String = "QUIET ROOM",
    modifier: Modifier = Modifier
) {
    val barCount = 46
    val activeColor = if (isPttTransmitting) FigmaAccentGreen else FigmaAccentBlue
    val idleColor = Color(0xFF293542)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val totalWidth = size.width
            val canvasHeight = size.height
            val centerY = canvasHeight / 2f
            val step = totalWidth / barCount
            val barWidth = (step * 0.55f).coerceIn(2.5f, 5.5f)

            for (i in 0 until barCount) {
                val x = i * step + step / 2f
                val normalizedIdx = i.toFloat() / barCount

                val barHeight = when {
                    isPttTransmitting -> {
                        // Driven by real measured pttAudioLevel [0.0f..1.0f]
                        val levelClamped = pttAudioLevel.coerceIn(0.04f, 1.0f)
                        val envelope = sin(normalizedIdx * Math.PI).toFloat()
                        val ripple = (sin(phase + i * 0.8) * 0.12f).toFloat()
                        val fraction = (levelClamped * envelope * 0.82f + 0.1f + ripple).coerceIn(0.08f, 1.0f)
                        canvasHeight * fraction
                    }
                    isPlaying -> {
                        // Harmonic composite sine waves for incoming audio aesthetic
                        val wave1 = sin(phase + normalizedIdx * 12.0).toFloat()
                        val wave2 = sin(phase * 1.5 + normalizedIdx * 20.0).toFloat()
                        val wave3 = sin(phase * 0.8 + normalizedIdx * 6.0).toFloat()
                        val composite = ((wave1 * 0.45f + wave2 * 0.35f + wave3 * 0.20f) + 1f) / 2f
                        val envelope = sin(normalizedIdx * Math.PI).toFloat()
                        val liveBoost = if (incomingAudioDbFs > -60) incomingAudioLevel.coerceIn(0.15f, 1.0f) else 1.0f
                        val heightFraction = (composite * envelope * 0.82f * liveBoost + 0.12f).coerceIn(0.1f, 1.0f)
                        canvasHeight * heightFraction
                    }
                    else -> {
                        val restingEnvelope = sin(normalizedIdx * Math.PI).toFloat()
                        (canvasHeight * 0.15f * restingEnvelope + 4f).coerceIn(4f, 10f)
                    }
                }

                val topY = centerY - barHeight / 2f
                val bottomY = centerY + barHeight / 2f

                drawLine(
                    color = if (isPlaying || isPttTransmitting) activeColor else idleColor,
                    start = Offset(x, topY),
                    end = Offset(x, bottomY),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        val labelText = when {
            isPttTransmitting -> String.format(Locale.US, "Outgoing audio-level visualization · %.0f%%", pttAudioLevel * 100f)
            isPlaying && incomingAudioDbFs > -60 -> "Live DSP Stream · $incomingAudioDbFs dBFS · $incomingAudioFreqHz Hz ($incomingAcousticClass)"
            isPlaying -> "Incoming stream (decorative spectrum · measured dBFS unavailable)"
            else -> "Waveform idle"
        }
        val labelColor = when {
            isPttTransmitting -> FigmaAccentGreen
            isPlaying -> FigmaAccentBlue
            else -> FigmaTextMuted
        }
        Text(
            text = labelText,
            fontSize = 10.sp,
            color = labelColor
        )
    }
}
