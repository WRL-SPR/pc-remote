package dev.pcremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One line of a PC's recent-activity history ("절전 명령 보냄", "원격 접속 가능 확인 · 52초 걸림"). */
data class Activity(val time: Long, val text: String, val ok: Boolean = true)

/** Keeps the last few activities per PC, including ones started from Routines / Bixby. */
class ActivityStore(context: Context) {
    private val prefs = context.getSharedPreferences("activity", Context.MODE_PRIVATE)

    fun list(pcId: String): List<Activity> {
        val arr = JSONArray(prefs.getString(pcId, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Activity(o.getLong("t"), o.getString("text"), o.optBoolean("ok", true))
        }
    }

    fun add(pcId: String, text: String, ok: Boolean = true): List<Activity> {
        val list = (listOf(Activity(System.currentTimeMillis(), text, ok)) + list(pcId)).take(MAX)
        val arr = JSONArray(list.map { JSONObject().put("t", it.time).put("text", it.text).put("ok", it.ok) })
        prefs.edit().putString(pcId, arr.toString()).apply()
        return list
    }

    fun clear(pcId: String) {
        prefs.edit().remove(pcId).apply()
    }

    private companion object {
        const val MAX = 30
    }
}

fun formatActivityTime(time: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(time).atZone(zone)
    val today = LocalDate.now(zone)
    val hm = "%02d:%02d".format(t.hour, t.minute)
    return when (t.toLocalDate()) {
        today -> "오늘 $hm"
        today.minusDays(1) -> "어제 $hm"
        else -> "${t.monthValue}월 ${t.dayOfMonth}일 $hm"
    }
}

fun formatDuration(seconds: Long): String =
    if (seconds < 60) "${seconds}초" else "${seconds / 60}분 ${seconds % 60}초"
