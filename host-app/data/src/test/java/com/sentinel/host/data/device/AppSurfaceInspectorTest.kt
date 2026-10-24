package com.sentinel.host.data.device

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.random.Random

class AppSurfaceInspectorTest {

    private lateinit var mockContext: Context
    private lateinit var inspector: AppSurfaceInspector

    @Before
    fun setUp() {
        mockContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        inspector = AppSurfaceInspector(mockContext)
    }

    @Test
    fun testEntropyCalculationZeroForUniformBytes() {
        // Repeated identical bytes have 0 entropy
        val uniformData = ByteArray(1024) { 0x41.toByte() }
        val stream = ByteArrayInputStream(uniformData)
        val entropy = inspector.calculateStreamEntropyBounded(stream, 1024)
        assertEquals(0.0, entropy, 0.001)
    }

    @Test
    fun testEntropyCalculationHighForRandomBytes() {
        // Perfectly random distribution has entropy near 8.0 bits/byte
        val randomBytes = ByteArray(16384)
        Random(42).nextBytes(randomBytes)
        val stream = ByteArrayInputStream(randomBytes)
        val entropy = inspector.calculateStreamEntropyBounded(stream, 16384)
        assertTrue("Entropy of random bytes must exceed 7.8, got: $entropy", entropy > 7.8)
    }

    @Test
    fun testEntropyCalculationBoundedLimitsReadLength() {
        val largeData = ByteArray(65536) { (it % 256).toByte() }
        val stream = ByteArrayInputStream(largeData)
        val entropy = inspector.calculateStreamEntropyBounded(stream, 1024)
        assertTrue("Entropy must be computed within bounds", entropy > 0.0)
    }

    @Test
    fun testChiSquareUniformVsSkewedDistribution() {
        // Perfectly uniform 256-byte cyclic pattern: chiSquare must be 0
        val uniformData = ByteArray(16384) { (it % 256).toByte() }
        val (_, chiUniform) = inspector.calculateStreamEntropyAndChiSquare(ByteArrayInputStream(uniformData), 16384)
        assertEquals(0.0, chiUniform, 0.001)

        // Skewed Huffman-like distribution (mostly zeros and a few characters): chiSquare must be very high (> 500)
        val skewedData = ByteArray(16384) { if (it % 10 == 0) 0x20.toByte() else 0x00.toByte() }
        val (_, chiSkewed) = inspector.calculateStreamEntropyAndChiSquare(ByteArrayInputStream(skewedData), 16384)
        assertTrue("Skewed distribution must have chiSquare > 500, got: $chiSkewed", chiSkewed > 500.0)
    }
}
