package com.sentinel.admin.ui.common

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentCoral
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextPrimary
import kotlin.math.min

/**
 * Result outcome of a pull-to-refresh operation (retained for backward compatibility).
 */
sealed interface RefreshStatus {
    data object Idle : RefreshStatus
    data object Success : RefreshStatus
    data class Error(val message: String? = null) : RefreshStatus
}

/**
 * Unified refresh phase driving presentation state (label, alpha, accent, translation, bar cycle).
 */
enum class RefreshPhase {
    IDLE,
    PULLING,
    ARMED,
    REFRESHING,
    SUCCESS,
    ERROR
}

object SentinelRefreshPresentationPolicy {
    fun resolvePhase(
        fraction: Float,
        isRefreshing: Boolean,
        operation: RefreshOperation,
        status: RefreshStatus = RefreshStatus.Idle
    ): RefreshPhase {
        val effectiveIsRefreshing = isRefreshing || operation is RefreshOperation.Running
        val isSuccess = (operation is RefreshOperation.Success) || (status is RefreshStatus.Success && !effectiveIsRefreshing)
        val isError = (operation is RefreshOperation.Error) || (status is RefreshStatus.Error && !effectiveIsRefreshing)

        return when {
            // An active pull gesture strictly owns priority over lingering outcome states
            fraction >= 1.0f && !effectiveIsRefreshing -> RefreshPhase.ARMED
            fraction > 0.1f && !effectiveIsRefreshing -> RefreshPhase.PULLING
            effectiveIsRefreshing -> RefreshPhase.REFRESHING
            isSuccess -> RefreshPhase.SUCCESS
            isError -> RefreshPhase.ERROR
            else -> RefreshPhase.IDLE
        }
    }

    fun resolveLabel(
        fraction: Float,
        isRefreshing: Boolean,
        operation: RefreshOperation,
        status: RefreshStatus = RefreshStatus.Idle
    ): String {
        val phase = resolvePhase(fraction, isRefreshing, operation, status)
        val errorMessage = when {
            operation is RefreshOperation.Error -> operation.message
            status is RefreshStatus.Error -> status.message
            else -> null
        }
        return when (phase) {
            RefreshPhase.ARMED -> "Release to refresh"
            RefreshPhase.PULLING -> "Pull to refresh"
            RefreshPhase.REFRESHING -> "Updating…"
            RefreshPhase.SUCCESS -> "Updated"
            RefreshPhase.ERROR -> "Couldn’t update"
            RefreshPhase.IDLE -> ""
        }
    }
}

/**
 * Checks system animator duration scale for reduced motion setting.
 */
fun isSystemReducedMotionEnabled(context: Context): Boolean {
    return try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        ) == 0f
    } catch (_: Exception) {
        false
    }
}

/**
 * Live lifecycle-aware reduced-motion policy observer shared across the screen hierarchy.
 */
@Composable
fun rememberLiveReducedMotion(): Boolean {
    val context = LocalContext.current
    var isReducedMotion by remember {
        mutableStateOf(isSystemReducedMotionEnabled(context))
    }
    DisposableEffect(context) {
        val cr = context.contentResolver
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isReducedMotion = isSystemReducedMotionEnabled(context)
            }
        }
        cr.registerContentObserver(uri, false, observer)
        onDispose {
            cr.unregisterContentObserver(observer)
        }
    }
    return isReducedMotion
}

/**
 * Shared capsule pull-to-refresh indicator aligned with Pass 7 requirements:
 * - Single effective refresh phase drives label, alpha, position, accent, bar cycle, and retraction
 * - Active pull gesture overrides any lingering success/error retraction animation
 * - 32-36 dp visual capsule with three rounded signal bars (3 dp wide, 3 dp gap)
 * - Proportional reveal and rising bars while pulling
 * - "Release to refresh" with one light haptic feedback upon crossing threshold (re-arm ONLY at fraction <= 0.01f)
 * - Visible indeterminate state even if pullDistance == 0 dp (header / button refresh)
 * - "Updating…" with a 900 ms staggered breathing cycle while refreshing
 * - "Updated" with restrained emerald accent settling 1500 ms and retracting smoothly
 * - "Couldn't update" on network failure
 * - Bounded attempt-scoped settling and consume callbacks
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SentinelRefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    operation: RefreshOperation = RefreshOperation.Idle,
    status: RefreshStatus = RefreshStatus.Idle,
    onConsumeOutcome: (Long) -> Unit = {},
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val effectiveReducedMotion = reducedMotion || isSystemReducedMotionEnabled(context)

    val haptic = LocalHapticFeedback.current
    val fraction = state.distanceFraction

    var hasHaptickedForGesture by remember { mutableStateOf(false) }

    val effectiveIsRefreshing = isRefreshing || operation is RefreshOperation.Running

    // Haptic feedback once upon crossing threshold, re-arming only at full release (<= 0.01f)
    LaunchedEffect(fraction) {
        if (fraction >= 1.0f && !hasHaptickedForGesture && !effectiveIsRefreshing) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hasHaptickedForGesture = true
        } else if (fraction <= 0.01f) {
            hasHaptickedForGesture = false
        }
    }

    // Unified Phase: Drives label, alpha, accent, translation, and retraction together
    val phase = SentinelRefreshPresentationPolicy.resolvePhase(fraction, isRefreshing, operation, status)
    val label = SentinelRefreshPresentationPolicy.resolveLabel(fraction, isRefreshing, operation, status)

    // Subtle retract animation for settled states (1500 ms display window)
    val retractAlpha = remember { Animatable(1f) }
    val currentAttemptId = operation.attemptId
    val isSettledOutcome = phase == RefreshPhase.SUCCESS || phase == RefreshPhase.ERROR

    LaunchedEffect(phase, currentAttemptId) {
        if (isSettledOutcome) {
            retractAlpha.snapTo(1f)
            kotlinx.coroutines.delay(1500)
            retractAlpha.animateTo(0f, tween(if (effectiveReducedMotion) 0 else 240, easing = FastOutSlowInEasing))
            currentAttemptId?.let { id -> onConsumeOutcome(id) }
        } else {
            retractAlpha.snapTo(1f)
        }
    }

    // Header refresh button click: fraction is 0f but effectiveIsRefreshing is true -> must remain visible
    if (phase == RefreshPhase.IDLE && fraction <= 0.05f) {
        return
    }

    val accentColor = when (phase) {
        RefreshPhase.SUCCESS -> FigmaAccentGreen
        RefreshPhase.ERROR -> FigmaAccentCoral
        else -> FigmaAccentBlue
    }

    // Gentle breathing cycle animation for bars while refreshing (900ms staggered cycle)
    val infiniteTransition = rememberInfiniteTransition(label = "refreshCycle")
    val barPhase by if (phase == RefreshPhase.REFRESHING && !effectiveReducedMotion) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "barPhase"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    val translationY = when (phase) {
        RefreshPhase.REFRESHING, RefreshPhase.SUCCESS, RefreshPhase.ERROR -> 14.dp
        else -> (min(1.2f, fraction) * 28).dp
    }

    val currentAlpha = when (phase) {
        RefreshPhase.ARMED -> 1f
        RefreshPhase.PULLING -> min(1f, fraction * 1.5f)
        RefreshPhase.REFRESHING -> 1f
        RefreshPhase.SUCCESS, RefreshPhase.ERROR -> retractAlpha.value
        RefreshPhase.IDLE -> 0f
    }

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .graphicsLayer {
                this.translationY = translationY.toPx()
                this.alpha = currentAlpha
            }
            .clip(RoundedCornerShape(18.dp))
            .background(FigmaCardSurface)
            .border(1.dp, FigmaCardBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 7.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Three signal bars
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.height(14.dp)
            ) {
                val heights = listOf(6.dp, 10.dp, 14.dp)
                heights.forEachIndexed { index, maxBarHeight ->
                    val barHeight = when (phase) {
                        RefreshPhase.REFRESHING -> {
                            if (effectiveReducedMotion) {
                                maxBarHeight
                            } else {
                                val offset = (barPhase + index * 0.25f) % 1.0f
                                val scale = 0.4f + 0.6f * kotlin.math.sin(offset * Math.PI).toFloat().coerceIn(0f, 1f)
                                maxBarHeight * scale
                            }
                        }
                        RefreshPhase.SUCCESS, RefreshPhase.ERROR -> maxBarHeight
                        else -> {
                            val barThreshold = (index + 1) * 0.28f
                            val progress = ((fraction - (barThreshold - 0.25f)) / 0.25f).coerceIn(0.2f, 1.0f)
                            maxBarHeight * progress
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(accentColor)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = if (phase == RefreshPhase.SUCCESS || phase == RefreshPhase.ERROR) accentColor else FigmaTextPrimary
            )
        }
    }
}
