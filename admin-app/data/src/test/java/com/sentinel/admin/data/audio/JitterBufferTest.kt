package com.sentinel.admin.data.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [JitterBuffer].
 *
 * Verifies ordering, duplicate rejection, late dropping, wrap-around,
 * adaptive depth, PLC gap detection, and statistics.
 */
class JitterBufferTest {

    private lateinit var buffer: JitterBuffer

    @Before
    fun setUp() {
        buffer = JitterBuffer(minDepth = 2, maxDepth = 5, maxLateDistance = 10)
    }

    // ============================================================
    // Basic operations
    // ============================================================

    @Test
    fun `push and pop in order`() {
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(1, byteArrayOf(0x02))

        val r1 = buffer.pop()
        assertTrue(r1 is JitterBuffer.PopResult.Frame)
        assertEquals(0L, (r1 as JitterBuffer.PopResult.Frame).sequence)

        val r2 = buffer.pop()
        assertTrue(r2 is JitterBuffer.PopResult.Frame)
        assertEquals(1L, (r2 as JitterBuffer.PopResult.Frame).sequence)

        val r3 = buffer.pop()
        assertTrue(r3 is JitterBuffer.PopResult.Empty)
    }

    @Test
    fun `out of order packets reordered by TreeMap`() {
        buffer.push(2, byteArrayOf(0x03))
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(1, byteArrayOf(0x02))

        // Pop should deliver 0, 1, 2 in order
        val r1 = buffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(0L, r1.sequence)

        val r2 = buffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(1L, r2.sequence)

        val r3 = buffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(2L, r3.sequence)
    }

    @Test
    fun `isReady after reaching target depth`() {
        assertFalse(buffer.isReady())
        buffer.push(0, byteArrayOf(0x01))
        assertFalse(buffer.isReady()) // 1 < minDepth(2)
        buffer.push(1, byteArrayOf(0x02))
        assertTrue(buffer.isReady()) // 2 >= minDepth(2)
    }

    // ============================================================
    // Duplicate rejection
    // ============================================================

    @Test
    fun `duplicate sequence rejected`() {
        val r1 = buffer.push(5, byteArrayOf(0x01))
        assertEquals(JitterBuffer.PushResult.ACCEPTED, r1)

        val r2 = buffer.push(5, byteArrayOf(0x02))
        assertEquals(JitterBuffer.PushResult.DUPLICATE, r2)

        assertEquals(1, buffer.droppedDuplicate)
        assertEquals(1, buffer.size())
    }

    // ============================================================
    // Late packet dropping
    // ============================================================

    @Test
    fun `late packets dropped`() {
        // Push and consume frames 0-15
        for (i in 0L..15L) {
            buffer.push(i, byteArrayOf(i.toByte()))
        }
        // Pop all to advance playback head
        repeat(16) { buffer.pop() }

        // Now playback head is at 16. Push sequence 2 (14 frames behind)
        val result = buffer.push(2, byteArrayOf(0x00))
        assertEquals(JitterBuffer.PushResult.LATE, result)
        assertEquals(1L, buffer.droppedLate)
    }

    // ============================================================
    // PLC (missing packet detection)
    // ============================================================

    @Test
    fun `missing packet returns Missing result for PLC`() {
        // Push 0 and 2, skip 1
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(2, byteArrayOf(0x03))

        // Pop 0
        val r1 = buffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(0L, r1.sequence)

        // Pop should detect gap at 1
        val r2 = buffer.pop()
        assertTrue(r2 is JitterBuffer.PopResult.Missing)
        assertEquals(1L, (r2 as JitterBuffer.PopResult.Missing).expectedSequence)

        // Next pop delivers 2
        val r3 = buffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(2L, r3.sequence)
    }

    // ============================================================
    // Wrap-around
    // ============================================================

    @Test
    fun `sequence distance handles normal case`() {
        assertEquals(5, JitterBuffer.sequenceDistance(10, 5))
        assertEquals(-5, JitterBuffer.sequenceDistance(5, 10))
    }

    @Test
    fun `nextSequence wraps around uint32`() {
        assertEquals(0L, JitterBuffer.nextSequence(0xFFFFFFFFL))
        assertEquals(1L, JitterBuffer.nextSequence(0L))
    }

    // ============================================================
    // Clear and reset
    // ============================================================

    @Test
    fun `clear resets buffer but keeps stats`() {
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(1, byteArrayOf(0x02))
        buffer.clear()

        assertEquals(0, buffer.size())
        assertFalse(buffer.isReady())
        assertEquals(2L, buffer.received) // stats preserved
    }

    @Test
    fun `reset clears everything including stats`() {
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(0, byteArrayOf(0x01)) // duplicate
        buffer.reset()

        assertEquals(0, buffer.size())
        assertEquals(0L, buffer.received)
        assertEquals(0L, buffer.droppedDuplicate)
    }

    // ============================================================
    // Statistics
    // ============================================================

    @Test
    fun `statistics track correctly`() {
        buffer.push(0, byteArrayOf(0x01))
        buffer.push(1, byteArrayOf(0x02))
        buffer.push(1, byteArrayOf(0x03)) // duplicate

        assertEquals(3L, buffer.received)
        assertEquals(1L, buffer.droppedDuplicate)

        buffer.pop()
        buffer.pop()
        assertEquals(2L, buffer.delivered)
    }

    // ============================================================
    // Adaptive depth
    // ============================================================

    @Test
    fun `jitter detection grows target depth`() {
        assertEquals(2, buffer.currentTargetDepth())

        // Push out of order to trigger jitter
        buffer.push(5, byteArrayOf(0x01))
        buffer.push(3, byteArrayOf(0x02)) // out of order → jitter

        assertTrue(buffer.currentTargetDepth() > 2)
    }

    @Test
    fun `concurrent push and pop operations are thread safe`() {
        val threadCount = 4
        val framesPerThread = 500
        val executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount + 2)
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val doneLatch = java.util.concurrent.CountDownLatch(threadCount + 2)
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

        for (t in 0 until 2) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until framesPerThread) {
                        val seq = (t * framesPerThread + i).toLong()
                        buffer.push(seq, byteArrayOf((seq and 0xFF).toByte()))
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        for (t in 0 until 2) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until framesPerThread) {
                        buffer.pop()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        for (t in 0 until 2) {
            executor.submit {
                try {
                    startLatch.await()
                    for (i in 0 until framesPerThread) {
                        buffer.size()
                        buffer.isReady()
                        buffer.received
                        buffer.delivered
                        buffer.droppedDuplicate
                        buffer.droppedLate
                        buffer.currentTargetDepth()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue("Timeout waiting for concurrent operations", doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        executor.shutdown()
        assertTrue("Unexpected concurrent exceptions: $errors", errors.isEmpty())
    }

    // ============================================================
    // Regressions
    // ============================================================

    @Test
    fun `large gap exceeds maxPlcGap advances playback head without excessive missing frames`() {
        val testBuffer = JitterBuffer(minDepth = 2, maxDepth = 5, maxLateDistance = 10, maxPlcGap = 10)
        // Push initial frames 100, 101
        testBuffer.push(100L, byteArrayOf(0x01))
        testBuffer.push(101L, byteArrayOf(0x02))

        val f1 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(100L, f1.sequence)
        val f2 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(101L, f2.sequence)

        // Simulate large network gap: next frame arrives at 300 (gap = 198 frames > maxPlcGap 10)
        testBuffer.push(300L, byteArrayOf(0x03))

        // Pop should fast-forward and deliver 300 immediately without emitting 198 missing frames
        val f3 = testBuffer.pop()
        assertTrue("Expected Frame after large gap, got $f3", f3 is JitterBuffer.PopResult.Frame)
        assertEquals(300L, (f3 as JitterBuffer.PopResult.Frame).sequence)
    }

    @Test
    fun `stale packet dropped without rewinding playback head`() {
        val testBuffer = JitterBuffer(minDepth = 2, maxDepth = 5, maxLateDistance = 10)
        // Push frames 1000..1015
        for (i in 1000L..1015L) {
            testBuffer.push(i, byteArrayOf(i.toByte()))
        }
        repeat(16) { testBuffer.pop() }

        // Playback head is now at 1016. Push stale packet 500
        val pushResult = testBuffer.push(500L, byteArrayOf(0x00))
        assertEquals(JitterBuffer.PushResult.LATE, pushResult)
        assertEquals(1L, testBuffer.droppedLate)

        // Push valid next frame 1016 and verify it pops smoothly
        testBuffer.push(1016L, byteArrayOf(0xAA.toByte()))
        val popResult = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(1016L, popResult.sequence)
    }

    @Test
    fun `sequence wrap-around across uint32 boundary delivered in correct order`() {
        val testBuffer = JitterBuffer(minDepth = 2, maxDepth = 5, maxLateDistance = 10)
        val boundary = 0xFFFFFFFFL

        testBuffer.push(boundary - 1, byteArrayOf(0x01))
        testBuffer.push(boundary, byteArrayOf(0x02))
        testBuffer.push(0L, byteArrayOf(0x03)) // wrapped around uint32
        testBuffer.push(1L, byteArrayOf(0x04))

        val f1 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(boundary - 1, f1.sequence)

        val f2 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(boundary, f2.sequence)

        val f3 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(0L, f3.sequence)

        val f4 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(1L, f4.sequence)
    }

    @Test
    fun `stale packets rejected before playback starts preserving monotonic sequence`() {
        val testBuffer = JitterBuffer(minDepth = 2, maxDepth = 5, maxLateDistance = 10)
        // Push initial frame 1000
        val r1 = testBuffer.push(1000L, byteArrayOf(0x01))
        assertEquals(JitterBuffer.PushResult.ACCEPTED, r1)

        // Stale packets 100 and 101 arrive (far behind 1000)
        val r2 = testBuffer.push(100L, byteArrayOf(0x02))
        assertEquals(JitterBuffer.PushResult.LATE, r2)

        val r3 = testBuffer.push(101L, byteArrayOf(0x03))
        assertEquals(JitterBuffer.PushResult.LATE, r3)

        // Monotonic sequence remains at 1000, push next frame 1001
        val r4 = testBuffer.push(1001L, byteArrayOf(0x04))
        assertEquals(JitterBuffer.PushResult.ACCEPTED, r4)

        // Pop delivers 1000 then 1001 without regressing to 100
        val f1 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(1000L, f1.sequence)

        val f2 = testBuffer.pop() as JitterBuffer.PopResult.Frame
        assertEquals(1001L, f2.sequence)
    }
}
