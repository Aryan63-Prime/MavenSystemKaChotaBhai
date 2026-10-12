package com.sentinel.host.domain.repository

import com.sentinel.host.domain.model.AudioFrame
import kotlinx.coroutines.flow.Flow

import java.util.UUID

/**
 * Manages audio capture, Opus encoding, and binary frame streaming.
 */
interface AudioRepository {
    fun startCapture(streamId: UUID? = null): Flow<AudioFrame>
    fun stopCapture()
}
