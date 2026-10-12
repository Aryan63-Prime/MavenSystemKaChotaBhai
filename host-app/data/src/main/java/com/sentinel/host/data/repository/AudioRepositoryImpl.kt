package com.sentinel.host.data.repository

import android.util.Log
import com.sentinel.host.data.audio.AudioFrameBuilder
import com.sentinel.host.data.audio.AudioPipeline
import com.sentinel.host.domain.model.AudioFrame
import com.sentinel.host.domain.repository.AudioRepository
import com.sentinel.host.domain.repository.ConnectionRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Sends encoded audio frames as binary WebSocket packets.
 *
 * Responsibilities:
 * - Delegates capture/encoding to [AudioPipeline]
 * - Serializes [AudioFrame]s into binary packets via [AudioFrameBuilder]
 * - Sends binary packets via [ConnectionRepository.sendBinary]
 * - Logs send failures without retrying (fire-and-forget per PROTOCOL.md)
 *
 * No Base64. No JSON. Binary frames only.
 *
 * Thread safety:
 * - [AudioFrameBuilder] is not thread-safe but is only used from the
 *   audio pipeline's single thread via the frames Flow collector.
 */
open class AudioRepositoryImpl(
    private val pipeline: AudioPipeline,
    private val connectionRepository: ConnectionRepository,
    private val frameBuilder: AudioFrameBuilder = AudioFrameBuilder()
) : AudioRepository {

    companion object {
        private const val TAG = "Sentinel:AudioRepo"
    }

    @Volatile
    var isMuted: Boolean = false

    override fun startCapture(streamId: UUID?): Flow<AudioFrame> {
        pipeline.start(streamId)
        Log.i(TAG, "Audio capture started (streamId=$streamId)")
        return pipeline.frames
    }

    override fun stopCapture() {
        pipeline.stop()
        Log.i(TAG, "Audio capture stopped")
    }

    /**
     * Serializes and sends a single audio frame as a binary WebSocket message.
     * Fire-and-forget — no ACK expected per PROTOCOL.md.
     *
     * @param frame The encoded audio frame to send.
     * @return true if the frame was sent successfully.
     */
    fun sendFrame(frame: AudioFrame): Boolean {
        if (isMuted) {
            // Muted while PTT audio is actively playing on speaker to prevent feedback loop
            return true
        }
        val packet = frameBuilder.build(frame)
        if (packet == null) {
            Log.w(TAG, "Failed to build packet for frame seq=${frame.sequence}")
            return false
        }

        val sent = connectionRepository.sendBinary(packet)
        if (!sent) {
            Log.w(TAG, "sendBinary failed for frame seq=${frame.sequence}")
        }
        return sent
    }
}
