package dev.pcremote

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer

object WakeOnLan {

    fun parseMac(mac: String): ByteArray {
        val hex = mac.replace(Regex("[^0-9A-Fa-f]"), "")
        require(hex.length == 12) { "MAC 주소 형식이 올바르지 않습니다" }
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Magic packet: 6 x 0xFF followed by the MAC repeated 16 times. */
    fun buildPacket(mac: ByteArray): ByteArray {
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) mac.copyInto(packet, 6 + i * 6)
        return packet
    }

    /**
     * Sends the magic packet to the current subnet broadcast, 255.255.255.255 and, if set,
     * the PC's extra WOL target (DDNS + router port forward for waking from outside).
     *
     * A DDNS lookup that fails (mobile DNS hiccups, network switching) never stops the local
     * broadcast: the lookup is retried, then the last address that resolved is used, and only
     * if that is missing too is the outside packet skipped. Throws only when not a single packet
     * could be sent. Returns a short note when the outside packet had to be skipped.
     */
    suspend fun send(context: Context, pc: Pc): String? = withContext(Dispatchers.IO) {
        val packet = buildPacket(parseMac(pc.mac))
        val local = LinkedHashSet<InetAddress>()
        local += subnetBroadcasts(context)
        local += InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        val host = pc.wolTarget.trim().takeIf { it.isNotEmpty() }
        val remote = host?.let { resolve(context, it) }

        var sent = 0
        var error: Exception? = null
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                for (target in local) for (port in setOf(9, pc.wolPort)) {
                    try {
                        socket.send(DatagramPacket(packet, packet.size, target, port)); sent++
                    } catch (e: Exception) { error = e }
                }
                if (remote != null) {
                    try {
                        socket.send(DatagramPacket(packet, packet.size, remote, pc.wolPort)); sent++
                    } catch (e: Exception) { error = e }
                }
            }
        }
        if (sent == 0) throw IOException("WOL 신호를 보낼 수 없습니다 (네트워크 연결 확인)", error)
        if (host != null && remote == null) "DDNS 주소 확인 실패 · 같은 Wi-Fi로만 보냄" else null
    }

    /** What a wake attempt did, for the activity log: [note] is shown in brackets, red unless [ok]. */
    data class Report(val note: String?, val ok: Boolean)

    /**
     * Magic packets (LAN broadcast + DDNS) and, if set up, the ipTIME router's own WOL, which also
     * works when the router has forgotten the switched-off PC (see [IptimeRouter]).
     * Throws only when nothing at all could be sent.
     */
    suspend fun wake(context: Context, pc: Pc, useRouter: Boolean = true): Report {
        val packets = runCatching { send(context, pc) }
        if (!useRouter || !pc.routerWol) return Report(packets.getOrThrow(), ok = packets.getOrNull() == null)
        val router = runCatching { IptimeRouter.wake(context, pc) }
        return when {
            router.isSuccess -> Report("공유기 WOL 포함", ok = true)
            packets.isFailure -> throw IOException("공유기 WOL 실패: ${router.exceptionOrNull()?.message}")
            else -> Report("공유기 WOL 실패: ${router.exceptionOrNull()?.message}", ok = false)
        }
    }

    /** Resolves the DDNS name with a few retries, falling back to the last address it gave. */
    suspend fun resolve(context: Context, host: String): InetAddress? {
        val cache = context.getSharedPreferences("wol_dns_cache", Context.MODE_PRIVATE)
        repeat(3) { attempt ->
            try {
                val address = InetAddress.getByName(host)
                cache.edit().putString(host, address.hostAddress).apply()
                return address
            } catch (e: UnknownHostException) {
                if (attempt < 2) delay(700L * (attempt + 1))
            } catch (e: SecurityException) {
                return null
            }
        }
        // A literal IP never hits DNS, so this cannot fail again.
        return cache.getString(host, null)?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
    }

    private fun subnetBroadcasts(context: Context): List<InetAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        return props.linkAddresses.mapNotNull { link ->
            val addr = link.address as? Inet4Address ?: return@mapNotNull null
            val prefix = link.prefixLength
            val ip = ByteBuffer.wrap(addr.address).int
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            val broadcast = ip or mask.inv()
            InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(broadcast).array())
        }
    }
}
