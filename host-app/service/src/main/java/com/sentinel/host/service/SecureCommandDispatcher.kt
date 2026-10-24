package com.sentinel.host.service

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

data class CommandDispatchResult(
    val commandId: String,
    val success: Boolean,
    val resultPayload: Map<String, Any?>,
    val errorMessage: String? = null
)

class SecureCommandDispatcher(
    private val myDeviceId: String,
    private val sharedHmacSecret: ByteArray? = null
) {
    private val activeHandlers = ConcurrentHashMap<String, suspend (JSONObject) -> Map<String, Any?>>()

    // Bounded LRU set of the last 512 executed sequence IDs to block replay attacks within the 300s window
    private val executedSequences: MutableSet<Long> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<Long, Boolean>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?): Boolean = size > 512
        })
    )

    fun registerHandler(command: String, handler: suspend (JSONObject) -> Map<String, Any?>) {
        activeHandlers[command] = handler
    }

    suspend fun processIncomingCommand(rawJson: String): CommandDispatchResult {
        return try {
            val root = JSONObject(rawJson)
            val data = root.optJSONObject("data") ?: return errorResult("Malformed packet")
            val targetId = data.optString("targetDeviceId", "")
            val command = data.optString("command", "")
            val sequence = root.optLong("sequence", 0L)
            val rawTimestamp = root.optLong("timestamp", 0L)
            val timestampSec = if (rawTimestamp > 100_000_000_000L) rawTimestamp / 1000L else rawTimestamp

            // 1. Strict Target Device Addressing Check
            if (targetId.isNotBlank() && targetId != myDeviceId) {
                return errorResult("Target device mismatch: $targetId != $myDeviceId")
            }

            // 2. Anti-Replay Epoch Window + Monotonic Nonce Deduplication
            val currentEpochSec = System.currentTimeMillis() / 1000L
            if (timestampSec > 0 && abs(currentEpochSec - timestampSec) > 300L) {
                return errorResult("Command rejected: Timestamp outside 300s replay window")
            }
            if (sequence > 0L && !executedSequences.add(sequence)) {
                return errorResult("Command rejected: Duplicate sequence nonce ($sequence) replayed")
            }

            // 3. Optional Constant-Time HMAC-SHA256 Envelope Verification
            val providedHmacHex = root.optString("hmac", "")
            if (sharedHmacSecret != null && sharedHmacSecret.isNotEmpty() && providedHmacHex.isNotBlank()) {
                val canonical = "$sequence|$timestampSec|$targetId|$command"
                val mac = Mac.getInstance("HmacSHA256").apply {
                    init(SecretKeySpec(sharedHmacSecret, "HmacSHA256"))
                }
                val expectedBytes = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
                val providedBytes = providedHmacHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
                if (!MessageDigest.isEqual(expectedBytes, providedBytes)) {
                    return errorResult("Command rejected: Invalid HMAC-SHA256 signature")
                }
            }

            val handler = activeHandlers[command]
                ?: return errorResult("Unregistered command handler: $command")

            val params = data.optJSONObject("params") ?: JSONObject()
            val output = handler.invoke(params)

            CommandDispatchResult(commandId = command, success = true, resultPayload = output)
        } catch (e: Exception) {
            errorResult("Command dispatch failure: ${e.message}")
        }
    }

    private fun errorResult(msg: String) = CommandDispatchResult("UNKNOWN", false, emptyMap(), msg)
}
