package com.sentinel.admin.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.ui.theme.FigmaAccentBlue
import com.sentinel.admin.ui.theme.FigmaAccentCoral
import com.sentinel.admin.ui.theme.FigmaAccentGreen
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

/**
 * Battery & Power collapsible accordion card aligned with Figma concept:
 * "Concept / 02 / Telemetry · expanded" & "Concept / 07 / Telemetry · collapsed".
 *
 * Implements Work Orders 3, 6 & 8:
 * - Truthful readings: batteryLevel is nullable (renders "Unavailable" when null, preserves genuine 0%)
 * - Disconnected retained readings are visibly labelled "Stale"
 * - Removed hardcoded production -2.1% / hour and fake curves
 * - Hoisted isExpanded state with 240ms duration motion contract
 * - 48dp minimum touch target with accessibility semantics
 */
@Composable
fun BatteryAccordionCard(
    batteryLevel: Int?,
    isCharging: Boolean = false,
    isOnline: Boolean = true,
    isExpanded: Boolean = true,
    batteryHistory: List<Int> = emptyList(),
    onExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
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
            val batteryLabel = BatteryPresentationPolicy.formatBattery(batteryLevel, isOnline, isCharging)

            val symbol = if (isExpanded) "−" else "+"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onExpandedChange(!isExpanded) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Battery and power accordion"
                        stateDescription = if (isExpanded) "Expanded, $batteryLabel" else "Collapsed, $batteryLabel"
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Battery & power",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = FigmaTextPrimary
                )

                Text(
                    text = "$batteryLabel  $symbol",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        batteryLevel == null -> FigmaTextMuted
                        batteryLevel <= 15 -> FigmaAccentCoral
                        else -> FigmaAccentBlue
                    }
                )
            }

            // --- 2. Expandable Body (240ms motion contract) ---
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(180)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 240, easing = FastOutLinearInEasing)
                ) + fadeOut(animationSpec = tween(180))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(14.dp))

                    // Truthful status details
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCharging) "Charging active" else if (batteryLevel != null) "On battery" else "Status unknown",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCharging) FigmaAccentGreen else FigmaTextPrimary
                        )

                        Text(
                            text = if (isOnline) "Latest reported" else "Retained (offline)",
                            fontSize = 13.sp,
                            color = FigmaTextMuted
                        )
                    }

                    if (batteryHistory.size >= 2) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "BATTERY DISCHARGE SPARKLINE (${batteryHistory.size} SAMPLES)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = FigmaAccentBlue,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Y-axis percentage labels on the left
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "100%",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B),
                                    lineHeight = 8.sp
                                )
                                Text(
                                    text = "50%",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B),
                                    lineHeight = 8.sp
                                )
                                Text(
                                    text = "0%",
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF64748B),
                                    lineHeight = 8.sp
                                )
                            }

                            // Sparkline Canvas with horizontal dotted/dashed gridlines
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                val w = size.width
                                val h = size.height

                                val yTop = 4.dp.toPx()
                                val yMid = h / 2f
                                val yBottom = h - 4.dp.toPx()
                                val usableH = yBottom - yTop

                                val gridColor = Color(0xFF1E293B)
                                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

                                // Reference gridlines at 100%, 50%, 0%
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, yTop),
                                    end = Offset(w, yTop),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = dashEffect
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, yMid),
                                    end = Offset(w, yMid),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = dashEffect
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, yBottom),
                                    end = Offset(w, yBottom),
                                    strokeWidth = 1.dp.toPx(),
                                    pathEffect = dashEffect
                                )

                                val range = 100f
                                val stepX = w / (batteryHistory.size - 1).coerceAtLeast(1)
                                val path = androidx.compose.ui.graphics.Path()
                                val fillPath = androidx.compose.ui.graphics.Path()

                                batteryHistory.forEachIndexed { i, pt ->
                                    val x = i * stepX
                                    val pct = pt.coerceIn(0, 100).toFloat()
                                    val y = yBottom - (pct / range) * usableH
                                    if (i == 0) {
                                        path.moveTo(x, y)
                                        fillPath.moveTo(x, yBottom)
                                        fillPath.lineTo(x, y)
                                    } else {
                                        path.lineTo(x, y)
                                        fillPath.lineTo(x, y)
                                    }
                                }
                                fillPath.lineTo(w, yBottom)
                                fillPath.close()

                                drawPath(
                                    path = fillPath,
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(
                                            androidx.compose.ui.graphics.Color(0xFF54C6A2).copy(alpha = 0.35f),
                                            androidx.compose.ui.graphics.Color(0xFF54C6A2).copy(alpha = 0.0f)
                                        ),
                                        startY = yTop,
                                        endY = yBottom
                                    )
                                )

                                drawPath(
                                    path = path,
                                    color = androidx.compose.ui.graphics.Color(0xFF54C6A2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 2.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                        join = androidx.compose.ui.graphics.StrokeJoin.Round
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
