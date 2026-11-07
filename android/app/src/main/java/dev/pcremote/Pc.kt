package dev.pcremote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    /** ipTIME remote management port, for the router's own WOL; 0 = not used. */
    val routerPort: Int = 0,
    val routerUser: String = "",
    /** Kept encrypted with an Android Keystore key while stored (see [PcStore]). */
    val routerPassword: String = "",
) {
    /** DDNS host for reaching the agent from outside, if configured. */
    val remoteHost: String?
        get() = wolTarget.trim().takeIf { it.isNotEmpty() && remoteAgentPort in 1..65535 }

    /** Whether the router's own WOL (through its remote management) is set up. */
    val routerWol: Boolean
        get() = wolTarget.isNotBlank() && routerPort in 1..65535 && routerUser.isNotBlank() && routerPassword.isNotEmpty()

    override fun toString() = "Pc(id=$id, name=$name, host=$host)"  // keep secrets out of logs
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
        .put("routerUser", routerUser)
        .put("routerPassword", SecretBox.seal(routerPassword))

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
        routerUser = optString("routerUser"),
        routerPassword = SecretBox.open(optString("routerPassword")),
    )
}

/** Encrypts small secrets with an AES key that never leaves the Android Keystore. */
private object SecretBox {
    private const val ALIAS = "pcremote_secrets"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    /** "" stays "". Otherwise base64(iv + ciphertext). */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Returns "" if it can't be decrypted (e.g. the key was lost), so the user re-enters it. */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12))
        }.getOrDefault("")
    }
}
