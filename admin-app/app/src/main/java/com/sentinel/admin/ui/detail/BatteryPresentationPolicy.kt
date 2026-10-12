package com.sentinel.admin.ui.detail

object BatteryPresentationPolicy {

    /**
     * Formats battery levels preserving genuine 0%, flagging stale offline readings,
     * and indicating charging state.
     */
    fun formatBattery(level: Int?, isOnline: Boolean, isCharging: Boolean): String {
        return when {
            level == null -> "Unavailable"
            level in 0..100 -> {
                val chargingIndicator = if (isCharging) " ⚡" else ""
                val staleIndicator = if (!isOnline) " · Stale" else ""
                "$level%$chargingIndicator$staleIndicator"
            }
            else -> "Unavailable"
        }
    }

    /**
     * Formats network types truthfully, handling null/empty as Unavailable and marking offline as stale.
     */
    fun formatNetwork(type: String?, isOnline: Boolean): String {
        return when {
            type.isNullOrBlank() -> "Network: Unavailable"
            !isOnline -> "$type · Stale"
            else -> type
        }
    }
}
