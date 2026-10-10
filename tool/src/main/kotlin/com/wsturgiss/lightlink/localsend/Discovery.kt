package com.wsturgiss.lightlink.localsend

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * LocalSend's multicast discovery (spec §3.1), on the Wi-Fi interface.
 *
 * An announcement is the phone saying "I'm here" to 224.0.0.167:53317; every
 * LocalSend device that hears it registers with the phone's server, which is
 * how both sides end up listing each other. Hearing announcements works the
 * other way round: [onMessage] gets them, and the caller registers back.
 *
 * Sending needs no special permission. Hearing may not work: Android can
 * filter multicast without a `MulticastLock`, whose permission is not on the
 * Light SDK allowlist. Announcing alone still finds anything that can hear
 * (computers, LocalSend on phones); the subnet scan covers the rest.
 */
class Discovery(
    private val announcement: () -> DeviceInfo,
    private val onMessage: (ip: String, message: DeviceInfo) -> Unit,
) {
    private val group = InetAddress.getByName(LocalSend.MULTICAST_GROUP)
    private var listening: MulticastSocket? = null
    private var sending: MulticastSocket? = null
    private var closed = false

    @Synchronized
    fun start() {
        if (closed) return
        val wifi = Lan.address()?.networkInterface
        sending = MulticastSocket().apply { wifi?.let { networkInterface = it } }
        try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LocalSend.DEFAULT_PORT))
                joinGroup(InetSocketAddress(group, 0), wifi)
            }
            listening = socket
            Thread({ receiveLoop(socket) }, "localsend-multicast").apply { isDaemon = true }.start()
        } catch (e: IOException) {
            // Announcing still works; only hearing others is lost.
            Log.w(TAG, "cannot listen for announcements", e)
        }
    }

    /** Sends one announcement. */
    fun announce() {
        val socket = synchronized(this) { sending } ?: return
        val message = announcement().copy(announce = true)
        val bytes = LocalSend.json.encodeToString(DeviceInfo.serializer(), message).toByteArray(Charsets.UTF_8)
        try {
            socket.send(DatagramPacket(bytes, bytes.size, group, LocalSend.DEFAULT_PORT))
        } catch (e: IOException) {
            Log.w(TAG, "announce failed", e)
        }
    }

    @Synchronized
    fun stop() {
        closed = true
        listening?.close()
        sending?.close()
        listening = null
        sending = null
    }

    private fun receiveLoop(socket: MulticastSocket) {
        val buffer = ByteArray(64 * 1024)
        val own = announcement().fingerprint
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                if (!socket.isClosed) Log.w(TAG, "receive failed", e)
                return
            }
            val message = runCatching {
                LocalSend.json.decodeFromString(DeviceInfo.serializer(), String(packet.data, 0, packet.length, Charsets.UTF_8))
            }.getOrNull() ?: continue
            // Our own announcements come back to us.
            if (message.fingerprint.equals(own, ignoreCase = true)) continue
            val ip = packet.address.hostAddress ?: continue
            Log.d(TAG, "heard ${message.alias} at $ip")
            onMessage(ip, message)
        }
    }

    private companion object {
        const val TAG = "Discovery"
    }
}

/** The phone's place on the LAN. */
object Lan {

    data class Address(val ip: String, val prefix: Int, val networkInterface: NetworkInterface)

    /** This phone's IPv4 address on Wi-Fi (or whatever LAN it has), or null when off it. */
    fun address(): Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            // Wi-Fi first; mobile data interfaces (rmnet…) never reach the LAN.
            .sortedByDescending { it.name.startsWith("wlan") }
            .firstNotNullOfOrNull { nif ->
                nif.interfaceAddresses
                    .firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
                    ?.let { Address(it.address.hostAddress!!, it.networkPrefixLength.toInt(), nif) }
            }
    }.getOrNull()

    /**
     * Every other host on [ip]'s subnet, for the scan. Anything wider than a /24
     * is cut to the /24 around the phone, as LocalSend does — a /16 is 65k probes.
     */
    fun subnetHosts(ip: String, prefix: Int): List<String> {
        val octets = ip.split('.').map { it.toInt() }
        require(octets.size == 4)
        val self = octets.fold(0L) { acc, o -> (acc shl 8) or o.toLong() }
        val bits = prefix.coerceIn(24, 30)
        val size = 1L shl (32 - bits)
        val network = self and (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (network + 1 until network + size - 1)
            .filter { it != self }
            .map { n -> (3 downTo 0).joinToString(".") { ((n shr (it * 8)) and 0xFF).toString() } }
    }
}
