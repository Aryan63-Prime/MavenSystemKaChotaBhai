package com.sentinel.admin.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentinel.admin.ui.theme.FigmaCardBorder
import com.sentinel.admin.ui.theme.FigmaCardSurface
import com.sentinel.admin.ui.theme.FigmaTextMuted
import com.sentinel.admin.ui.theme.FigmaTextPrimary

/**
 * Network and motion summary card aligned with Figma concept:
 * "Concept / 02 / Telemetry · expanded".
 *
 * Implements Work Orders 3, 4 & 8:
 * - Truthful readings: nullable networkType, signalDbm, and accelerationG
 * - Removed hardcoded production -87 dBm and 0.98 G defaults
 * - Disconnected retained readings are visibly labelled "Stale"
 * - Truthful footer reflecting that historical sensor logging is absent from backend
 * - 48dp touch target with accessibility semantics
 */
@Composable
fun NetworkMotionCard(
    networkType: String? = null,
    signalDbm: Int? = null,
    accelerationG: Float? = null,
    isOnline: Boolean = true,
    onInspectHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val networkLabel = when {
        networkType.isNullOrBlank() -> "Network: Unavailable"
        !isOnline -> "$networkType · Stale"
        else -> networkType
    }

    val signalLabel = if (signalDbm != null) "$signalDbm dBm" else "Signal: Unavailable"

    val motionLabel = if (accelerationG != null) {
        String.format(java.util.Locale.US, "%.2f G", accelerationG)
    } else {
        "Motion: Unavailable"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { onInspectHistory() }
            .semantics {
                role = Role.Button
                contentDescription = "Network and motion summary. $networkLabel, $signalLabel, $motionLabel. Tap for sensor status."
            },
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
            Text(
                text = "Network & motion",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = FigmaTextPrimary
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Primary metric row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (signalDbm != null) "$networkLabel · $signalLabel" else networkLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = FigmaTextPrimary
                )

                Text(
                    text = motionLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = FigmaTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer prompt
            Text(
                text = "Sensor history unavailable · Host streams real-time state only",
                fontSize = 12.sp,
                color = FigmaTextMuted
            )
        }
    }
}
