package com.sentinel.admin.ui.detail

import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.PlaybackState

object LiveAudioPresentationPolicy {

    /**
     * Paused playback or active recording is an existing session, not idle.
     * Stop and local cancellation must remain available for paused/offline/connecting/buffering sessions.
     */
    fun isSessionActive(
        playbackState: PlaybackState,
        isRecording: Boolean
    ): Boolean {
        val isPlaying = playbackState is PlaybackState.Playing
        val isConnecting = playbackState is PlaybackState.Connecting
        val isBuffering = playbackState is PlaybackState.Buffering
        val isPaused = playbackState is PlaybackState.Paused
        return isPlaying || isConnecting || isBuffering || isPaused || isRecording
    }

    fun isStopEligible(
        playbackState: PlaybackState,
        isRecording: Boolean
    ): Boolean = isSessionActive(playbackState, isRecording)

    fun isListenEligible(
        playbackState: PlaybackState,
        isRecording: Boolean,
        isOnline: Boolean
    ): Boolean = !isSessionActive(playbackState, isRecording) && isOnline

    fun resolvePttLabel(
        isPttArmed: Boolean,
        hasMicPermission: Boolean,
        isPressPending: Boolean,
        isPttTransmitting: Boolean
    ): String = when {
        !isPttArmed -> "PTT Locked"
        !hasMicPermission -> "Mic Required"
        isPttTransmitting -> "Transmitting…"
        isPressPending -> "Connecting…"
        else -> "Hold to talk"
    }

    /**
     * Authoritative session duration in seconds derived from decoded Opus frame count (20ms per frame).
     */
    fun calculateAuthoritativeSeconds(audioStats: AudioStatistics): Long =
        (audioStats.framesDecoded * 20L) / 1000L
}

/**
 * Production PTT activation token and latch controller.
 *
 * Implements Work Order 5:
 * - Owns local activation token consumed before calling Stop callback
 * - Ensures exactly one Stop invocation per owned activation
 * - Protects against double-stop races on disposal, cancellation, or recomposition
 */
class PttActivationOwner {
    private var next = 0L
    private var active: Long? = null

    fun begin(): Long? {
        if (active != null) return null
        return (++next).also { active = it }
    }

    fun consume(id: Long): Boolean {
        if (active != id) return false
        active = null
        return true
    }

    fun current(): Long? = active

    fun cancelCurrent(): Boolean {
        if (active == null) return false
        active = null
        return true
    }
}
