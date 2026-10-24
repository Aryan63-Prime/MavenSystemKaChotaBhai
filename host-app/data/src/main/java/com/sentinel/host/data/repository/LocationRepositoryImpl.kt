package com.sentinel.host.data.repository

import com.sentinel.host.data.remote.SequenceGenerator
import com.sentinel.host.data.remote.protocol.MessageSerializer
import com.sentinel.host.domain.model.LocationUpdate
import com.sentinel.host.domain.repository.ConnectionRepository
import com.sentinel.host.domain.repository.LocationRepository

/**
 * Sends serialized LOCATION messages over the WebSocket.
 * Fire-and-forget — no ACK expected per PROTOCOL.md.
 */
class LocationRepositoryImpl(
    private val connectionRepository: ConnectionRepository,
    private val messageSerializer: MessageSerializer,
    private val sequenceGenerator: SequenceGenerator,
    private val offlineBuffer: com.sentinel.host.data.location.OfflineTelemetryBuffer? = null,
    private val deviceRepository: com.sentinel.host.domain.repository.DeviceRepository? = null
) : LocationRepository {

    override suspend fun sendLocation(location: LocationUpdate) {
        val message = messageSerializer.serializeLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            battery = location.battery,
            network = location.network,
            sequence = sequenceGenerator.next()
        )
        val sent = connectionRepository.sendText(message)
        if (!sent) {
            offlineBuffer?.bufferLocation(location)
        }
    }
}
