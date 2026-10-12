package com.sentinel.admin.data.remote.protocol

import android.util.Log
import com.sentinel.admin.domain.model.DeviceUpdateEvent

/**
 * Maps [DeviceUpdateDataJson] to domain [DeviceUpdateEvent].
 *
 * Pure function — no side effects beyond logging unknown events.
 * Returns null for unknown or malformed events.
 */
class DeviceUpdateEventMapper {

    companion object {
        private const val TAG = "Sentinel:EventMapper"

        // Event name constants matching server protocol
        private const val EVENT_CONNECTED = "connected"
        private const val EVENT_DISCONNECTED = "disconnected"
        private const val EVENT_HEARTBEAT = "heartbeat"
        private const val EVENT_LOCATION = "location"
        private const val EVENT_BATTERY = "battery"
        private const val EVENT_NETWORK = "network"
        private const val EVENT_METADATA = "metadata"
        private const val EVENT_EMERGENCY_SOS = "emergency_sos"
    }

    /**
     * Maps a raw JSON data payload to a domain event.
     *
     * @return The mapped event, or null if the event type is unknown or the payload is invalid.
     */
    fun map(data: DeviceUpdateDataJson): DeviceUpdateEvent? {
        if (data.deviceId.isBlank()) {
            Log.w(TAG, "DEVICE_UPDATE missing deviceId, ignoring")
            return null
        }

        return when (data.event) {
            EVENT_CONNECTED -> DeviceUpdateEvent.DeviceConnected(
                deviceId = data.deviceId,
                deviceName = data.deviceName,
                appVersion = data.appVersion,
                model = data.model
            )

            EVENT_DISCONNECTED -> DeviceUpdateEvent.DeviceDisconnected(
                deviceId = data.deviceId,
                model = data.model
            )

            EVENT_HEARTBEAT -> DeviceUpdateEvent.HeartbeatReceived(
                deviceId = data.deviceId,
                timestamp = data.timestamp,
                model = data.model
            )

            EVENT_LOCATION -> {
                val lat = data.latitude ?: return null
                val lng = data.longitude ?: return null
                DeviceUpdateEvent.LocationUpdated(
                    deviceId = data.deviceId,
                    latitude = lat,
                    longitude = lng,
                    accuracy = data.accuracy,
                    battery = data.battery,
                    network = data.network,
                    model = data.model,
                    timestamp = data.timestamp
                )
            }

            EVENT_BATTERY -> {
                val battery = data.battery ?: return null
                DeviceUpdateEvent.BatteryUpdated(
                    deviceId = data.deviceId,
                    battery = battery,
                    model = data.model
                )
            }

            EVENT_NETWORK -> {
                val network = data.network ?: return null
                DeviceUpdateEvent.NetworkUpdated(
                    deviceId = data.deviceId,
                    network = network,
                    model = data.model
                )
            }

            EVENT_METADATA -> DeviceUpdateEvent.MetadataUpdated(
                deviceId = data.deviceId,
                deviceName = data.deviceName,
                appVersion = data.appVersion,
                model = data.model
            )

            EVENT_EMERGENCY_SOS -> DeviceUpdateEvent.EmergencySos(
                deviceId = data.deviceId,
                triggerReason = data.triggerReason ?: "FALL_DETECTED",
                impactGForce = data.impactGForce ?: 0.0,
                latitude = data.latitude,
                longitude = data.longitude,
                accuracy = data.accuracy,
                battery = data.battery,
                model = data.model,
                timestamp = data.timestamp
            )

            else -> {
                Log.w(TAG, "Unknown DEVICE_UPDATE event: ${data.event}")
                null
            }
        }
    }
}
