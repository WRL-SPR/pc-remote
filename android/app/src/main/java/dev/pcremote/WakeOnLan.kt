package dev.pcremote

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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
     */
    suspend fun send(context: Context, pc: Pc) = withContext(Dispatchers.IO) {
        val packet = buildPacket(parseMac(pc.mac))
        val local = LinkedHashSet<InetAddress>()
        local += subnetBroadcasts(context)
        local += InetAddress.getByName("255.255.255.255")
        val remote = pc.wolTarget.trim().takeIf { it.isNotEmpty() }?.let { InetAddress.getByName(it) }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                for (target in local) for (port in setOf(9, pc.wolPort)) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, target, port)) }
                }
                if (remote != null) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, remote, pc.wolPort)) }
                }
            }
        }
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
