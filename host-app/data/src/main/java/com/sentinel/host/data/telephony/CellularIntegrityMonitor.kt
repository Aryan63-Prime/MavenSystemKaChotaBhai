package com.sentinel.host.data.telephony

import android.content.Context
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import kotlin.math.pow

data class CellularIntegrityReport(
    val networkType: String,
    val cellId: Long,
    val physicalCellId: Int,
    val trackingAreaCode: Int,
    val rsrpDbm: Int,
    val rsrqDb: Int,
    val sinrDb: Int,
    val estimatedDistanceMeters: Double,
    val isAbnormalProximitySuspected: Boolean,
    val diagnosticSummary: String
)

class CellularIntegrityMonitor(private val context: Context) {

    fun evaluateServingCell(): CellularIntegrityReport {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return emptyReport("NONE", "Telephony unavailable")

        val cells: List<CellInfo> = try {
            tm.allCellInfo ?: emptyList()
        } catch (_: SecurityException) {
            return emptyReport("DENIED", "Permission denied")
        } catch (_: Exception) {
            return emptyReport("ERROR", "Cell info query error")
        }

        val registered = cells.firstOrNull { it.isRegistered }
            ?: return emptyReport("NO_SERVICE", "No registered cell located")

        // 1. 5G New Radio (Standalone / Non-Standalone)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && registered is CellInfoNr) {
            val id = registered.cellIdentity as? CellIdentityNr
            val ss = registered.cellSignalStrength as? CellSignalStrengthNr
            val nci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) id?.nci ?: -1L else -1L
            val pci = id?.pci ?: -1
            val tac = id?.tac ?: -1
            val rsrp = ss?.ssRsrp?.takeIf { it in -140..-25 } ?: -95
            val rsrq = ss?.ssRsrq?.takeIf { it in -40..20 } ?: -10
            val sinr = ss?.ssSinr?.takeIf { it in -25..40 } ?: 12

            val estDistM = 10.0.pow((-35.0 - rsrp) / 28.0).coerceIn(5.0, 5000.0)
            val isCloseAccessBts = rsrp > -50 && estDistM < 18.0

            return CellularIntegrityReport(
                networkType = "5G-NR",
                cellId = nci,
                physicalCellId = pci,
                trackingAreaCode = tac,
                rsrpDbm = rsrp,
                rsrqDb = rsrq,
                sinrDb = sinr,
                estimatedDistanceMeters = estDistM,
                isAbnormalProximitySuspected = isCloseAccessBts,
                diagnosticSummary = "5G-NR PCI:$pci NCI:$nci TAC:$tac RSRP:${rsrp}dBm RSRQ:${rsrq}dB SINR:${sinr}dB (~${estDistM.toInt()}m)"
            )
        }

        // 2. 4G LTE
        if (registered is CellInfoLte) {
            val id = registered.cellIdentity
            val ss = registered.cellSignalStrength
            val rsrp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ss.rsrp.takeIf { it in -140..-25 } ?: -95
            } else -95
            val rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ss.rsrq.takeIf { it in -40..20 } ?: -10
            } else -10
            val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ss.rssnr.takeIf { it in -25..40 } ?: 10
            } else 10
            val ta = ss.timingAdvance.takeIf { it in 0..1282 }

            val distM = if (ta != null) ta * 78.12 else 10.0.pow((-35.0 - rsrp) / 28.0).coerceIn(5.0, 5000.0)
            val isCloseAccessBts = (rsrp in -50..-25) && (ta == null || ta == 0)

            return CellularIntegrityReport(
                networkType = "LTE",
                cellId = id.ci.toLong(),
                physicalCellId = id.pci,
                trackingAreaCode = id.tac,
                rsrpDbm = rsrp,
                rsrqDb = rsrq,
                sinrDb = sinr,
                estimatedDistanceMeters = distM,
                isAbnormalProximitySuspected = isCloseAccessBts,
                diagnosticSummary = "LTE CID:${id.ci} PCI:${id.pci} TAC:${id.tac} RSRP:${rsrp}dBm TA:${ta ?: "N/A"} (~${distM.toInt()}m)"
            )
        }

        return emptyReport("LEGACY_2G_3G", "Legacy GSM/WCDMA Cell (Potential Downgrade)")
    }

    private fun emptyReport(type: String, msg: String) = CellularIntegrityReport(
        networkType = type, cellId = -1L, physicalCellId = -1, trackingAreaCode = -1,
        rsrpDbm = -120, rsrqDb = -20, sinrDb = 0, estimatedDistanceMeters = 0.0,
        isAbnormalProximitySuspected = type == "LEGACY_2G_3G", diagnosticSummary = msg
    )
}
