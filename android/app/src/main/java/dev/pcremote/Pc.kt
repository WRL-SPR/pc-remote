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
    /** DDNS name (e.g. xxx.iptime.org) used from outside home: WOL target and agent fallback. */
    val wolTarget: String = "",
    /** External UDP port the router forwards to the PC for WOL. */
    val wolPort: Int = 9,
    /** External TCP port the router forwards to the agent; 0 = not used. */
    val remoteAgentPort: Int = 0,
) {
    /** DDNS host for reaching the agent from outside, if configured. */
    val remoteHost: String?
        get() = wolTarget.trim().takeIf { it.isNotEmpty() && remoteAgentPort in 1..65535 }
}

class PcStore(context: Context) {
    private val prefs = context.getSharedPreferences("pcs", Context.MODE_PRIVATE)

    fun load(): List<Pc> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).toPc() }
    }

    fun save(list: List<Pc>) {
        prefs.edit().putString("list", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    /** The PC that Routines / Bixby / app-shortcut actions control. */
    var defaultId: String?
        get() = prefs.getString("defaultId", null)
        set(value) = prefs.edit().putString("defaultId", value).apply()

    fun defaultPc(list: List<Pc> = load()): Pc? = list.firstOrNull { it.id == defaultId } ?: list.firstOrNull()

    private fun Pc.toJson() = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("mac", mac)
        .put("token", token)
        .put("agentPort", agentPort)
        .put("wolTarget", wolTarget)
        .put("wolPort", wolPort)
        .put("remoteAgentPort", remoteAgentPort)

    private fun JSONObject.toPc() = Pc(
        id = getString("id"),
        name = getString("name"),
        host = getString("host"),
        mac = getString("mac"),
        token = optString("token"),
        agentPort = optInt("agentPort", 9876),
        wolTarget = optString("wolTarget"),
        wolPort = optInt("wolPort", 9),
        remoteAgentPort = optInt("remoteAgentPort", 0),
    )
}
