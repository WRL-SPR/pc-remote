package dev.pcremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Pc(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** LAN IP of the PC, used for status checks and power commands. */
    val host: String,
    val mac: String,
    val token: String,
    val agentPort: Int = 9876,
    /** Extra WOL target, e.g. an ipTIME DDNS name (xxx.iptime.org) for waking from outside. */
    val wolTarget: String = "",
    val wolPort: Int = 9,
)

class PcStore(context: Context) {
    private val prefs = context.getSharedPreferences("pcs", Context.MODE_PRIVATE)

    fun load(): List<Pc> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).toPc() }
    }

    fun save(list: List<Pc>) {
        prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    private fun Pc.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("mac", mac)
        .put("token", token)
        .put("agentPort", agentPort)
        .put("wolTarget", wolTarget)
        .put("wolPort", wolPort)

    private fun JSONObject.toPc() = Pc(
        id = getString("id"),
        name = getString("name"),
        host = getString("host"),
        mac = getString("mac"),
        token = optString("token"),
        agentPort = optInt("agentPort", 9876),
        wolTarget = optString("wolTarget"),
        wolPort = optInt("wolPort", 9),
    )
}
