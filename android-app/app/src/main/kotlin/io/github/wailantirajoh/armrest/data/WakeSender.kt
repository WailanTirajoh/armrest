package io.github.wailantirajoh.armrest.data

import io.github.wailantirajoh.armrest.core.WakeOnLan
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Mengirim magic packet Wake-on-LAN lewat UDP. Komputer yang mati tidak menjalankan agent, jadi HP sendiri yang
 * membangunkannya: broadcast ke seluruh jaringan lokal, plus alamat terakhir yang diketahui. Panggil di luar main thread.
 */
object WakeSender {
    /** false kalau tidak ada satu paket pun yang terkirim (mis. HP tidak tersambung ke jaringan). */
    fun send(mac: String, lastAddress: String): Boolean {
        val packet = WakeOnLan.magicPacket(mac)
        val lastHost = lastAddress.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        var sent = false
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in listOf(WakeOnLan.BROADCAST, lastHost).filter { it.isNotBlank() }.distinct()) {
                    try {
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), WakeOnLan.PORT))
                        sent = true
                    } catch (_: IOException) {
                        // Satu tujuan gagal (mis. alamat lama tidak bisa dijangkau); coba tujuan berikutnya.
                    }
                }
            }
        } catch (_: IOException) {
            return false
        }
        return sent
    }
}
