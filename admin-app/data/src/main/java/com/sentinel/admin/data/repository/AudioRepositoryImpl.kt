package com.sentinel.admin.data.repository

import com.sentinel.admin.data.remote.SequenceGenerator
import com.sentinel.admin.data.remote.protocol.MessageSerializer
import com.sentinel.admin.domain.repository.AudioRepository
import com.sentinel.admin.domain.repository.ConnectionRepository
import javax.inject.Inject

/**
 * Sends LISTEN and STOP control messages via WebSocket.
 *
 * This repository is COMMANDS ONLY — no playback logic.
 * Playback belongs exclusively to AudioMonitor.
 */
class AudioRepositoryImpl @Inject constructor(
    private val connectionRepository: ConnectionRepository,
    private val messageSerializer: MessageSerializer,
    private val sequenceGenerator: SequenceGenerator
) : AudioRepository {

    override fun listen(deviceId: String, streamId: java.util.UUID?): Boolean {
        val sequence = sequenceGenerator.next()
        val message = messageSerializer.serializeListen(deviceId, sequence, streamId?.toString(), audioVersion = 2)
        return connectionRepository.sendText(message)
    }

    override fun stopListening(deviceId: String, streamId: java.util.UUID?): Boolean {
        val sequence = sequenceGenerator.next()
        val message = messageSerializer.serializeStop(deviceId, sequence, streamId?.toString())
        return connectionRepository.sendText(message)
    }
}
