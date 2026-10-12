package com.sentinel.host.service

import com.sentinel.host.data.audio.AudioPipeline
import com.sentinel.host.data.repository.AudioRepositoryImpl
import com.sentinel.host.domain.audio.AudioRecorder
import com.sentinel.host.domain.audio.OpusEncoder
import com.sentinel.host.domain.model.ConnectionEvent
import com.sentinel.host.domain.model.ConnectionState
import com.sentinel.host.domain.repository.ConnectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioStreamerTest {

    private lateinit var streamer: AudioStreamer
    private lateinit var pipeline: AudioPipeline
    private lateinit var audioRepo: AudioRepositoryImpl

    @Before
    fun setUp() {
        val connection = object : ConnectionRepository {
            override val state = MutableStateFlow(ConnectionState.Disconnected)
            override val events: SharedFlow<ConnectionEvent> = MutableSharedFlow(extraBufferCapacity = 64)
            override suspend fun connect(serverUrl: String) {}
            override suspend fun disconnect() {}
            override fun sendText(message: String) = true
            override fun sendBinary(data: ByteArray) = true
        }

        pipeline = AudioPipeline(
            recorder = NoopRecorder(),
            encoder = NoopEncoder(),
            testDispatcher = Dispatchers.Unconfined
        )

        audioRepo = AudioRepositoryImpl(pipeline, connection)

        streamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
    }

    // ================================================================
    // Permission gating
    // ================================================================

    @Test
    fun `start without permission is no-op`() {
        streamer.hasPermission = false
        streamer.start()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `start with permission starts pipeline`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)
    }

    @Test
    fun `resume without paused session is no-op`() {
        streamer.hasPermission = true
        streamer.resume()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `resume after explicit stop is no-op`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.pause()
        assertFalse(pipeline.isRunning)
        assertTrue(streamer.isPaused)

        streamer.stop()
        assertFalse(streamer.isPaused)

        // Resume after explicit stop must not resurrect capture
        streamer.resume()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `resume without permission is no-op`() {
        streamer.hasPermission = true
        streamer.start()
        streamer.pause()

        streamer.hasPermission = false
        streamer.resume()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `resume with valid paused session restarts pipeline`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.pause()
        assertFalse(pipeline.isRunning)
        assertTrue(streamer.isPaused)

        streamer.resume()
        assertTrue(pipeline.isRunning)
        assertFalse(streamer.isPaused)
    }

    // ================================================================
    // Stop / Pause
    // ================================================================

    @Test
    fun `stop stops pipeline`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.stop()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `pause stops pipeline`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.pause()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `stop is safe when not started`() {
        streamer.stop() // Should not throw
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `pause is safe when not started`() {
        streamer.pause() // Should not throw
        assertFalse(pipeline.isRunning)
    }

    // ================================================================
    // Idempotency
    // ================================================================

    @Test
    fun `double start is idempotent`() {
        streamer.hasPermission = true
        streamer.start()
        streamer.start()
        assertTrue(pipeline.isRunning)
    }

    @Test
    fun `double stop is idempotent`() {
        streamer.hasPermission = true
        streamer.start()
        streamer.stop()
        streamer.stop()
        assertFalse(pipeline.isRunning)
    }

    // ================================================================
    // Lifecycle cycle
    // ================================================================

    @Test
    fun `start stop start cycle works`() {
        streamer.hasPermission = true

        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.stop()
        assertFalse(pipeline.isRunning)

        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.stop()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `start pause resume stop cycle works`() {
        streamer.hasPermission = true

        streamer.start()
        assertTrue(pipeline.isRunning)

        streamer.pause()
        assertFalse(pipeline.isRunning)

        streamer.resume()
        assertTrue(pipeline.isRunning)

        streamer.stop()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `audio focus transient loss pauses streaming and gain resumes it`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        // Simulate incoming call / transient focus loss
        streamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(streamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)

        // Call ends / audio focus gained back
        streamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(streamer.isInterruptedByCall)
        assertTrue(pipeline.isRunning)

        streamer.stop()
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `audio focus permanent loss stops streaming completely`() {
        streamer.hasPermission = true
        streamer.start()
        assertTrue(pipeline.isRunning)

        // Permanent loss
        streamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(streamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)
    }

    @Test
    fun `session timeout expires at original monotonic deadline without relying on advanceUntilIdle`() = runTest {
        val testScope = this
        val timedStreamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = testScope,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true
        timedStreamer.start(maxDurationSeconds = 1L)
        assertTrue(pipeline.isRunning)

        // At 999ms, deadline has not been reached yet
        testScheduler.advanceTimeBy(999L)
        testScheduler.runCurrent()
        assertTrue("Capture must still be running before deadline", pipeline.isRunning)
        assertFalse("Timeout callback must not be invoked before deadline", timedOut)

        // At exactly 1000ms, monotonic deadline is reached
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        assertFalse("Capture must be stopped at monotonic deadline", pipeline.isRunning)
        assertTrue("Timeout callback must be invoked at monotonic deadline", timedOut)
    }

    @Test(timeout = 10000)
    fun `already-fired watchdog waiting for lifecycle lock does not stop new session`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val watchdogDispatcher = executor.asCoroutineDispatcher()
        val asyncScope = CoroutineScope(SupervisorJob() + watchdogDispatcher)
        try {
            val timedStreamer = AudioStreamer(
                audioRepository = audioRepo,
                pipeline = pipeline,
                scope = asyncScope
            )
            timedStreamer.hasPermission = true

            val reachedLockLatch = CountDownLatch(1)
            val watchdogFinishedLatch = CountDownLatch(1)
            timedStreamer.onWatchdogAboutToAcquireLock = {
                reachedLockLatch.countDown()
            }
            timedStreamer.onWatchdogFinished = {
                watchdogFinishedLatch.countDown()
            }

            // Acquire lifecycleLock on test thread before watchdog completes its delay
            synchronized(timedStreamer.lifecycleLock) {
                // Session 1 started with 1s duration
                timedStreamer.start(maxDurationSeconds = 1L)
                assertTrue(pipeline.isRunning)

                // Wait until watchdog finishes delay(remainingMs) and invokes the hook right before synchronized(lifecycleLock).
                // At this point, the background worker thread is actively blocked trying to acquire lifecycleLock on a separate thread.
                val reachedBarrier = reachedLockLatch.await(5, TimeUnit.SECONDS)
                assertTrue("Watchdog must reach lifecycleLock barrier while held", reachedBarrier)

                // While watchdog is blocked waiting for lifecycleLock, stop Session 1 and start Session 2
                timedStreamer.stop()
                timedStreamer.start(maxDurationSeconds = 10L)
                assertTrue(pipeline.isRunning)
            }
            // lifecycleLock is released; background watchdog enters its synchronized block.
            // Because sessionGeneration changed, the stale watchdog must NOT stop Session 2!

            // Confirm the stale watchdog actually finished execution via completion barrier instead of Thread.sleep
            assertTrue("Stale watchdog must complete execution", watchdogFinishedLatch.await(5, TimeUnit.SECONDS))

            assertTrue("Session 2 must still be running after stale fired watchdog exits", pipeline.isRunning)
            timedStreamer.stop()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reconnection restart work consuming time does not extend original monotonic deadline`() = runTest {
        var startupDelayMs = 0L
        val delayingRecorder = object : AudioRecorder {
            override val isRecording: Boolean get() = true
            override fun start(): Boolean {
                if (startupDelayMs > 0L) {
                    testScheduler.advanceTimeBy(startupDelayMs)
                }
                return true
            }
            override fun stop() {}
            override fun read(buffer: ShortArray, offset: Int, size: Int) = -1
            override fun close() {}
        }

        val testPipeline = AudioPipeline(
            recorder = delayingRecorder,
            encoder = NoopEncoder(),
            testDispatcher = Dispatchers.Unconfined
        )
        val testAudioRepo = AudioRepositoryImpl(
            testPipeline,
            object : ConnectionRepository {
                override val state = MutableStateFlow(ConnectionState.Disconnected)
                override val events: SharedFlow<ConnectionEvent> = MutableSharedFlow(extraBufferCapacity = 64)
                override suspend fun connect(serverUrl: String) {}
                override suspend fun disconnect() {}
                override fun sendText(message: String) = true
                override fun sendBinary(data: ByteArray) = true
            }
        )

        val timedStreamer = AudioStreamer(
            audioRepository = testAudioRepo,
            pipeline = testPipeline,
            scope = this,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Start 2s session (original absolute deadline = 2000ms)
        startupDelayMs = 0L
        timedStreamer.start(maxDurationSeconds = 2L)
        assertTrue(testPipeline.isRunning)

        // Stream for 500ms
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        timedStreamer.pause()
        assertTrue(timedStreamer.isPaused)
        assertFalse(testPipeline.isRunning)

        // Advance 200ms while reconnecting (virtual time = 700ms, remaining = 1300ms)
        testScheduler.advanceTimeBy(200L)
        testScheduler.runCurrent()

        // Restart work consumes 400ms during resume (e.g. slow hardware init / HAL unblock)
        startupDelayMs = 400L
        timedStreamer.resume()
        assertFalse(timedStreamer.isPaused)
        assertTrue(testPipeline.isRunning)
        // Current virtual time is now 700 + 400 = 1100ms!
        // Recomputed watchdog delay from unchanged deadline must be 2000 - 1100 = 900ms.

        // Advance to 1999ms (899ms more, exactly 1ms before original 2000ms deadline)
        testScheduler.advanceTimeBy(899L)
        testScheduler.runCurrent()
        assertTrue("Capture must still be running 1ms before original deadline", testPipeline.isRunning)
        assertFalse("Watchdog must not have fired before original deadline", timedOut)

        // Advance 1ms to exactly 2000ms (original deadline)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        // Original deadline must be strictly enforced despite 400ms spent in restart work!
        assertFalse("Capture must be stopped at original deadline", testPipeline.isRunning)
        assertTrue("Session must time out at original deadline", timedOut)
    }

    @Test
    fun `audio focus recovery restart work consuming time does not extend original monotonic deadline`() = runTest {
        var startupDelayMs = 0L
        val delayingRecorder = object : AudioRecorder {
            override val isRecording: Boolean get() = true
            override fun start(): Boolean {
                if (startupDelayMs > 0L) {
                    testScheduler.advanceTimeBy(startupDelayMs)
                }
                return true
            }
            override fun stop() {}
            override fun read(buffer: ShortArray, offset: Int, size: Int) = -1
            override fun close() {}
        }

        val testPipeline = AudioPipeline(
            recorder = delayingRecorder,
            encoder = NoopEncoder(),
            testDispatcher = Dispatchers.Unconfined
        )
        val testAudioRepo = AudioRepositoryImpl(
            testPipeline,
            object : ConnectionRepository {
                override val state = MutableStateFlow(ConnectionState.Disconnected)
                override val events: SharedFlow<ConnectionEvent> = MutableSharedFlow(extraBufferCapacity = 64)
                override suspend fun connect(serverUrl: String) {}
                override suspend fun disconnect() {}
                override fun sendText(message: String) = true
                override fun sendBinary(data: ByteArray) = true
            }
        )

        val timedStreamer = AudioStreamer(
            audioRepository = testAudioRepo,
            pipeline = testPipeline,
            scope = this,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Start 2s session (original absolute deadline = 2000ms)
        startupDelayMs = 0L
        timedStreamer.start(maxDurationSeconds = 2L)
        assertTrue(testPipeline.isRunning)

        // Focus loss at 500ms
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(timedStreamer.isInterruptedByCall)
        assertFalse(testPipeline.isRunning)

        // Advance 300ms while call is ongoing (virtual time = 800ms)
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()

        // Focus regained, but hardware re-init takes 350ms
        startupDelayMs = 350L
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(timedStreamer.isInterruptedByCall)
        assertTrue(testPipeline.isRunning)
        // Current virtual time is now 800 + 350 = 1150ms!
        // Recomputed watchdog delay must be 2000 - 1150 = 850ms.

        // Advance to 1999ms (849ms more, 1ms before original deadline)
        testScheduler.advanceTimeBy(849L)
        testScheduler.runCurrent()
        assertTrue("Capture must still be running 1ms before original deadline", testPipeline.isRunning)
        assertFalse("Watchdog must not have fired before original deadline", timedOut)

        // Advance 1ms to 2000ms (original deadline)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        // Original deadline must be strictly enforced!
        assertFalse("Capture must be stopped at original deadline", testPipeline.isRunning)
        assertTrue("Session must time out at original deadline", timedOut)
    }

    @Test
    fun `audio focus gain after session deadline expired does not restart capture`() = runTest {
        val testScope = this
        val timedStreamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = testScope,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Start with 1s duration (deadline = 1000ms)
        timedStreamer.start(maxDurationSeconds = 1L)
        assertTrue(pipeline.isRunning)

        // Transient focus loss pauses capture at 400ms
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(timedStreamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)

        // Advance 599ms (total 999ms, deadline 1000ms not yet reached)
        testScheduler.advanceTimeBy(599L)
        testScheduler.runCurrent()
        assertTrue(timedStreamer.isInterruptedByCall)
        assertFalse(timedOut)

        // Advance 1ms (total 1000ms, exact deadline reached)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        // Watchdog must fire, stop session, and invoke timeout callback
        assertTrue("Watchdog must timeout at original monotonic deadline", timedOut)
        assertFalse(timedStreamer.isInterruptedByCall)

        // Call ends, audio focus regained
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)

        // Must NOT restart capture past deadline!
        assertFalse(pipeline.isRunning)
        assertFalse(timedStreamer.isInterruptedByCall)
    }

    @Test
    fun `audio focus gain before deadline restores capture and watchdog`() = runTest {
        val testScope = this
        val timedStreamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = testScope,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Start with 2s duration (deadline = 2000ms)
        timedStreamer.start(maxDurationSeconds = 2L)
        assertTrue(pipeline.isRunning)

        // Focus loss at 500ms
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(timedStreamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)

        // Focus regained at 1000ms (1000ms remaining on original deadline)
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(timedStreamer.isInterruptedByCall)
        assertTrue(pipeline.isRunning)

        // Advance to 1999ms (999ms more, 1ms before original deadline)
        testScheduler.advanceTimeBy(999L)
        testScheduler.runCurrent()
        assertTrue(pipeline.isRunning)
        assertFalse(timedOut)

        // Advance 1ms to exactly 2000ms
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        // Restored watchdog must fire and stop capture!
        assertFalse(pipeline.isRunning)
        assertTrue(timedOut)
    }

    @Test
    fun `reconnect watchdog auto-stops session if deadline expires while interrupted by audio focus loss`() = runTest {
        val testScope = this
        val timedStreamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = testScope,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Start with 2s duration (deadline = 2000ms)
        timedStreamer.start(maxDurationSeconds = 2L)
        assertTrue(pipeline.isRunning)

        // Pause for reconnect at 500ms
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        timedStreamer.pause()
        assertTrue(timedStreamer.isPaused)
        assertFalse(pipeline.isRunning)

        // Advance 200ms while reconnecting (virtual time = 700ms)
        testScheduler.advanceTimeBy(200L)
        testScheduler.runCurrent()

        // Resume after reconnect (deadline is still 2000ms, remaining = 1300ms)
        timedStreamer.resume()
        assertFalse(timedStreamer.isPaused)
        assertTrue(pipeline.isRunning)

        // While resumed, a transient audio focus loss occurs at 900ms (remaining = 1100ms)
        testScheduler.advanceTimeBy(200L)
        testScheduler.runCurrent()
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(timedStreamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)

        // Advance 1099ms (virtual time 1999ms - 1ms before 2000ms deadline)
        testScheduler.advanceTimeBy(1099L)
        testScheduler.runCurrent()
        assertTrue("Interruption must persist before deadline", timedStreamer.isInterruptedByCall)
        assertFalse("Timeout must not occur before deadline", timedOut)

        // Advance 1ms (virtual time 2000ms - exact monotonic deadline reached!)
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        // The reconnect watchdog must have fired despite focus loss interruption!
        assertTrue("Reconnect watchdog must timeout when deadline expires during interruption", timedOut)
        assertFalse("Interruption flag must be cleared on expiration", timedStreamer.isInterruptedByCall)
        assertFalse(pipeline.isRunning)

        // Subsequent focus gain must NOT revive the capture
        timedStreamer.audioFocusChangeListener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("Focus gain after reconnect timeout must not restart capture", pipeline.isRunning)
    }

    @Test
    fun `start with zero duration enforces bounded watchdog`() = runTest {
        val testScope = this
        val timedStreamer = AudioStreamer(
            audioRepository = audioRepo,
            pipeline = pipeline,
            scope = testScope,
            clock = { testScheduler.currentTime }
        )
        var timedOut = false
        timedStreamer.onSessionTimeout = { timedOut = true }
        timedStreamer.hasPermission = true

        // Passing 0L must not disable watchdog
        timedStreamer.start(maxDurationSeconds = 0L)
        assertTrue(pipeline.isRunning)

        // Default duration is 300s = 300_000ms. Advance to 299_999ms.
        testScheduler.advanceTimeBy(299_999L)
        testScheduler.runCurrent()
        assertTrue(pipeline.isRunning)
        assertFalse(timedOut)

        // Advance 1ms to 300_000ms
        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()

        assertFalse(pipeline.isRunning)
        assertTrue(timedOut)
    }

    @Test
    fun `lifecycle callbacks are invoked on start and stop`() {
        var started = false
        var stopped = false
        streamer.onSessionStarted = { started = true }
        streamer.onSessionStopped = { stopped = true }
        streamer.hasPermission = true

        streamer.start()
        assertTrue(started)

        streamer.stop()
        assertTrue(stopped)
    }

    @Test
    fun `start assigns stream UUID and passes it to activeStreamId`() {
        val testUuid = java.util.UUID.randomUUID()
        streamer.hasPermission = true
        streamer.start(streamId = testUuid)
        org.junit.Assert.assertEquals(testUuid, streamer.activeStreamId)
        assertTrue(streamer.isStreaming)

        // Stopping with mismatched UUID should be ignored
        val foreignUuid = java.util.UUID.randomUUID()
        streamer.stop(foreignUuid)
        assertTrue(streamer.isStreaming)
        org.junit.Assert.assertEquals(testUuid, streamer.activeStreamId)

        // Stopping with matching UUID terminates the session
        streamer.stop(testUuid)
        assertFalse(streamer.isStreaming)
        org.junit.Assert.assertNull(streamer.activeStreamId)
    }
}

// ================================================================
// Test doubles
// ================================================================

private class NoopRecorder : AudioRecorder {
    override val isRecording = false
    override fun start() = true
    override fun stop() {}
    override fun read(buffer: ShortArray, offset: Int, size: Int) = -1
    override fun close() {}
}

private class NoopEncoder : OpusEncoder {
    override fun encode(pcm: ShortArray, frameSize: Int, output: ByteArray, maxOutput: Int) = -1
    override fun close() {}
}
