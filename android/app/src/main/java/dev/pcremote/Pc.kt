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
    /** ipTIME HTTPS remote-management port, reached through [wolTarget]; 0 = router WOL not used. */
    val routerPort: Int = 0,
    val routerId: String = "",
    val routerPw: String = "",
    /** SHA-256 of the router's self-signed certificate, pinned on first use. */
    val routerPin: String = "",
) {
    /** Router WOL is set up: wakes a fully shut down PC from outside (see [RouterWol]). */
    val routerEnabled: Boolean
        get() = wolTarget.isNotBlank() && routerPort in 1..65535 && routerId.isNotEmpty() && routerPw.isNotEmpty()

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

    /**
     * After a sleep / hibernate / shutdown command the app stops checking that PC for a while,
     * so its status checks can't wake a PC whose network card still reacts to ordinary traffic.
     * Shared with Routines / Bixby actions, which run outside the app.
     */
    fun quietUntil(pcId: String): Long = prefs.getLong("quiet_$pcId", 0)

    fun setQuiet(pcId: String, untilMillis: Long) {
        prefs.edit().putLong("quiet_$pcId", untilMillis).apply()
    }

    companion object {
        const val QUIET_AFTER_POWER_OFF_MS = 90_000L
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
        .put("remoteAgentPort", remoteAgentPort)
        .put("routerPort", routerPort)
        .put("routerId", routerId)
        .put("routerPw", routerPw)
        .put("routerPin", routerPin)

    /** Keeps the router certificate seen on the first successful router WOL. */
    fun setRouterPin(pcId: String, pin: String) {
        val list = load()
        if (list.none { it.id == pcId && it.routerPin != pin }) return
        save(list.map { if (it.id == pcId) it.copy(routerPin = pin) else it })
    }

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
        routerPort = optInt("routerPort", 0),
        routerId = optString("routerId"),
        routerPw = optString("routerPw"),
        routerPin = optString("routerPin"),
    )
}
