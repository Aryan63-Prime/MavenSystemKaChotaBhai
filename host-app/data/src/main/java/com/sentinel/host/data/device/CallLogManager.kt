package com.sentinel.host.data.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise Call Log Extractor.
 * Reads call history via [CallLog.Calls] with pagination, call type categorization,
 * duration formatting, and runtime permission validation.
 */
@Singleton
class CallLogManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Sentinel:CallLog"
    }

    fun hasCallLogPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getCallLogs(limit: Int = 50, offset: Int = 0): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()

        if (!hasCallLogPermission()) {
            Log.w(TAG, "READ_CALL_LOG permission not granted on host")
            result["permissionGranted"] = false
            result["error"] = "READ_CALL_LOG permission not granted on host device"
            result["total"] = 0
            result["callLogs"] = emptyList<Map<String, Any>>()
            return result
        }

        result["permissionGranted"] = true

        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.GEOCODED_LOCATION
        )

        val sortOrder = "${CallLog.Calls.DATE} DESC"
        val callLogsList = mutableListOf<Map<String, Any>>()
        var cursor: Cursor? = null

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.let { c ->
                val idIdx = c.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val geoIdx = c.getColumnIndex(CallLog.Calls.GEOCODED_LOCATION)

                var index = 0
                val targetEnd = if (limit > 0) offset + limit else Int.MAX_VALUE

                while (c.moveToNext()) {
                    if (index >= offset && index < targetEnd) {
                        val id = if (idIdx >= 0) c.getLong(idIdx) else 0L
                        val number = if (numberIdx >= 0) c.getString(numberIdx) ?: "Unknown" else "Unknown"
                        val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val typeCode = if (typeIdx >= 0) c.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                        val dateMillis = if (dateIdx >= 0) c.getLong(dateIdx) else 0L
                        val durationSec = if (durationIdx >= 0) c.getLong(durationIdx) else 0L
                        val location = if (geoIdx >= 0) c.getString(geoIdx) ?: "" else ""

                        val typeStr = when (typeCode) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                            CallLog.Calls.REJECTED_TYPE -> "Rejected"
                            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                            else -> "Unknown"
                        }

                        val durationStr = if (durationSec > 0) {
                            val mins = durationSec / 60
                            val secs = durationSec % 60
                            if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
                        } else {
                            "0s"
                        }

                        val dateStr = if (dateMillis > 0) dateFormat.format(Date(dateMillis)) else ""

                        callLogsList.add(
                            mapOf(
                                "id" to id,
                                "number" to number,
                                "name" to name.ifBlank { number },
                                "type" to typeStr,
                                "typeCode" to typeCode,
                                "timestamp" to dateMillis,
                                "dateFormatted" to dateStr,
                                "durationSeconds" to durationSec,
                                "durationFormatted" to durationStr,
                                "geocodedLocation" to location
                            )
                        )
                    }
                    index++
                    if (index >= targetEnd && limit > 0) break
                }
                result["total"] = c.count
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query call logs: ${e.message}", e)
            result["error"] = e.message ?: "Failed to read call logs"
        } finally {
            cursor?.close()
        }

        result["returned"] = callLogsList.size
        result["offset"] = offset
        result["callLogs"] = callLogsList
        return result
    }
}
