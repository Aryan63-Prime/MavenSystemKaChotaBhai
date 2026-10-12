package com.sentinel.admin.domain.repository

import java.util.UUID

/**
 * Controls audio monitoring for a selected device.
 *
 * Sends LISTEN/STOP control messages over WebSocket.
 * Audio data arrives as binary frames via [ConnectionRepository.events].
 */
interface AudioRepository {
    /** Sends LISTEN command for the given device. */
    fun listen(deviceId: String, streamId: UUID? = null): Boolean

    /** Sends STOP command for the given device. */
    fun stopListening(deviceId: String, streamId: UUID? = null): Boolean
}
