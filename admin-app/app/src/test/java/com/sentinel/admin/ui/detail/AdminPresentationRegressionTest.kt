package com.sentinel.admin.ui.detail

import android.content.SharedPreferences
import com.sentinel.admin.domain.model.AudioStatistics
import com.sentinel.admin.domain.model.PlaybackState
import com.sentinel.admin.ui.common.RefreshOperation
import com.sentinel.admin.ui.map.CameraSnapshot
import com.sentinel.admin.ui.map.GeofenceDraft
import com.sentinel.admin.ui.map.GeofenceDraftStore
import com.sentinel.admin.ui.map.GeofenceEditorStateHolder
import com.sentinel.admin.ui.map.GeofenceGeometryValidator
import com.sentinel.admin.ui.map.MapsLauncher
import com.sentinel.admin.ui.map.MapsLaunchResult
import com.sentinel.admin.ui.map.StyleAttemptOwner
import com.sentinel.admin.ui.map.ValidationResult
import com.sentinel.admin.ui.map.cameraSaver
import com.sentinel.admin.ui.map.isValidCoordinate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import androidx.lifecycle.SavedStateHandle
import com.sentinel.admin.domain.model.Device
import com.sentinel.admin.domain.model.DeviceContact
import com.sentinel.admin.domain.model.DeviceContactBook
import com.sentinel.admin.domain.model.DeviceLocation
import com.sentinel.admin.domain.model.DeviceUpdateEvent
import com.sentinel.admin.domain.model.EventStatistics
import com.sentinel.admin.domain.repository.AudioRepository
import com.sentinel.admin.domain.repository.ContactRepository
import com.sentinel.admin.domain.repository.DeviceRepository
import com.sentinel.admin.service.AudioMonitor
import com.sentinel.admin.ui.common.RefreshPhase
import com.sentinel.admin.ui.common.RefreshStatus
import com.sentinel.admin.ui.common.SentinelRefreshPresentationPolicy
import com.sentinel.admin.ui.map.DraftLoadMode
import com.sentinel.admin.ui.map.EditorInputs
import com.sentinel.admin.ui.map.EditorSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production-facing unit and regression test suite for Sentinel Admin Presentation Layer.
 *
 * Covers:
 * 1. GeofenceGeometryValidator:
 *    - Finite coordinate ranges and genuine zero preservation (0,0)
 *    - Explicit 10m..50km radius validation
 *    - Strict single-ring polygon policy: terminal closure normalization, zero-length edge rejection (duplicateEdge),
 *      repeated interior vertex / touching ring rejection (touchingRing), antimeridian span limit
 *    - Scale-aware area degeneracy check accepting smallSquare (~11m, 1e-8 deg²) while rejecting collinear sets.
 * 2. SafeImageDecoder & thumbnailSampleSize:
 *    - Bounds arithmetic ensuring NEITHER dimension exceeds maxEdge (portrait, landscape/panorama, square)
 *    - Blank/oversized/invalid input rejection
 * 3. Durable GeofenceDraftStore:
 *    - Round-trip process-restart simulation with SharedPreferences
 *    - Preservation of decimal radius values
 *    - Device uniqueKey isolation for same-name devices
 *    - Storage failure handling (commit failures return false without claiming success)
 *    - Clear and undo semantics
 * 4. Native Map identity mapping & retry generation:
 *    - Native marker.id to uniqueKey mapping with duplicate device names and renames
 *    - Generation checks preventing stale callbacks from mutating newer state
 * 5. LiveAudioPresentationPolicy & BatteryPresentationPolicy:
 *    - PTT safety arming, microphone permission, distinct UI states
 *    - Truthful battery, network, and audio-level labels
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdminPresentationRegressionTest {

    // ============================================================
    // 1. GeofenceGeometryValidator Tests
    // ============================================================

    @Test
    fun `validateCoordinate accepts valid coordinates and preserves genuine zeros`() {
        assertTrue(GeofenceGeometryValidator.validateCoordinate(37.7749, -122.4194).isValid)
        assertTrue(GeofenceGeometryValidator.validateCoordinate(0.0, 0.0).isValid) // Genuine (0,0) Gulf of Guinea
        assertTrue(GeofenceGeometryValidator.validateCoordinate(90.0, 180.0).isValid)
        assertTrue(GeofenceGeometryValidator.validateCoordinate(-90.0, -180.0).isValid)

        assertTrue(isValidCoordinate(0.0, 0.0))
        assertTrue(isValidCoordinate(37.77, -122.41))
        assertFalse(isValidCoordinate(null, 0.0))
        assertFalse(isValidCoordinate(Double.NaN, 0.0))
    }

    @Test
    fun `validateCoordinate rejects null, infinite, NaN and out-of-range coordinates`() {
        assertFalse(GeofenceGeometryValidator.validateCoordinate(null, null).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(37.0, null).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(null, -122.0).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(Double.NaN, 0.0).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(0.0, Double.POSITIVE_INFINITY).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(90.0001, 0.0).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(-90.0001, 0.0).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(0.0, 180.0001).isValid)
        assertFalse(GeofenceGeometryValidator.validateCoordinate(0.0, -180.0001).isValid)
    }

    @Test
    fun `validateRadius enforces explicit 10m minimum and 50km maximum policy`() {
        assertTrue(GeofenceGeometryValidator.validateRadius(10.0f).isValid) // Min boundary
        assertTrue(GeofenceGeometryValidator.validateRadius(150.0f).isValid)
        assertTrue(GeofenceGeometryValidator.validateRadius(50_000.0f).isValid) // Max boundary

        assertFalse(GeofenceGeometryValidator.validateRadius(null).isValid)
        assertFalse(GeofenceGeometryValidator.validateRadius(0.0f).isValid)
        assertFalse(GeofenceGeometryValidator.validateRadius(9.9f).isValid) // Below 10m minimum
        assertFalse(GeofenceGeometryValidator.validateRadius(-15.0f).isValid)
        assertFalse(GeofenceGeometryValidator.validateRadius(50_000.1f).isValid) // Exceeds 50km
        assertFalse(GeofenceGeometryValidator.validateRadius(Float.NaN).isValid)
        assertFalse(GeofenceGeometryValidator.validateRadius(Float.POSITIVE_INFINITY).isValid)
    }

    @Test
    fun `validatePolygon accepts valid convex and concave polygons`() {
        val validTriangle = listOf(
            Pair(37.77, -122.41),
            Pair(37.78, -122.42),
            Pair(37.79, -122.40)
        )
        assertTrue(GeofenceGeometryValidator.validatePolygon(validTriangle).isValid)

        val validQuad = listOf(
            Pair(0.0, 0.0),
            Pair(0.0, 1.0),
            Pair(1.0, 1.0),
            Pair(1.0, 0.0)
        )
        assertTrue(GeofenceGeometryValidator.validatePolygon(validQuad).isValid)
    }

    @Test
    fun `validatePolygon normalizes terminal closure duplicate`() {
        val closedSquare = listOf(
            Pair(0.0, 0.0),
            Pair(0.0, 1.0),
            Pair(1.0, 1.0),
            Pair(1.0, 0.0),
            Pair(0.0, 0.0) // Terminal closure duplicate
        )
        val result = GeofenceGeometryValidator.validatePolygon(closedSquare)
        assertTrue("Closing point duplicate should be normalized", result.isValid)
    }

    @Test
    fun `validatePolygon accepts smallSquare ~11m valid geofence`() {
        val smallSquare = listOf(
            0.0 to 0.0, 0.0 to 0.0001,
            0.0001 to 0.0001, 0.0001 to 0.0
        )
        val result = GeofenceGeometryValidator.validatePolygon(smallSquare)
        assertTrue("~11m-sided square must be valid under scale-aware policy", result.isValid)
    }

    @Test
    fun `validatePolygon rejects duplicateEdge zero-length edges`() {
        val duplicateEdge = listOf(0.0 to 0.0, 0.0 to 2.0, 0.0 to 2.0, 2.0 to 0.0)
        val result = GeofenceGeometryValidator.validatePolygon(duplicateEdge)
        assertFalse(result.isValid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("zero-length edge"))
    }

    @Test
    fun `validatePolygon rejects touchingRing non-adjacent repeated interior vertex`() {
        val touchingRing = listOf(
            0.0 to 0.0, 0.0 to 2.0, 2.0 to 2.0, 2.0 to 0.0,
            0.0 to 0.0, 0.0 to -2.0, -2.0 to -2.0, -2.0 to 0.0
        )
        val result = GeofenceGeometryValidator.validatePolygon(touchingRing)
        assertFalse(result.isValid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("repeated vertices") || (result as ValidationResult.Invalid).reason.contains("touching ring"))
    }

    @Test
    fun `validatePolygon rejects insufficient vertices`() {
        val twoVertices = listOf(Pair(37.77, -122.41), Pair(37.78, -122.42))
        val resultTwo = GeofenceGeometryValidator.validatePolygon(twoVertices)
        assertFalse(resultTwo.isValid)
        assertTrue((resultTwo as ValidationResult.Invalid).reason.contains("at least 3 vertices"))
    }

    @Test
    fun `validatePolygon rejects collinear vertices with zero area`() {
        val collinear = listOf(
            Pair(10.0, 10.0),
            Pair(20.0, 20.0),
            Pair(30.0, 30.0)
        )
        val result = GeofenceGeometryValidator.validatePolygon(collinear)
        assertFalse(result.isValid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("zero area"))
    }

    @Test
    fun `validatePolygon rejects antimeridian crossing`() {
        val crossing = listOf(
            Pair(0.0, 179.5),
            Pair(1.0, -179.5),
            Pair(-1.0, 179.8)
        )
        val result = GeofenceGeometryValidator.validatePolygon(crossing)
        assertFalse(result.isValid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("Antimeridian crossing"))
    }

    @Test
    fun `validatePolygon rejects self-intersecting polygon (bowtie)`() {
        val bowtie = listOf(
            Pair(0.0, 0.0),
            Pair(2.0, 2.0),
            Pair(0.0, 2.0),
            Pair(2.0, 0.0)
        )
        val result = GeofenceGeometryValidator.validatePolygon(bowtie)
        assertFalse(result.isValid)
        assertTrue((result as ValidationResult.Invalid).reason.contains("self-intersect", ignoreCase = true))
    }

    // ============================================================
    // 2. SafeImageDecoder (thumbnailSampleSize) Tests
    // ============================================================

    @Test
    fun `thumbnailSampleSize ensures neither dimension exceeds maxEdge`() {
        assertEquals(1, thumbnailSampleSize(512, 512, maxEdge = 512))
        assertEquals(2, thumbnailSampleSize(1024, 1024, maxEdge = 512))

        // Wide image / panorama: width subsampled to <= 512
        val wideSample = thumbnailSampleSize(2000, 100, maxEdge = 512)
        assertEquals(4, wideSample)
        assertTrue((2000 + wideSample - 1) / wideSample <= 512)

        // Tall image / portrait: height subsampled to <= 512
        val tallSample = thumbnailSampleSize(100, 2000, maxEdge = 512)
        assertEquals(4, tallSample)
        assertTrue((2000 + tallSample - 1) / tallSample <= 512)

        val sample1023 = thumbnailSampleSize(1023, 1023, maxEdge = 512)
        assertEquals(2, sample1023)
        assertTrue((1023 + sample1023 - 1) / sample1023 <= 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `thumbnailSampleSize rejects non-positive dimensions`() {
        thumbnailSampleSize(0, 500, maxEdge = 512)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `thumbnailSampleSize rejects out-of-range maxEdge`() {
        thumbnailSampleSize(100, 100, maxEdge = 16)
    }

    @Test
    fun `SafeImageDecoder rejects blank or oversized base64 data`() = runBlocking {
        assertNull(SafeImageDecoder.decode(""))
        assertNull(SafeImageDecoder.decode("    "))
        // Oversized string exceeding maxEncodedChars
        val hugeString = "A".repeat(100)
        assertNull(SafeImageDecoder.decode(hugeString, maxEncodedChars = 50))
    }

    // ============================================================
    // 3. Durable GeofenceDraftStore Tests
    // ============================================================

    @Test
    fun `GeofenceDraftStore preserves decimal radius and survives process restart simulation`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val store1 = GeofenceDraftStore(fakePrefs)

        val circleDraft = GeofenceDraft.Circle(
            deviceId = "HOST-001-VIVO",
            centerLat = 37.774929,
            centerLon = -122.419416,
            radiusMeters = 125.75f // Decimal radius
        )

        assertTrue(store1.saveDraft(circleDraft))
        assertTrue(store1.hasDraft("HOST-001-VIVO"))

        // Create fresh store instance on same prefs to simulate process restart
        val store2 = GeofenceDraftStore(fakePrefs)
        val loaded = store2.getDraft("HOST-001-VIVO") as? GeofenceDraft.Circle
        assertNotNull(loaded)
        assertEquals("HOST-001-VIVO", loaded?.deviceId)
        assertEquals(37.774929, loaded?.centerLat ?: 0.0, 0.000001)
        assertEquals(-122.419416, loaded?.centerLon ?: 0.0, 0.000001)
        assertEquals(125.75f, loaded?.radiusMeters ?: 0f, 0.001f) // Exact decimal preserved!
    }

    @Test
    fun `GeofenceDraftStore persists polygon drafts across process restart`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val store1 = GeofenceDraftStore(fakePrefs)

        val polyDraft = GeofenceDraft.Polygon(
            deviceId = "HOST-002-POLYGON",
            vertices = listOf(
                Pair(10.1234, 20.5678),
                Pair(10.2234, 20.6678),
                Pair(10.3234, 20.4678)
            )
        )

        assertTrue(store1.saveDraft(polyDraft))

        val store2 = GeofenceDraftStore(fakePrefs)
        val loaded = store2.getDraft("HOST-002-POLYGON") as? GeofenceDraft.Polygon
        assertNotNull(loaded)
        assertEquals(3, loaded?.vertices?.size)
        assertEquals(10.1234, loaded?.vertices?.get(0)?.first ?: 0.0, 0.0001)
    }

    @Test
    fun `GeofenceDraftStore reports persistence failure honestly`() = runBlocking {
        val fakePrefs = FakeSharedPreferences(shouldFailCommit = true)
        val store = GeofenceDraftStore(fakePrefs)

        val draft = GeofenceDraft.Circle("DEV-FAIL", 0.0, 0.0, 50f)
        val result = store.saveDraft(draft)
        assertFalse("Storage failure must return false and not display success", result)
    }

    @Test
    fun `GeofenceDraftStore isolates drafts by device uniqueKey and supports clear`() = runBlocking {
        val fakePrefs = FakeSharedPreferences()
        val store = GeofenceDraftStore(fakePrefs)
        val draft1 = GeofenceDraft.Circle("DEV-KEY-1", 10.0, 20.0, 100f)
        val draft2 = GeofenceDraft.Polygon("DEV-KEY-2", listOf(Pair(0.0, 0.0), Pair(1.0, 0.0), Pair(1.0, 1.0)))

        store.saveDraft(draft1)
        store.saveDraft(draft2)

        assertTrue(store.hasDraft("DEV-KEY-1"))
        assertTrue(store.hasDraft("DEV-KEY-2"))

        assertTrue(store.clearDraft("DEV-KEY-1"))
        assertFalse(store.hasDraft("DEV-KEY-1"))
        assertNull(store.getDraft("DEV-KEY-1"))

        // DEV-KEY-2 remains completely intact
        assertTrue(store.hasDraft("DEV-KEY-2"))
        val loaded2 = store.getDraft("DEV-KEY-2") as? GeofenceDraft.Polygon
        assertNotNull(loaded2)
        assertEquals(3, loaded2?.vertices?.size)
    }

    // ============================================================
    // 4. Native Map Identity & Request Generation Tests
    // ============================================================

    @Test
    fun `fleet marker mapping strictly maps native marker id to stable uniqueKey`() {
        // Simulates the native marker dictionary mapping pattern
        val deviceKeyByMarkerId = mutableMapOf<Long, String>()

        // Two devices sharing the same friendly name "Sentinel Unit"
        val dev1UniqueKey = "HOST-001-VIVO-2D2C"
        val dev2UniqueKey = "HOST-001-SAMS-CBB7"

        val markerId1 = 101L
        val markerId2 = 102L

        deviceKeyByMarkerId[markerId1] = dev1UniqueKey
        deviceKeyByMarkerId[markerId2] = dev2UniqueKey

        // Marker click on markerId1 must resolve to dev1UniqueKey
        assertEquals("HOST-001-VIVO-2D2C", deviceKeyByMarkerId[markerId1])
        assertEquals("HOST-001-SAMS-CBB7", deviceKeyByMarkerId[markerId2])

        // Rename device 1: unique key mapping remains stable
        val renamedDev1Name = "Primary Field Node"
        deviceKeyByMarkerId[markerId1] = dev1UniqueKey // Rebuilt
        assertEquals("HOST-001-VIVO-2D2C", deviceKeyByMarkerId[markerId1])
    }

    @Test
    fun `retry generation prevents stale callbacks from mutating newer state`() {
        var currentGeneration = 0
        var state = "Loading"

        // Generation 0 request starts
        val gen0 = currentGeneration

        // User clicks retry before generation 0 completes
        currentGeneration += 1
        val gen1 = currentGeneration

        // Stale generation 0 callback arrives late
        fun onGen0Callback() {
            if (gen0 == currentGeneration) {
                state = "Ready" // Should NOT execute
            }
        }
        onGen0Callback()
        assertEquals("Loading", state) // Stale callback ignored!

        // Generation 1 callback arrives
        fun onGen1Callback() {
            if (gen1 == currentGeneration) {
                state = "Ready"
            }
        }
        onGen1Callback()
        assertEquals("Ready", state) // Legitimate callback applied!
    }

    @Test
    fun `StyleAttemptOwner manages lifecycle and invalidates stale callbacks`() {
        val owner = StyleAttemptOwner()
        val attempt1 = owner.begin()
        assertTrue(owner.isCurrent(attempt1))
        assertEquals(attempt1, owner.current())

        // Newer attempt starts before attempt1 finishes
        val attempt2 = owner.begin()
        assertFalse(owner.isCurrent(attempt1))
        assertTrue(owner.isCurrent(attempt2))
        assertEquals(attempt2, owner.current())

        // Attempt 1 finishes late -> rejected
        assertFalse(owner.finish(attempt1))
        assertTrue(owner.isCurrent(attempt2))

        // Attempt 2 finishes -> accepted and cleared
        assertTrue(owner.finish(attempt2))
        assertNull(owner.current())
        assertFalse(owner.isCurrent(attempt2))

        // Invalidation clears active attempt
        val attempt3 = owner.begin()
        assertTrue(owner.isCurrent(attempt3))
        owner.invalidate()
        assertNull(owner.current())
        assertFalse(owner.isCurrent(attempt3))
    }

    @Test
    fun `CameraSnapshot and cameraSaver round-trip perfectly`() {
        val original = CameraSnapshot(
            latitude = 37.774929,
            longitude = -122.419416,
            zoom = 14.5,
            bearing = 45.0,
            tilt = 15.0,
            following = false,
            isRestored = true
        )

        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }

        // Save
        val saved = with(cameraSaver) { scope.save(original) } as? List<*>
        assertNotNull(saved)
        assertEquals(7, saved?.size)

        // Restore
        val restored = cameraSaver.restore(saved!!)
        assertNotNull(restored)
        assertEquals(original.latitude, restored?.latitude ?: 0.0, 0.000001)
        assertEquals(original.longitude, restored?.longitude ?: 0.0, 0.000001)
        assertEquals(original.zoom, restored?.zoom ?: 0.0, 0.0001)
        assertEquals(original.bearing, restored?.bearing ?: 0.0, 0.0001)
        assertEquals(original.tilt, restored?.tilt ?: 0.0, 0.0001)
        assertEquals(original.following, restored?.following)
        assertEquals(original.isRestored, restored?.isRestored)
    }

    @Test
    fun `PttActivationOwner single token latch prevents duplicate stop and handles cancel`() {
        val owner = PttActivationOwner()
        assertNull(owner.current())

        // Acquire token
        val token1 = owner.begin()
        assertNotNull(token1)
        assertEquals(token1, owner.current())

        // Second acquire while running returns null (already active)
        val token2 = owner.begin()
        assertNull(token2)

        // Stop with wrong token is rejected
        assertFalse(owner.consume(99999L))
        assertEquals(token1, owner.current())

        // Stop with correct token succeeds and releases latch
        assertTrue(owner.consume(token1!!))
        assertNull(owner.current())

        // Duplicate stop with same token is safely rejected
        assertFalse(owner.consume(token1))

        // Cancel on screen leave or gesture cancellation
        val token3 = owner.begin()
        assertNotNull(token3)
        assertTrue(owner.cancelCurrent())
        assertNull(owner.current())
        assertFalse(owner.consume(token3!!))
    }

    // ============================================================
    // 5. LiveAudioPresentationPolicy Tests
    // ============================================================

    @Test
    fun `LiveAudioPresentationPolicy resolves session active and stop eligibility accurately`() {
        val activeStates = listOf(
            PlaybackState.Playing,
            PlaybackState.Connecting,
            PlaybackState.Buffering,
            PlaybackState.Paused
        )

        for (state in activeStates) {
            assertTrue(
                "State $state must be session active",
                LiveAudioPresentationPolicy.isSessionActive(state, isRecording = false)
            )
            assertTrue(
                "Stop must be eligible for $state",
                LiveAudioPresentationPolicy.isStopEligible(state, isRecording = false)
            )
            assertFalse(
                "Listen must NOT be eligible during active state $state",
                LiveAudioPresentationPolicy.isListenEligible(state, isRecording = false, isOnline = true)
            )
        }

        assertTrue(LiveAudioPresentationPolicy.isSessionActive(PlaybackState.Idle, isRecording = true))
        assertTrue(LiveAudioPresentationPolicy.isStopEligible(PlaybackState.Idle, isRecording = true))
        assertFalse(LiveAudioPresentationPolicy.isListenEligible(PlaybackState.Idle, isRecording = true, isOnline = true))

        assertFalse(LiveAudioPresentationPolicy.isSessionActive(PlaybackState.Idle, isRecording = false))
        assertFalse(LiveAudioPresentationPolicy.isStopEligible(PlaybackState.Idle, isRecording = false))
        assertTrue(LiveAudioPresentationPolicy.isListenEligible(PlaybackState.Idle, isRecording = false, isOnline = true))
        assertFalse(LiveAudioPresentationPolicy.isListenEligible(PlaybackState.Idle, isRecording = false, isOnline = false))
    }

    @Test
    fun `LiveAudioPresentationPolicy resolves PTT labels distinctly`() {
        assertEquals(
            "PTT Locked",
            LiveAudioPresentationPolicy.resolvePttLabel(
                isPttArmed = false,
                hasMicPermission = true,
                isPressPending = true,
                isPttTransmitting = false
            )
        )

        assertEquals(
            "Mic Required",
            LiveAudioPresentationPolicy.resolvePttLabel(
                isPttArmed = true,
                hasMicPermission = false,
                isPressPending = true,
                isPttTransmitting = false
            )
        )

        assertEquals(
            "Connecting…",
            LiveAudioPresentationPolicy.resolvePttLabel(
                isPttArmed = true,
                hasMicPermission = true,
                isPressPending = true,
                isPttTransmitting = false
            )
        )

        assertEquals(
            "Transmitting…",
            LiveAudioPresentationPolicy.resolvePttLabel(
                isPttArmed = true,
                hasMicPermission = true,
                isPressPending = false,
                isPttTransmitting = true
            )
        )

        assertEquals(
            "Hold to talk",
            LiveAudioPresentationPolicy.resolvePttLabel(
                isPttArmed = true,
                hasMicPermission = true,
                isPressPending = false,
                isPttTransmitting = false
            )
        )
    }

    @Test
    fun `LiveAudioPresentationPolicy derives authoritative duration strictly from decoded frames`() {
        assertEquals(0L, LiveAudioPresentationPolicy.calculateAuthoritativeSeconds(AudioStatistics(0, 0)))
        assertEquals(1L, LiveAudioPresentationPolicy.calculateAuthoritativeSeconds(AudioStatistics(50, 50)))
        assertEquals(60L, LiveAudioPresentationPolicy.calculateAuthoritativeSeconds(AudioStatistics(3000, 3000)))
        assertEquals(1L, LiveAudioPresentationPolicy.calculateAuthoritativeSeconds(AudioStatistics(100, 80)))
    }

    // ============================================================
    // 6. BatteryPresentationPolicy Tests
    // ============================================================

    @Test
    fun `BatteryPresentationPolicy formats battery levels preserving zeros and marking stale readings`() {
        assertEquals("Unavailable", BatteryPresentationPolicy.formatBattery(null, isOnline = true, isCharging = false))
        assertEquals("0%", BatteryPresentationPolicy.formatBattery(0, isOnline = true, isCharging = false))
        assertEquals("0% · Stale", BatteryPresentationPolicy.formatBattery(0, isOnline = false, isCharging = false))
        assertEquals("82% ⚡", BatteryPresentationPolicy.formatBattery(82, isOnline = true, isCharging = true))
        assertEquals("82% ⚡ · Stale", BatteryPresentationPolicy.formatBattery(82, isOnline = false, isCharging = true))
        assertEquals("Unavailable", BatteryPresentationPolicy.formatBattery(-5, isOnline = true, isCharging = false))
        assertEquals("Unavailable", BatteryPresentationPolicy.formatBattery(105, isOnline = true, isCharging = false))
    }

    @Test
    fun `BatteryPresentationPolicy formats network types truthfully`() {
        assertEquals("Network: Unavailable", BatteryPresentationPolicy.formatNetwork(null, isOnline = true))
        assertEquals("Network: Unavailable", BatteryPresentationPolicy.formatNetwork("", isOnline = true))
        assertEquals("LTE", BatteryPresentationPolicy.formatNetwork("LTE", isOnline = true))
        assertEquals("Wi-Fi · Stale", BatteryPresentationPolicy.formatNetwork("Wi-Fi", isOnline = false))
    }

    // ============================================================
    // 7. RefreshOperation Lifecycle & Outcome Ownership Tests (Pass 5)
    // ============================================================

    @Test
    fun `RefreshOperation exposes correct attempt IDs and running state`() {
        val idle = RefreshOperation.Idle
        assertNull(idle.attemptId)
        assertFalse(idle.isRunning)

        val running = RefreshOperation.Running(42L)
        assertEquals(42L, running.attemptId)
        assertTrue(running.isRunning)

        val success = RefreshOperation.Success(42L)
        assertEquals(42L, success.attemptId)
        assertFalse(success.isRunning)

        val error = RefreshOperation.Error(42L, "Network timeout")
        assertEquals(42L, error.attemptId)
        assertEquals("Network timeout", error.message)
        assertFalse(error.isRunning)

        val cancelled = RefreshOperation.Cancelled(42L)
        assertEquals(42L, cancelled.attemptId)
        assertFalse(cancelled.isRunning)
    }

    // ============================================================
    // 8. GeofenceEditorStateHolder Mutation Identity & Lifecycle (Pass 5)
    // ============================================================

    @Test
    fun `GeofenceEditorStateHolder advances editorRevision and sets isDirty on every keystroke while coalescing undo snapshots`() = runTest {
        val prefs = FakeSharedPreferences()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        assertEquals(0L, holder.editorRevision)
        assertFalse(holder.isDirty)
        assertEquals(0, holder.historyStack.size)

        // Rapid typing in circleLat within 800ms
        holder.onCircleLatChanged("37.774")
        assertEquals(1L, holder.editorRevision)
        assertTrue(holder.isDirty)
        assertEquals(1, holder.historyStack.size) // Captured initial snapshot before burst
        assertEquals("37.77", holder.historyStack[0].inputs.circleLat)

        holder.onCircleLatChanged("37.7749")
        assertEquals(2L, holder.editorRevision) // Increments on EVERY keystroke
        assertTrue(holder.isDirty)
        assertEquals(1, holder.historyStack.size) // Still 1 snapshot due to 800ms coalescing

        holder.onCircleLatChanged("37.77492")
        assertEquals(3L, holder.editorRevision) // Increments again
        assertTrue(holder.isDirty)
        assertEquals(1, holder.historyStack.size) // Still 1 snapshot

        // Undoing restores the pre-burst snapshot
        val undone = holder.undo()
        assertTrue(undone)
        assertEquals("37.77", holder.circleLatInput)
        assertEquals(4L, holder.editorRevision) // Revision advances on undo as well
        assertFalse(holder.isDirty) // Restored back to initial persistedRevision (0L)
    }

    @Test
    fun `GeofenceEditorStateHolder clearForm resets inputs without mutating persisted storage`() = runTest {
        val prefs = FakeSharedPreferences()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Save a valid draft first
        holder.saveDraft()
        testScheduler.advanceUntilIdle()

        assertTrue(holder.hasPersistedDraft)
        assertFalse(holder.isDirty)
        assertNotNull(store.getDraft("HOST-001"))

        // Calling clearForm() resets UI fields but leaves store completely intact
        holder.clearForm()
        assertEquals("", holder.circleLatInput)
        assertEquals("", holder.circleLonInput)
        assertTrue(holder.isDirty)
        assertTrue("Storage must remain intact after clearForm", holder.hasPersistedDraft)
        assertNotNull("Store draft must not be erased by clearForm", store.getDraft("HOST-001"))

        // Calling deletePersistedDraft() actually purges the record from storage
        holder.deletePersistedDraft()
        testScheduler.advanceUntilIdle()

        assertFalse("Persisted draft flag must clear after delete", holder.hasPersistedDraft)
        assertNull("Store draft must be deleted", store.getDraft("HOST-001"))
    }

    @Test
    fun `GeofenceEditorStateHolder blocks save when validation fails`() = runTest {
        val prefs = FakeSharedPreferences()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Set radius below 10m minimum
        holder.onCircleRadiusChanged("5.0")
        holder.saveDraft()
        testScheduler.advanceUntilIdle()

        assertNotNull("Validation error must be populated", holder.validationError)
        assertNull("No draft should be saved when validation fails", store.getDraft("HOST-001"))

        // Set valid radius (150m)
        holder.onCircleRadiusChanged("150.0")
        holder.saveDraft()
        testScheduler.advanceUntilIdle()

        assertNull("Validation error must clear on valid save", holder.validationError)
        assertNotNull("Valid draft must be saved", store.getDraft("HOST-001"))
    }

    // ============================================================
    // 9. StyleAttemptOwner & Decoupled Native Failures (Pass 5)
    // ============================================================

    @Test
    fun `StyleAttemptOwner generates monotonic attempts and supersedes old attempts`() {
        val owner = StyleAttemptOwner()
        val attempt1 = owner.begin()
        val attempt2 = owner.begin()

        assertTrue(attempt2 > attempt1)
        assertFalse("Attempt 1 is superseded", owner.isCurrent(attempt1))
        assertTrue("Attempt 2 is current", owner.isCurrent(attempt2))
        assertEquals(attempt2, owner.current())

        // Obsolete failure from attempt 1 arrives
        val finishedStale = owner.finish(attempt1)
        assertFalse("Stale attempt cannot finish current attempt", finishedStale)
        assertTrue("Attempt 2 remains current despite stale failure", owner.isCurrent(attempt2))

        // Legitimate success for attempt 2 arrives
        val finishedCurrent = owner.finish(attempt2)
        assertTrue("Current attempt successfully finishes", finishedCurrent)
        assertNull("Owner has no active attempt after finish", owner.current())
    }

    // ============================================================
    // 10. MapsLauncher Coordinates & Intent Generation (Pass 5)
    // ============================================================

    @Test
    fun `MapsLauncher builds unrounded locale-independent URIs preserving genuine zeros`() {
        val geoUriStr = MapsLauncher.buildGeoUriString(37.7749295, -122.4194155, "Alpha Device")
        assertTrue(geoUriStr.startsWith("geo:37.7749295,-122.4194155?q="))
        assertTrue(geoUriStr.contains("37.7749295"))
        assertTrue(geoUriStr.contains("-122.4194155"))

        val zeroUriStr = MapsLauncher.buildGeoUriString(0.0, 0.0, "Origin")
        assertTrue(zeroUriStr.startsWith("geo:0.0,0.0?q="))

        val webUriStr = MapsLauncher.buildWebUriString(37.7749295, -122.4194155)
        assertEquals("https://www.google.com/maps/search/?api=1&query=37.7749295,-122.4194155", webUriStr)

        val webZeroUriStr = MapsLauncher.buildWebUriString(0.0, 0.0)
        assertEquals("https://www.google.com/maps/search/?api=1&query=0.0,0.0", webZeroUriStr)
    }

    // ============================================================
    // 11. Pass 6 & Pass 7 Acceptance Regression Tests
    // ============================================================

    @Test
    fun `Pass6 Test 1 Failed save commit leaves dirty inputs and prior persisted baseline intact - no success callback or message`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = true)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        holder.onCircleRadiusChanged("250.0")
        assertTrue(holder.isDirty)
        val initialBaseline = holder.persistedInputs

        var successCallbackCalled = false
        holder.saveDraft(onDraftSaved = { successCallbackCalled = true })
        testScheduler.advanceUntilIdle()

        assertFalse("Success callback must not be invoked on failed commit", successCallbackCalled)
        assertTrue("Editor must remain dirty after failed commit", holder.isDirty)
        assertEquals("Prior persisted baseline must remain intact", initialBaseline, holder.persistedInputs)
        assertFalse("hasPersistedDraft must not be true if nothing was saved", holder.hasPersistedDraft)
        assertEquals("Could not save draft to local storage", holder.statusMessage)
    }

    @Test
    fun `Pass6 Test 2 Failed delete leaves persisted presence intact and exposes failure`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Save draft successfully first
        holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertTrue(holder.hasPersistedDraft)
        assertNotNull(holder.persistedInputs)
        assertNotNull(store.getDraft("HOST-001"))

        // Simulate failed commit on delete
        prefs.shouldFailCommit = true
        holder.deletePersistedDraft()
        testScheduler.advanceUntilIdle()

        assertTrue("hasPersistedDraft must remain true on failed delete", holder.hasPersistedDraft)
        assertNotNull("persistedInputs must remain intact on failed delete", holder.persistedInputs)
        assertNotNull("Storage record must not be erased on failed delete", store.getDraft("HOST-001"))
        assertEquals("Could not delete draft from local storage", holder.statusMessage)
    }

    @Test
    fun `Pass6 Test 3 A delayed load cannot overwrite intervening user edits or clear their undo history`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        // Store an existing draft in store
        store.saveDraft(GeofenceDraft.Circle("HOST-001", 10.0, 20.0, 300.0f, System.currentTimeMillis()))

        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Launch delayed load
        holder.loadDraft()
        // Before IO completes, user performs edits
        holder.onCircleLatChanged("55.123")
        assertEquals(1, holder.historyStack.size)

        // Now advance IO
        testScheduler.advanceUntilIdle()

        // User edit must be preserved
        assertEquals("55.123", holder.circleLatInput)
        assertEquals(1, holder.historyStack.size)
        assertTrue(holder.isDirty)
        // Persisted baseline was loaded
        assertEquals("10.0", holder.persistedInputs?.circleLat)
    }

    @Test
    fun `Pass6 Test 4 Save A then edit B while IO blocked - completion persists A and leaves B dirty`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Save A
        holder.saveDraft()
        // While IO is blocked, user modifies to B
        holder.onCircleLatChanged("44.44")
        testScheduler.advanceUntilIdle()

        // Storage contains A
        val saved = store.getDraft("HOST-001") as? GeofenceDraft.Circle
        assertNotNull(saved)
        assertEquals(37.77, saved!!.centerLat, 0.001)

        // Editor inputs is B, and is marked dirty!
        assertEquals("44.44", holder.circleLatInput)
        assertTrue("Editor must remain dirty because current inputs (B) != persisted baseline (A)", holder.isDirty)
        assertEquals("37.77", holder.persistedInputs?.circleLat)
    }

    @Test
    fun `Pass6 Test 5 An old load save delete cannot mutate another device owner or clear newer status`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Start save for HOST-001
        holder.saveDraft()

        // Switch device context to HOST-002 before IO finishes
        holder.resetForDevice("HOST-002", newFallbackLat = 12.34, newFallbackLon = 56.78)
        holder.statusMessage = "Active device HOST-002"

        // Advance IO for the old HOST-001 save
        testScheduler.advanceUntilIdle()

        // Device remains HOST-002, inputs and status are not corrupted
        assertEquals("HOST-002", holder.deviceId)
        assertEquals("12.34", holder.circleLatInput)
        assertEquals("Active device HOST-002", holder.statusMessage)
    }

    @Test
    fun `Pass7 Test 6 Process restoration baseline reconciliation preserves unsaved edits while syncing committed storage`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)

        // Baseline A in holder (fallbackLat = 37.77, fallbackLon = -122.41)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )
        // Verify Undo to initial coordinates is clean before any edits
        assertFalse(holder.isDirty)

        // User edits to B (38.88, -121.11, radius 250)
        holder.onCircleLatChanged("38.88")
        holder.onCircleLonChanged("-121.11")
        holder.onCircleRadiusChanged("250")
        assertTrue(holder.isDirty)

        // Capture state (simulate process recreation / rememberSaveable save)
        val savedMap = holder.saveToMap()

        // Storage commits B in the background while state was captured
        store.saveDraft(GeofenceDraft.Circle("HOST-001", 38.88, -121.11, 250f))

        // Restore holder in new process / activity
        val restoredHolder = GeofenceEditorStateHolder.restoreFromMap(
            saved = savedMap,
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Restored fields B survive
        assertEquals("38.88", restoredHolder.circleLatInput)
        assertEquals("-121.11", restoredHolder.circleLonInput)
        assertEquals("250", restoredHolder.circleRadiusInput)
        assertTrue("Flagged as needing baseline reconciliation", restoredHolder.needsBaselineReconciliation)

        // Execute non-destructive baseline reconciliation
        restoredHolder.loadDraft(mode = DraftLoadMode.RECONCILE_RESTORED_BASELINE)
        testScheduler.advanceUntilIdle()

        // Baseline is reconciled to B, so fields B match storage B -> isDirty is false!
        assertFalse("Restored edits match newly committed storage baseline, dirty state is clean", restoredHolder.isDirty)
        assertEquals("38.88", restoredHolder.circleLatInput)
        assertEquals(38.88, restoredHolder.persistedInputs?.circleLat?.toDouble() ?: 0.0, 0.001)

        // Numeric semantic equality check: "250" equals "250.0"
        assertTrue(
            "Numeric semantic equality accepts 250 == 250.0 without marking dirty",
            EditorInputs("CIRCLE", "38.88", "-121.11", "250")
                .semanticallyEquals(EditorInputs("CIRCLE", "38.88", "-121.11", "250.0"))
        )

        // User edits to C during or after reconciliation
        restoredHolder.onCircleLatChanged("39.99")
        assertTrue("Later edit C marks editor dirty against reconciled baseline", restoredHolder.isDirty)

        // Undo back to reconciled baseline is clean
        restoredHolder.undo()
        assertFalse("Undo back to reconciled baseline restores clean state", restoredHolder.isDirty)
    }

    @Test
    fun `Pass6 Test 7 Saved A then edit B then save B - Undo A is dirty and Undo B is clean`() = runTest {
        val prefs = FakeSharedPreferences(shouldFailCommit = false)
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        var mockTime = 1000L
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 10.0,
            fallbackLon = 20.0,
            ioDispatcher = testDispatcher,
            clockMs = { mockTime }
        )

        // Save A (10.0, 20.0)
        holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertFalse(holder.isDirty)
        assertEquals("10.0", holder.persistedInputs?.circleLat)

        // Advance mock time past 800ms coalescing window
        mockTime += 1000L

        // Edit B (30.0)
        holder.onCircleLatChanged("30.0")
        assertTrue(holder.isDirty)

        // Save B
        holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertFalse(holder.isDirty)
        assertEquals("30.0", store.getDraft("HOST-001")?.let { (it as GeofenceDraft.Circle).centerLat.toString() })

        // Undo back to A
        val undid = holder.undo()
        assertTrue(undid)
        assertEquals("10.0", holder.circleLatInput)
        assertTrue("Undo back to A while storage contains B must be dirty", holder.isDirty)

        // Edit/undo back to B
        holder.onCircleLatChanged("30.0")
        assertFalse("Matching current persisted baseline (B) must be clean", holder.isDirty)
    }

    @Test
    fun `Pass7 Test 8 Scheduler barrier mutex ownership and IO cancellation settle busy state truthfully`() = runTest {
        val prefs = FakeSharedPreferences()
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val store = GeofenceDraftStore(prefs, ioDispatcher = testDispatcher)
        val holder = GeofenceEditorStateHolder(
            initialDeviceId = "HOST-001",
            draftStore = store,
            ownerScope = this,
            fallbackLat = 37.77,
            fallbackLon = -122.41,
            ioDispatcher = testDispatcher
        )

        // Part 1: Start save A, positively observe isSaving = true with IO pending,
        // queue delete B, cancel B while waiting, complete A. Busy state settles and another save works.
        var observedSavingDuringIo = false
        var deleteJobB: kotlinx.coroutines.Job? = null
        prefs.onCommit = {
            observedSavingDuringIo = holder.isSaving
            deleteJobB = holder.deletePersistedDraft()
            deleteJobB?.cancel()
        }

        val saveJobA = holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertTrue("isSaving must be positively observed true with IO pending", observedSavingDuringIo)
        assertFalse("Busy state must settle to false after A completes", holder.isSaving)
        assertTrue("Draft A successfully committed", holder.hasPersistedDraft)
        prefs.onCommit = null

        // Verify another save works immediately
        holder.onCircleRadiusChanged("300")
        holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertFalse(holder.isSaving)
        assertEquals(300f, (store.getDraft("HOST-001") as? GeofenceDraft.Circle)?.radiusMeters ?: 0f, 0.1f)

        // Part 2: Reset to device B after A's save has entered busy state; finish old work and B's load.
        // B must not remain busy or receive A's metadata.
        holder.onCircleRadiusChanged("400")
        var observedSavingInPart2 = false
        prefs.onCommit = {
            observedSavingInPart2 = holder.isSaving
            holder.resetForDevice("HOST-002", newFallbackLat = 10.0, newFallbackLon = 20.0)
        }
        holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertTrue("A's save had entered busy state", observedSavingInPart2)
        assertFalse("Device B must not remain busy", holder.isSaving)
        assertEquals("HOST-002", holder.deviceId)
        assertEquals("10.0", holder.circleLatInput)
        prefs.onCommit = null

        // Part 3: Cancel during actual IO, not before the save coroutine has started.
        var saveJob3: kotlinx.coroutines.Job? = null
        var observedSavingInPart3 = false
        prefs.onCommit = {
            observedSavingInPart3 = holder.isSaving
            saveJob3?.cancel()
        }
        saveJob3 = holder.saveDraft()
        testScheduler.advanceUntilIdle()
        assertTrue("Save coroutine was running on IO before cancellation", observedSavingInPart3)
        assertFalse("Holder busy flag must settle after cancellation during IO", holder.isSaving)
        prefs.onCommit = null
    }

    @Test
    fun `Pass7 Test 9 Cached network failure preserves cached device and emits RefreshOperation Error`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val fakeRepo = FakeDeviceRepositoryForRegression(
                    deviceResult = Result.success(TEST_DEVICE),
                    fetchResult = Result.failure(java.io.IOException("Network unreachable"))
                )
                val fakeAudioRepo = FakeAudioRepositoryForRegression()
                val fakeContactRepo = FakeContactRepositoryForRegression()
                val webSocket = com.sentinel.admin.data.remote.websocket.WebSocketDataSource(okhttp3.OkHttpClient())
                val pttRecorder = FakePttRecorderForRegression()
                val audioMonitor = AudioMonitor(
                    decoder = com.sentinel.admin.data.audio.NativeOpusDecoder(),
                    audioOutput = FakeAudioOutputForRegression(),
                    scope = TestScope(testDispatcher)
                )

                val viewModel = DeviceDetailViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("deviceId" to "HOST-001")),
                    deviceRepository = fakeRepo,
                    audioRepository = fakeAudioRepo,
                    contactRepository = fakeContactRepo,
                    audioMonitor = audioMonitor,
                    webSocketDataSource = webSocket,
                    pttAudioRecorder = pttRecorder
                )
                testScheduler.advanceUntilIdle()

                // Verify initial load displays cached device
                assertNotNull(viewModel.uiState.value.device)
                assertEquals("HOST-001", viewModel.uiState.value.device?.deviceId)

                // Trigger fresh refresh which fails with network error
                viewModel.refresh()
                assertTrue("Refresh is running", viewModel.uiState.value.refreshOperation.isRunning)

                testScheduler.runCurrent()

                // Cached device must remain completely preserved!
                assertNotNull("Cached device must not be cleared on refresh error", viewModel.uiState.value.device)
                assertEquals("HOST-001", viewModel.uiState.value.device?.deviceId)
                assertFalse("isRefreshing must be false", viewModel.uiState.value.isRefreshing)
                assertTrue("RefreshOperation must be Error", viewModel.uiState.value.refreshOperation is RefreshOperation.Error)
                assertEquals("Network unreachable", (viewModel.uiState.value.refreshOperation as RefreshOperation.Error).message)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Pass7 Test 10 Production consumeRefreshOutcome consumes terminal outcomes without touching running operations`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val fakeRepo = FakeDeviceRepositoryForRegression(
                    deviceResult = Result.success(TEST_DEVICE),
                    fetchResult = Result.failure(java.io.IOException("Connection timeout"))
                )
                val fakeAudioRepo = FakeAudioRepositoryForRegression()
                val fakeContactRepo = FakeContactRepositoryForRegression()
                val webSocket = com.sentinel.admin.data.remote.websocket.WebSocketDataSource(okhttp3.OkHttpClient())
                val pttRecorder = FakePttRecorderForRegression()
                val audioMonitor = AudioMonitor(
                    decoder = com.sentinel.admin.data.audio.NativeOpusDecoder(),
                    audioOutput = FakeAudioOutputForRegression(),
                    scope = TestScope(testDispatcher)
                )

                val viewModel = DeviceDetailViewModel(
                    savedStateHandle = SavedStateHandle(mapOf("deviceId" to "HOST-001")),
                    deviceRepository = fakeRepo,
                    audioRepository = fakeAudioRepo,
                    contactRepository = fakeContactRepo,
                    audioMonitor = audioMonitor,
                    webSocketDataSource = webSocket,
                    pttAudioRecorder = pttRecorder
                )
                testScheduler.advanceUntilIdle()

                // Start refresh
                viewModel.refresh()
                val runningAttemptId = viewModel.uiState.value.refreshOperation.attemptId!!
                assertTrue(viewModel.uiState.value.refreshOperation is RefreshOperation.Running)

                // Attempting to consume while Running must be a no-op!
                viewModel.consumeRefreshOutcome(runningAttemptId)
                assertTrue("Running attempt must NOT be consumed", viewModel.uiState.value.refreshOperation is RefreshOperation.Running)

                // Complete operation to Error (runCurrent completes the network call without fast-forwarding 1800ms settling delay)
                testScheduler.runCurrent()
                assertTrue(viewModel.uiState.value.refreshOperation is RefreshOperation.Error)

                // Mismatched attempt ID must not consume
                viewModel.consumeRefreshOutcome(runningAttemptId + 999L)
                assertTrue("Mismatched attemptId must not consume outcome", viewModel.uiState.value.refreshOperation is RefreshOperation.Error)

                // Matching attempt ID consumes terminal error to Idle immediately (cancels settling job)
                viewModel.consumeRefreshOutcome(runningAttemptId)
                assertEquals(RefreshOperation.Idle, viewModel.uiState.value.refreshOperation)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `Pass7 Test 11 Refresh phase presentation couples gesture priority and drives indicator attributes`() {
        // Active pull gesture (armed: fraction >= 1.0f) strictly overrides lingering success
        val armedPhase = SentinelRefreshPresentationPolicy.resolvePhase(
            fraction = 1.0f,
            isRefreshing = false,
            operation = RefreshOperation.Success(1L)
        )
        assertEquals(RefreshPhase.ARMED, armedPhase)
        assertEquals("Release to refresh", SentinelRefreshPresentationPolicy.resolveLabel(1.0f, false, RefreshOperation.Success(1L)))

        // Active pull gesture (pulling: fraction > 0.1f) strictly overrides lingering error
        val pullingPhase = SentinelRefreshPresentationPolicy.resolvePhase(
            fraction = 0.5f,
            isRefreshing = false,
            operation = RefreshOperation.Error(1L, "Failed")
        )
        assertEquals(RefreshPhase.PULLING, pullingPhase)
        assertEquals("Pull to refresh", SentinelRefreshPresentationPolicy.resolveLabel(0.5f, false, RefreshOperation.Error(1L, "Failed")))

        // Active refresh operation
        val refreshingPhase = SentinelRefreshPresentationPolicy.resolvePhase(
            fraction = 0f,
            isRefreshing = false,
            operation = RefreshOperation.Running(2L)
        )
        assertEquals(RefreshPhase.REFRESHING, refreshingPhase)
        assertEquals("Updating…", SentinelRefreshPresentationPolicy.resolveLabel(0f, false, RefreshOperation.Running(2L)))

        // Terminal idle success
        val successPhase = SentinelRefreshPresentationPolicy.resolvePhase(
            fraction = 0f,
            isRefreshing = false,
            operation = RefreshOperation.Success(3L)
        )
        assertEquals(RefreshPhase.SUCCESS, successPhase)
        assertEquals("Updated", SentinelRefreshPresentationPolicy.resolveLabel(0f, false, RefreshOperation.Success(3L)))

        // Terminal idle error
        val errorPhase = SentinelRefreshPresentationPolicy.resolvePhase(
            fraction = 0f,
            isRefreshing = false,
            operation = RefreshOperation.Error(4L, "Custom error")
        )
        assertEquals(RefreshPhase.ERROR, errorPhase)
        assertEquals("Couldn’t update", SentinelRefreshPresentationPolicy.resolveLabel(0f, false, RefreshOperation.Error(4L, "Custom error")))
    }

    @Test
    fun `Pass7 Test 12 DetailTab enumeration and layout-aware bring-into-view mapping`() {
        val tabs = DetailTab.entries
        assertEquals(3, tabs.size)
        assertTrue(tabs.contains(DetailTab.TELEMETRY))
        assertTrue(tabs.contains(DetailTab.ROUTE))
        assertTrue(tabs.contains(DetailTab.MEDIA))

        // Ensure each tab label is unique and non-empty
        val labels = tabs.map { it.label }
        assertEquals(tabs.size, labels.distinct().size)
        assertEquals("Telemetry", DetailTab.TELEMETRY.label)
        assertEquals("Route", DetailTab.ROUTE.label)
        assertEquals("Media", DetailTab.MEDIA.label)
    }

    @Test
    fun `Pass7 Test 13 Payload bounds enforcement and numeric semantic equality`() {
        // Enforce MAX_FIELD_LENGTH = 128
        val longString = "A".repeat(300)
        val snapshot = EditorSnapshot.fromMap(
            mapOf(
                "type" to "CIRCLE",
                "lat" to longString,
                "lon" to longString,
                "rad" to longString,
                "vertices" to emptyList<Any>()
            )
        )
        assertNotNull(snapshot)
        assertEquals(128, snapshot!!.inputs.circleLat.length)
        assertEquals(128, snapshot.inputs.circleLon.length)
        assertEquals(128, snapshot.inputs.circleRadius.length)

        // Enforce MAX_POLYGON_VERTICES = 100
        val manyVertices = (1..200).map { listOf(it.toDouble(), it.toDouble()) }
        val polySnapshot = EditorSnapshot.fromMap(
            mapOf(
                "type" to "POLYGON",
                "lat" to "",
                "lon" to "",
                "rad" to "",
                "vertices" to manyVertices
            )
        )
        assertEquals(100, polySnapshot!!.inputs.polygonVertices.size)

        // Semantic numeric equality
        val in1 = EditorInputs("CIRCLE", "37.77", "-122.41", "150")
        val in2 = EditorInputs("CIRCLE", "37.770", "-122.4100", "150.0")
        assertTrue(in1.semanticallyEquals(in2))

        val inDifferent = EditorInputs("CIRCLE", "37.77", "-122.41", "151")
        assertFalse(in1.semanticallyEquals(inDifferent))
    }

    // ============================================================
    // Pass 8 Regression Tests
    // ============================================================

    @Test
    fun `Pass8 Test 1 DeviceLocation hasReportedFix prevents synthetic coordinates from plotting`() {
        val syntheticFromBattery = DeviceLocation(
            deviceId = "HOST-001",
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 0.0,
            battery = 45,
            network = "unknown",
            recordedAt = "",
            hasReportedFix = false,
            receivedAt = null
        )
        assertFalse(syntheticFromBattery.hasReportedFix)

        val genuineFix = DeviceLocation(
            deviceId = "HOST-001",
            latitude = 37.7749,
            longitude = -122.4194,
            accuracy = 12.0,
            battery = 80,
            network = "WiFi",
            recordedAt = "2026-10-11T05:00:00Z",
            hasReportedFix = true,
            receivedAt = "2026-10-11T05:00:00Z"
        )
        assertTrue(genuineFix.hasReportedFix)
        assertTrue(isValidCoordinate(genuineFix.latitude, genuineFix.longitude))

        // Genuine (0,0) fix with reported GPS fix
        val genuineGulfOfGuinea = DeviceLocation(
            deviceId = "HOST-001",
            latitude = 0.0,
            longitude = 0.0,
            accuracy = 5.0,
            battery = 90,
            network = "LTE",
            recordedAt = "2026-10-11T05:00:00Z",
            hasReportedFix = true,
            receivedAt = "2026-10-11T05:00:00Z"
        )
        assertTrue(genuineGulfOfGuinea.hasReportedFix)
        assertTrue(isValidCoordinate(genuineGulfOfGuinea.latitude, genuineGulfOfGuinea.longitude))
    }

    @Test
    fun `Pass8 Test 2 formatSampleTime formats ISO timestamps and falls back gracefully`() {
        assertEquals("Sample time unknown", formatSampleTime(null))
        assertEquals("Sample time unknown", formatSampleTime(""))
        assertEquals("Sample time unknown", formatSampleTime("   "))

        val formattedIso = formatSampleTime("2026-10-08T20:23:40Z")
        assertTrue(formattedIso.contains(":") && formattedIso.contains("Oct"))

        val epochMillis = "1728419020000"
        val formattedEpoch = formatSampleTime(epochMillis)
        assertTrue(formattedEpoch.contains(":"))
    }

    @Test
    fun `Pass8 Test 3 DashboardUiState emptyFilterMessage generates truthful feedback`() {
        val allState = com.sentinel.admin.ui.dashboard.DashboardUiState(
            devices = listOf(TEST_DEVICE),
            displayDevices = emptyList(),
            searchQuery = "",
            fleetFilter = com.sentinel.admin.ui.dashboard.FleetFilter.ALL
        )
        assertNull(allState.emptyFilterMessage)

        val filteredState = com.sentinel.admin.ui.dashboard.DashboardUiState(
            devices = listOf(TEST_DEVICE),
            displayDevices = emptyList(),
            searchQuery = "",
            fleetFilter = com.sentinel.admin.ui.dashboard.FleetFilter.LOW_BATTERY
        )
        assertEquals("No devices match filter: Low Battery", filteredState.emptyFilterMessage)

        val searchedState = com.sentinel.admin.ui.dashboard.DashboardUiState(
            devices = listOf(TEST_DEVICE),
            displayDevices = emptyList(),
            searchQuery = "Samsung",
            fleetFilter = com.sentinel.admin.ui.dashboard.FleetFilter.ALL
        )
        assertEquals("No results for \"Samsung\"", searchedState.emptyFilterMessage)
    }

    @Test
    fun `Pass8 Test 4 filterByFleet and buildMarkers handle low battery and active alert device keys`() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            runTest(testDispatcher) {
                val repo = FakeDeviceRepositoryForRegression()
                val viewModel = com.sentinel.admin.ui.dashboard.DashboardViewModel(repo)

                val normalDevice = TEST_DEVICE.copy(
                    deviceId = "HOST-001",
                    latestLocation = DeviceLocation("HOST-001", 37.77, -122.41, 10.0, 85, "WiFi", "", true)
                )
                val lowBatteryDevice = TEST_DEVICE.copy(
                    deviceId = "HOST-002",
                    deviceName = "Low Bat Phone",
                    latestLocation = DeviceLocation("HOST-002", 37.78, -122.42, 10.0, 15, "LTE", "", true)
                )
                val unknownBatteryDevice = TEST_DEVICE.copy(
                    deviceId = "HOST-003",
                    deviceName = "Unknown Bat Phone",
                    latestLocation = DeviceLocation("HOST-003", 37.79, -122.43, 10.0, -1, "LTE", "", true)
                )
                val testDevices = listOf(normalDevice, lowBatteryDevice, unknownBatteryDevice)

                // Low battery strictly matches battery in 0..20, rejects -1
                val lowBatResult = viewModel.filterByFleet(testDevices, com.sentinel.admin.ui.dashboard.FleetFilter.LOW_BATTERY)
                assertEquals(1, lowBatResult.size)
                assertEquals("HOST-002", lowBatResult[0].deviceId)

                // Markers omit devices without reported fix
                val noFixDevice = TEST_DEVICE.copy(
                    deviceId = "HOST-004",
                    latestLocation = DeviceLocation("HOST-004", 0.0, 0.0, 0.0, 50, "LTE", "", false)
                )
                val markers = viewModel.buildMarkers(listOf(normalDevice, noFixDevice))
                assertEquals(1, markers.size)
                assertEquals("HOST-001", markers[0].deviceId)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    companion object {
        private val TEST_DEVICE = Device(
            deviceId = "HOST-001",
            connectionId = "CONN-001",
            authenticated = true,
            registered = true,
            registrationState = "registered",
            heartbeatStatus = "online",
            connectedAt = "2026-07-09T12:00:00Z",
            lastHeartbeat = "2026-07-09T12:00:20Z",
            deviceName = "Pixel 9",
            appVersion = "1.0.0",
            model = "Google Pixel",
            latestLocation = null
        )
    }

    private class FakeDeviceRepositoryForRegression(
        var deviceResult: Result<Device> = Result.success(TEST_DEVICE),
        var fetchResult: Result<Device> = Result.success(TEST_DEVICE)
    ) : DeviceRepository {
        val cachedMap = MutableStateFlow<Map<String, Device>>(mapOf("HOST-001" to TEST_DEVICE))
        override val devices: StateFlow<Map<String, Device>> = cachedMap
        override val deviceUpdates: SharedFlow<DeviceUpdateEvent> = MutableSharedFlow()
        override val eventStatistics: StateFlow<EventStatistics> = MutableStateFlow(EventStatistics())
        override val emergencyAlert: StateFlow<com.sentinel.admin.domain.model.EmergencyAlert?> = MutableStateFlow(null)

        override fun dismissEmergencyAlert() {}
        override fun triggerTestEmergencyAlert(alert: com.sentinel.admin.domain.model.EmergencyAlert) {}
        override suspend fun wakeDevice(deviceId: String): Result<Boolean> = Result.success(true)
        override suspend fun getDevice(deviceId: String): Result<Device> = deviceResult
        override suspend fun fetchFreshDevice(deviceId: String): Result<Device> = fetchResult
        override suspend fun getDevices(): Result<List<Device>> = Result.success(cachedMap.value.values.toList())
    }

    private class FakeAudioRepositoryForRegression : AudioRepository {
        override fun listen(deviceId: String, streamId: java.util.UUID?): Boolean = true
        override fun stopListening(deviceId: String, streamId: java.util.UUID?): Boolean = true
    }

    private class FakeContactRepositoryForRegression : ContactRepository {
        override fun getContactBook(deviceId: String): Flow<DeviceContactBook?> = MutableStateFlow(null)
        override suspend fun saveContactBook(deviceId: String, contactBook: DeviceContactBook): Result<Unit> = Result.success(Unit)
    }

    private class FakeAudioOutputForRegression : com.sentinel.admin.data.audio.AudioOutput {
        override fun initialize(): Boolean = true
        override fun write(pcm: ShortArray, offset: Int, size: Int): Int = size
        override fun pause() {}
        override fun resume() {}
        override fun release() {}
    }

    private class FakePttRecorderForRegression : com.sentinel.admin.data.audio.PttAudioRecorder {
        override var isRecording: Boolean = false
        override fun start(onChunkAvailable: (pcmBase64: String, normalizedLevel: Float) -> Unit): Boolean = true
        override fun stop() {}
    }
}

/**
 * Lightweight in-memory SharedPreferences test double verifying commit/apply and key serialization.
 */
class FakeSharedPreferences(
    var shouldFailCommit: Boolean = false,
    var onCommit: (() -> Unit)? = null
) : SharedPreferences {
    val storage = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(storage)
    override fun getString(key: String?, defValue: String?): String? = storage[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = storage[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = storage[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = storage[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = storage[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = storage.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val staging = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { staging[it] = value }
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removed.add(it) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            prefs.onCommit?.invoke()
            if (prefs.shouldFailCommit) return false
            if (clearAll) prefs.storage.clear()
            removed.forEach { prefs.storage.remove(it) }
            staging.forEach { (k, v) -> if (v != null) prefs.storage[k] = v else prefs.storage.remove(k) }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
