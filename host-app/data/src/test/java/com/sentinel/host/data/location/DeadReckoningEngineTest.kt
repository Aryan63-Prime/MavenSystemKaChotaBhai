package com.sentinel.host.data.location

import android.content.Context
import com.sentinel.host.domain.model.LocationUpdate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeadReckoningEngineTest {

    private lateinit var context: Context
    private lateinit var engine: DeadReckoningEngine

    @Before
    fun setUp() {
        context = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }
        engine = DeadReckoningEngine(context)
    }

    @Test
    fun testInitialGpsLocationSetsOriginAndResetsEkfState() {
        val gps = LocationUpdate(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 4.0f,
            battery = 85,
            network = "WiFi"
        )

        engine.onGpsLocationReceived(gps)

        assertEquals(gps, engine.lastKnownGpsLocation)
        assertFalse(engine.isGpsLost)
        val state = engine.getEkfState()
        assertEquals(0.0, state[0], 0.001)
        assertEquals(0.0, state[1], 0.001)
    }

    @Test
    fun testPredictStepPropagatesStateAndIncreasesUncertainty() {
        val initialAccuracy = engine.getEstimatedAccuracy()

        // Predict a step forward
        engine.predictStep(10.0, 1.0)

        val state = engine.getEkfState()
        assertTrue("Displacement y should increase along north heading", state[1] > 0.0)

        val updatedAccuracy = engine.getEstimatedAccuracy()
        assertTrue("Uncertainty should increase after step propagation without GPS", updatedAccuracy >= initialAccuracy)
    }

    @Test
    fun testOnGpsSignalLostEngagesDeadReckoningExtrapolation() {
        val gps = LocationUpdate(
            latitude = 12.9716,
            longitude = 77.5946,
            accuracy = 3.5f,
            battery = 90,
            network = "5G"
        )
        engine.onGpsLocationReceived(gps)

        assertFalse(engine.isGpsLost)
        engine.onGpsSignalLost()
        assertTrue(engine.isGpsLost)
    }

    @Test
    fun testRecoveringGpsClearsLostStateAndUpdatesState() {
        val initialGps = LocationUpdate(10.0, 20.0, 5.0f, 80, "Cellular")
        engine.onGpsLocationReceived(initialGps)
        engine.onGpsSignalLost()
        assertTrue(engine.isGpsLost)

        val recoveredGps = LocationUpdate(10.0001, 20.0001, 3.0f, 80, "Cellular")
        engine.onGpsLocationReceived(recoveredGps)
        assertFalse(engine.isGpsLost)
        assertEquals(recoveredGps, engine.lastKnownGpsLocation)
    }
}
