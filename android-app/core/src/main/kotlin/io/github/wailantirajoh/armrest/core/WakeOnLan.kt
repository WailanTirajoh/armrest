package io.github.wailantirajoh.armrest.core

/** Magic packet Wake-on-LAN (protocol/PROTOCOL.md, bagian "Wake-on-LAN"). */
object WakeOnLan {
    const val PORT = 9
    const val BROADCAST = "255.255.255.255"

    private val pattern = Regex("^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$")

    /** `A4-83-E7-12-34-56` → `a4:83:e7:12:34:56`. null kalau bukan alamat hardware 6 byte atau semuanya 0. */
    fun normalize(mac: String): String? {
        val text = mac.trim().lowercase()
        if (!pattern.matches(text)) return null
        val normalized = text.replace('-', ':')
        return normalized.takeIf { it != "00:00:00:00:00:00" }
    }

    /** 6 byte 0xFF, lalu alamat hardware 16 kali: 102 byte. */
    fun magicPacket(mac: String): ByteArray {
        val address = requireNotNull(normalize(mac)) { "bad mac: $mac" }.split(':').map { it.toInt(16).toByte() }
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { address[it % 6] }
    }
}
