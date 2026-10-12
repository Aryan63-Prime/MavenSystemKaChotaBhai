package com.sentinel.host.data.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.sentinel.host.domain.model.LocationUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Extended Kalman Filter (EKF) Inertial Dead Reckoning Engine.
 *
 * When GPS signal is lost (e.g. underground parking, subway, tunnels, indoor facilities),
 * this engine propagates state estimates using step detection and device orientation (gyro / rotation vector).
 *
 * State vector: x = [East (m), North (m), Velocity (m/s), Heading (rad), Heading Bias (rad/s)]^T
 * Covariance matrix P tracks spatial estimation uncertainty dynamically.
 */
@Singleton
class DeadReckoningEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : SensorEventListener {

    companion object {
        private const val TAG = "Sentinel:DeadReckoning"
        const val EARTH_RADIUS_METERS = 6378137.0
        const val DEFAULT_STRIDE_LENGTH_METERS = 0.75
        const val STATE_DIM = 5
    }

    private val sensorManager = try {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    } catch (_: Exception) {
        null
    }

    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val _estimatedLocation = MutableStateFlow<LocationUpdate?>(null)
    val estimatedLocation: StateFlow<LocationUpdate?> = _estimatedLocation.asStateFlow()

    @Volatile
    var lastKnownGpsLocation: LocationUpdate? = null
        private set

    @Volatile
    var currentBearingDegrees: Float = 0f
        private set

    @Volatile
    var isGpsLost: Boolean = false
        private set

    // EKF State Vector: [x, y, v, psi, b_psi]
    // x: East displacement in meters from origin (lastKnownGpsLocation)
    // y: North displacement in meters from origin
    // v: Velocity in m/s
    // psi: Heading in radians (0 = North, pi/2 = East)
    // b_psi: Heading gyro bias in radians/s
    private val state = DoubleArray(STATE_DIM)

    // Covariance matrix P (5x5)
    private val P = Array(STATE_DIM) { DoubleArray(STATE_DIM) }

    // Process noise covariance Q (5x5)
    private val Q = Array(STATE_DIM) { DoubleArray(STATE_DIM) }

    private var originLat: Double = 0.0
    private var originLon: Double = 0.0
    private var lastStepTimestampNs: Long = 0L

    init {
        resetEkf()
    }

    private fun resetEkf() {
        for (i in 0 until STATE_DIM) {
            state[i] = 0.0
            for (j in 0 until STATE_DIM) {
                P[i][j] = if (i == j) 1.0 else 0.0
                Q[i][j] = 0.0
            }
        }
        // Initial state uncertainties
        P[0][0] = 25.0  // Pos X variance (5m std)
        P[1][1] = 25.0  // Pos Y variance (5m std)
        P[2][2] = 1.0   // Velocity variance (1 m/s std)
        P[3][3] = 0.1   // Heading variance (~18 deg std)
        P[4][4] = 0.01  // Bias variance

        // Process noise tuning
        Q[0][0] = 0.04  // Position noise (0.2m / step)
        Q[1][1] = 0.04
        Q[2][2] = 0.1   // Velocity noise
        Q[3][3] = 0.005 // Heading noise
        Q[4][4] = 0.0001// Bias drift
    }

    fun start() {
        rotationSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        stepSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        Log.i(TAG, "EKF Dead reckoning sensors registered")
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        Log.i(TAG, "EKF Dead reckoning sensors unregistered")
    }

    /**
     * Called whenever a fresh GPS location fix arrives.
     * Updates reference origin and applies EKF measurement update.
     */
    fun onGpsLocationReceived(location: LocationUpdate) {
        val wasLost = isGpsLost
        isGpsLost = false
        lastKnownGpsLocation = location

        if (originLat == 0.0 && originLon == 0.0) {
            originLat = location.latitude
            originLon = location.longitude
            state[0] = 0.0
            state[1] = 0.0
            val initialHeadingRad = Math.toRadians(currentBearingDegrees.toDouble())
            state[3] = initialHeadingRad
        }

        // Project new GPS coordinates into local Cartesian frame relative to origin
        val dLat = Math.toRadians(location.latitude - originLat)
        val dLon = Math.toRadians(location.longitude - originLon)
        val latRefRad = Math.toRadians(originLat)

        val zX = dLon * EARTH_RADIUS_METERS * cos(latRefRad)
        val zY = dLat * EARTH_RADIUS_METERS

        val accuracy = max(location.accuracy.toDouble(), 2.0)
        applyGpsMeasurementUpdate(zX, zY, accuracy)

        if (wasLost) {
            Log.i(TAG, "GPS signal restored! Resetting EKF to fresh satellite fix.")
        }
    }

    /**
     * Flags that GPS signal has been lost (e.g. entered tunnel or underground).
     */
    fun onGpsSignalLost() {
        if (!isGpsLost) {
            isGpsLost = true
            Log.w(TAG, "GPS fix lost! Engaging EKF inertial dead reckoning extrapolation.")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val azimuthRad = orientation[0]
                var azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
                if (azimuthDeg < 0) azimuthDeg += 360f
                currentBearingDegrees = azimuthDeg

                // Update EKF heading state directly from sensor fusion
                val headingRad = Math.toRadians(azimuthDeg.toDouble())
                state[3] = headingRad
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                val nowNs = event.timestamp
                val dt = if (lastStepTimestampNs > 0) {
                    ((nowNs - lastStepTimestampNs) / 1_000_000_000.0).coerceIn(0.1, 2.0)
                } else {
                    0.5
                }
                lastStepTimestampNs = nowNs

                // Step occurred: execute EKF prediction step
                predictStep(DEFAULT_STRIDE_LENGTH_METERS, dt)

                if (isGpsLost && lastKnownGpsLocation != null) {
                    emitExtrapolatedLocation()
                }
            }
        }
    }

    /**
     * EKF Prediction Step:
     * Propagates state: x_new = x + L * sin(psi), y_new = y + L * cos(psi)
     * and propagates covariance: P = F * P * F^T + Q
     */
    fun predictStep(strideMeters: Double, dt: Double) {
        val heading = state[3]
        val v = strideMeters / dt
        state[2] = v

        val dx = strideMeters * sin(heading)
        val dy = strideMeters * cos(heading)

        state[0] += dx
        state[1] += dy

        // Jacobian F:
        // [ 1, 0, dt*sin(psi), stride*cos(psi),  0 ]
        // [ 0, 1, dt*cos(psi), -stride*sin(psi), 0 ]
        // [ 0, 0, 1,           0,               0 ]
        // [ 0, 0, 0,           1,               -dt]
        // [ 0, 0, 0,           0,               1 ]
        val F = Array(STATE_DIM) { DoubleArray(STATE_DIM) }
        for (i in 0 until STATE_DIM) F[i][i] = 1.0
        F[0][2] = dt * sin(heading)
        F[0][3] = strideMeters * cos(heading)
        F[1][2] = dt * cos(heading)
        F[1][3] = -strideMeters * sin(heading)
        F[3][4] = -dt

        // P_next = F * P * F^T + Q
        val FP = multiplyMatrices(F, P)
        val Ft = transpose(F)
        val FPFt = multiplyMatrices(FP, Ft)

        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                P[i][j] = FPFt[i][j] + Q[i][j]
            }
        }
    }

    /**
     * EKF Measurement Update Step when GPS fix is available:
     * z = [x_gps, y_gps]^T
     */
    private fun applyGpsMeasurementUpdate(zX: Double, zY: Double, accuracyMeters: Double) {
        val rVar = accuracyMeters * accuracyMeters

        // Measurement matrix H is 2x5:
        // [1 0 0 0 0]
        // [0 1 0 0 0]
        // Innovation: y = z - H * x
        val y0 = zX - state[0]
        val y1 = zY - state[1]

        // S = H * P * H^T + R
        val s00 = P[0][0] + rVar
        val s01 = P[0][1]
        val s10 = P[1][0]
        val s11 = P[1][1] + rVar

        val detS = (s00 * s11) - (s01 * s10)
        if (abs(detS) < 1e-9) return

        val invS00 = s11 / detS
        val invS01 = -s01 / detS
        val invS10 = -s10 / detS
        val invS11 = s00 / detS

        // K = P * H^T * inv(S)  (5x2 matrix)
        val K = Array(STATE_DIM) { DoubleArray(2) }
        for (i in 0 until STATE_DIM) {
            K[i][0] = P[i][0] * invS00 + P[i][1] * invS10
            K[i][1] = P[i][0] * invS01 + P[i][1] * invS11
        }

        // State update: x = x + K * y
        for (i in 0 until STATE_DIM) {
            state[i] += K[i][0] * y0 + K[i][1] * y1
        }

        // Covariance update: P = (I - K * H) * P
        val I_KH = Array(STATE_DIM) { DoubleArray(STATE_DIM) }
        for (i in 0 until STATE_DIM) I_KH[i][i] = 1.0
        for (i in 0 until STATE_DIM) {
            I_KH[i][0] -= K[i][0]
            I_KH[i][1] -= K[i][1]
        }

        val updatedP = multiplyMatrices(I_KH, P)
        for (i in 0 until STATE_DIM) {
            for (j in 0 until STATE_DIM) {
                P[i][j] = updatedP[i][j]
            }
        }
    }

    /**
     * Converts current EKF displacement (state[0]=East, state[1]=North)
     * back to WGS-84 coordinates and publishes an extrapolated LocationUpdate.
     */
    private fun emitExtrapolatedLocation() {
        val currentGps = lastKnownGpsLocation ?: return

        val xEast = state[0]
        val yNorth = state[1]
        val latRefRad = Math.toRadians(originLat)

        val newLat = originLat + Math.toDegrees(yNorth / EARTH_RADIUS_METERS)
        val newLon = originLon + Math.toDegrees(xEast / (EARTH_RADIUS_METERS * cos(latRefRad)))

        // Total positional standard error from covariance matrix
        val variance = max(P[0][0] + P[1][1], 1.0)
        val estimatedErrorMeters = sqrt(variance).toFloat()

        val extrapolated = LocationUpdate(
            latitude = newLat,
            longitude = newLon,
            accuracy = estimatedErrorMeters,
            battery = currentGps.battery,
            network = "${currentGps.network} (EKF DeadReckoning)"
        )

        _estimatedLocation.value = extrapolated
        Log.d(TAG, "EKF Extrapolated: lat=$newLat, lon=$newLon, error=±${estimatedErrorMeters}m (bearing=$currentBearingDegrees°)")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Inspection helpers for unit tests
    internal fun getEkfState(): DoubleArray = state.clone()
    internal fun getEstimatedAccuracy(): Float = sqrt(max(P[0][0] + P[1][1], 1.0)).toFloat()

    private fun multiplyMatrices(A: Array<DoubleArray>, B: Array<DoubleArray>): Array<DoubleArray> {
        val rowsA = A.size
        val colsA = A[0].size
        val colsB = B[0].size
        val result = Array(rowsA) { DoubleArray(colsB) }

        for (i in 0 until rowsA) {
            for (j in 0 until colsB) {
                var sum = 0.0
                for (k in 0 until colsA) {
                    sum += A[i][k] * B[k][j]
                }
                result[i][j] = sum
            }
        }
        return result
    }

    private fun transpose(A: Array<DoubleArray>): Array<DoubleArray> {
        val rows = A.size
        val cols = A[0].size
        val result = Array(cols) { DoubleArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = A[i][j]
            }
        }
        return result
    }
}
