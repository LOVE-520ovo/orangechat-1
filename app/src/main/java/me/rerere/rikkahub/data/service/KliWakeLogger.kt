package me.rerere.rikkahub.data.service

import android.content.Context
import android.util.Log
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting
import me.rerere.rikkahub.data.wake.WakeLogClient
import me.rerere.rikkahub.data.wake.WakeLogConfig
import me.rerere.rikkahub.data.wake.WakeLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object KliWakeLogger {
    private const val TAG = "KliWakeLog"
    private const val PREFS = "kli_wake_prefs"
    private const val KEY_PENDING = "kli_pending"

    data class Pending(
        val cycleId: Long,
        val lambda: Double,
        val theta: Double,
        val drive: Double,
        val tone: Double,
        val drift: Double,
        val hazard: Double,
    )

    fun savePending(context: Context, p: Pending) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(
            KEY_PENDING,
            listOf(p.cycleId, p.lambda, p.theta, p.drive, p.tone, p.drift, p.hazard).joinToString("|")
        ).apply()
    }

    fun readPending(context: Context): Pending? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING, null) ?: return null
        val a = raw.split("|")
        if (a.size != 7) return null
        return Pending(
            cycleId = a[0].toLongOrNull() ?: 0L,
            lambda = a[1].toDoubleOrNull() ?: 0.0,
            theta = a[2].toDoubleOrNull() ?: 0.0,
            drive = a[3].toDoubleOrNull() ?: 0.5,
            tone = a[4].toDoubleOrNull() ?: 0.5,
            drift = a[5].toDoubleOrNull() ?: 0.0,
            hazard = a[6].toDoubleOrNull() ?: 0.0,
        )
    }

    fun passThought(raw: String): String {
        val m = Regex("""\[PASS(?::([^\]]*))?\]""").find(raw) ?: return ""
        return m.groupValues.getOrNull(1)?.trim().orEmpty()
    }

    suspend fun log(
        context: Context,
        setting: ProactiveMessageSetting,
        rawText: String,
        replyText: String?,
        durationMs: Int,
        decision: String,
    ) {
        val cfg = WakeLogConfig(setting.supabaseUrl, setting.supabaseKey)
        if (cfg.supabaseUrl.isBlank() || cfg.serviceKey.isBlank()) {
            Log.w(TAG, "wake log skipped: supabase not configured")
            return
        }
        val p = readPending(context)
        val entry = WakeLogEntry(
            owner = setting.wakeLogOwner,
            firedAtMs = System.currentTimeMillis(),
            cycleId = p?.cycleId ?: 0L,
            lambda = p?.lambda ?: 0.0,
            theta = p?.theta ?: 0.0,
            hazardAtFire = p?.hazard ?: 0.0,
            drive = p?.drive ?: 0.5,
            tone = p?.tone ?: 0.5,
            drift = p?.drift ?: 0.0,
            decision = decision,
            thought = passThought(rawText).ifBlank { null },
            replyText = replyText?.take(2000),
            durationMs = durationMs,
        )
        runCatching { WakeLogClient.insert(cfg, entry) }
            .onFailure { Log.w(TAG, "insert failed: " + it.message) }
    }

    suspend fun historyFor(context: Context, setting: ProactiveMessageSetting): String = withContext(Dispatchers.IO) {
        if (setting.supabaseUrl.isBlank() || setting.supabaseKey.isBlank()) return@withContext ""
        val owner = URLEncoder.encode(setting.wakeLogOwner, "UTF-8")
        val urlStr = setting.supabaseUrl.trimEnd(/) +
            "/rest/v1/kli_wake_log?select=fired_at,decision,thought,reply_text&owner=eq." + owner +
            "&order=fired_at.desc&limit=3"
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("apikey", setting.supabaseKey)
                setRequestProperty("Authorization", "Bearer " + setting.supabaseKey)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return@withContext ""
            val text = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val arr = JSONArray(text)
            if (arr.length() == 0) return@withContext ""
            val sb = StringBuilder()
            sb.appendLine("[你自己的记录]")
            val now = System.currentTimeMillis()
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val labels = arrayOf("上一次睁眼", "上上上次", "上上上上次")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val firedAt = parseIso(o.optString("fired_at"))
                val mins = if (firedAt > 0) ((now - firedAt) / 60000L).toInt() else -1
                val whenText = if (firedAt > 0) fmt.format(Date(firedAt)) else "未知"
                val agoText = when {
                    mins < 0 -> ""
                    mins < 60 -> "" + mins + " 分钟前"
                    mins < 1440 -> "" + (mins / 60) + " 小时 " + (mins % 60) + " 分钟前"
                    else -> "" + (mins / 1440) + " 天前"
                }
                val decision = o.optString("decision")
                val thought = o.optString("thought")
                val reply = o.optString("reply_text")
                sb.append(if (i < labels.size) labels[i] else "更早")
                sb.append("：")
                if (agoText.isNotEmpty()) sb.append(agoText).append("（").append(whenText).append("）")
                sb.appendLine()
                when (decision) {
                    "silent" -> {
                        sb.append("    我当时什么都没说。")
                        if (thought.isNotBlank()) sb.append("念头是「").append(thought).append("」")
                        sb.appendLine()
                    }
                    "spoke" -> {
                        sb.append("    我当时说了一句「").append(reply.take(60)).append("」")
                        sb.appendLine()
                    }
                    else -> {
                        sb.append("    我当时醒了一下，做了些别的。")
                        sb.appendLine()
                    }
                }
            }
            sb.appendLine("这不是别人告诉你的，是你自己上一次醒来留下的。别装作记得更多，也别装作不记得。")
            sb.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "history fetch failed: " + t.message)
            ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseIso(s: String): Long {
        if (s.isBlank()) return 0L
        return runCatching {
            val f = SimpleDateFormat("yyyy-MM-ddTHH:mm:ss.SSSZ", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }
}
