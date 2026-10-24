package com.sentinel.host.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class MerkleBlock(
    val index: Long,
    val timestampMs: Long,
    val previousHash: String,
    val powerWatts: Double,
    val currentMilliamps: Double,
    val voltageMillivolts: Int,
    val eventHash: String
)

class PmicMerkleAuditEngine(private val context: Context) {

    companion object {
        private const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    private var blockIndex = 0L
    private var lastBlockHash = GENESIS_HASH
    private val ledger = CopyOnWriteArrayList<MerkleBlock>()

    @Synchronized
    fun samplePmicThermodynamics(): MerkleBlock {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val rawCurrent = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0L
        // Normalize OEM fuel-gauge units (µA if >= 10,000, else mA)
        val currentMa = if (abs(rawCurrent) >= 10_000L) abs(rawCurrent) / 1000.0 else abs(rawCurrent).toDouble()

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850)?.takeIf { it > 0 } ?: 3850

        val powerWatts = (currentMa * voltageMv) / 1_000_000.0
        val nowMs = System.currentTimeMillis()
        val idx = blockIndex++

        val payload = "$idx|$nowMs|$lastBlockHash|$powerWatts|$currentMa|$voltageMv"
        val newHash = sha256(payload)

        val block = MerkleBlock(
            index = idx,
            timestampMs = nowMs,
            previousHash = lastBlockHash,
            powerWatts = powerWatts,
            currentMilliamps = currentMa,
            voltageMillivolts = voltageMv,
            eventHash = newHash
        )
        lastBlockHash = newHash
        if (ledger.size >= 64) ledger.removeAt(0)
        ledger.add(block)
        return block
    }

    /**
     * Cryptographically verifies that no historical block in the in-memory window has been mutated.
     */
    fun verifyChainIntegrity(): Boolean {
        val snapshot = ledger.toList()
        for (i in snapshot.indices) {
            val b = snapshot[i]
            if (i > 0 && b.previousHash != snapshot[i - 1].eventHash) return false
            val expectedPayload = "${b.index}|${b.timestampMs}|${b.previousHash}|${b.powerWatts}|${b.currentMilliamps}|${b.voltageMillivolts}"
            if (sha256(expectedPayload) != b.eventHash) return false
        }
        return true
    }

    fun getLedger(): List<MerkleBlock> = ledger.toList()

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
