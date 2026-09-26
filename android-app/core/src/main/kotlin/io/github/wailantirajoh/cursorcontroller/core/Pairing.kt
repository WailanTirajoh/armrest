package io.github.wailantirajoh.cursorcontroller.core

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/** Isi QR pairing: cursorctl://pair?h=&n=&a=&t=&fp= */
data class PairingUri(
    val hostId: String,
    val hostName: String,
    val address: String,
    val token: String,
    val fingerprint: String,
) {
    companion object {
        fun parse(text: String): PairingUri? {
            val uri = try {
                URI(text.trim())
            } catch (_: Exception) {
                return null
            }
            if (uri.scheme != "cursorctl" || uri.host != "pair") return null
            val params = uri.rawQuery.orEmpty().split("&").mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index) to URLDecoder.decode(part.substring(index + 1), "UTF-8")
            }.toMap()
            val address = params["a"] ?: return null
            if (!address.contains(':')) return null
            return PairingUri(
                hostId = params["h"]?.takeIf { it.isNotBlank() } ?: return null,
                hostName = params["n"]?.takeIf { it.isNotBlank() } ?: "Mac",
                address = address,
                token = params["t"]?.takeIf { it.isNotBlank() } ?: return null,
                fingerprint = params["fp"]?.takeIf { it.isNotBlank() } ?: return null,
            )
        }
    }
}

object AuthCrypto {
    /** Payload yang ditandatangani: nonce ‖ UTF-8(hostId) ‖ UTF-8(deviceId). */
    fun payload(nonce: ByteArray, hostId: String, deviceId: String): ByteArray =
        nonce + hostId.toByteArray(Charsets.UTF_8) + deviceId.toByteArray(Charsets.UTF_8)

    /** Fingerprint sertifikat: base64url tanpa padding dari SHA-256 DER. */
    fun fingerprint(der: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(der))

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Jeda percobaan sambung ulang: 0,5 → 1 → 2 → 5 detik, lalu tetap 5 detik. */
object ReconnectPolicy {
    private val delaysMs = longArrayOf(500, 1_000, 2_000, 5_000)

    fun delayMs(attempt: Int): Long = delaysMs[attempt.coerceIn(0, delaysMs.size - 1)]
}
