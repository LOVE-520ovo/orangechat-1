package me.rerere.rikkahub.data.wake

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class WakeLogConfig(
    val supabaseUrl: String,
    val serviceKey: String,
    val table: String = "kli_wake_log",
)

data class WakeLogEntry(
    val owner: String,
    val firedAtMs: Long,
    val cycleId: Long,
    val lambda: Double,
    val theta: Double,
    val hazardAtFire: Double,
    val drive: Double,
    val tone: Double,
    val drift: Double,
    val decision: String,
    val thought: String? = null,
    val replyText: String? = null,
    val toolCalls: String? = null,
    val durationMs: Int? = null,
    val model: String? = null,
    val policyVersion: String = "v1.0-dev",
    val source: String = "spontaneous",
)

object WakeLogClient {
    private const val TAG = "KliWakeLog"

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun insert(cfg: WakeLogConfig, e: WakeLogEntry): Boolean = withContext(Dispatchers.IO) {
        if (cfg.supabaseUrl.isBlank() || cfg.serviceKey.isBlank()) return@withContext false
        var conn: HttpURLConnection? = null
        try {
            val body = buildJsonObject {
                put("owner", e.owner)
                put("fired_at", iso.format(Date(e.firedAtMs)))
                put("policy_version", e.policyVersion)
                put("cycle_id", e.cycleId)
                put("source", e.source)
                put("lambda", e.lambda)
                put("theta", e.theta)
                put("hazard_at_fire", e.hazardAtFire)
                put("drive", e.drive)
                put("tone", e.tone)
                put("drift", e.drift)
                put("decision", e.decision)
                e.thought?.let { put("thought", it) }
                e.replyText?.let { put("reply_text", it) }
                e.toolCalls?.let { put("tool_calls", Json.parseToJsonElement(it)) }
                e.durationMs?.let { put("duration_ms", it) }
                e.model?.let { put("model", it) }
            }.toString()

            val url = URL("${cfg.supabaseUrl.trimEnd('/')}/rest/v1/${cfg.table}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("apikey", cfg.serviceKey)
                setRequestProperty("Authorization", "Bearer ${cfg.serviceKey}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=minimal")
            }
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                Log.w(TAG, "insert failed HTTP $code: ${err.take(300)}")
                return@withContext false
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "insert failed: ${t.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
