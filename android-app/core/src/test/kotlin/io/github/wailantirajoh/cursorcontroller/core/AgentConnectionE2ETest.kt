package io.github.wailantirajoh.cursorcontroller.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Uji end-to-end ke agent Mac sungguhan (TLS + WebSocket + pairing + auth + input).
 * Jalankan lewat scripts/e2e-local.sh; tanpa CURSORCTL_E2E_PAIRING_FILE test ini dilewati.
 */
class AgentConnectionE2ETest {
    private class JvmCredentials(override val deviceName: String) : DeviceCredentials {
        private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val deviceId = UUID.randomUUID().toString()
        override val publicKeyDer: ByteArray = keyPair.public.encoded
        override fun sign(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }
    }

    private val events = LinkedBlockingQueue<String>()
    private val listener = object : AgentConnection.Listener {
        override fun onPaired(host: PairedHost) { events.add("paired") }
        override fun onAuthenticated() { events.add("authenticated") }
        override fun onEnded(failure: ClientFailure?) { events.add("ended:$failure") }
    }

    private fun next(): String? = events.poll(15, TimeUnit.SECONDS)

    @Test
    fun pairAuthenticateReconnectAndRejectAgainstRealAgent() {
        val file = System.getenv("CURSORCTL_E2E_PAIRING_FILE").orEmpty()
        assumeTrue("CURSORCTL_E2E_PAIRING_FILE tidak di-set", file.isNotBlank())
        val uri = requireNotNull(PairingUri.parse(File(file).readText())) { "QR pairing tidak valid" }
        val phone = JvmCredentials("JVM E2E")

        // 1. Pairing lewat QR, lalu autentikasi di koneksi yang sama, lalu kirim input.
        val pairing = AgentConnection(ConnectTarget.Pair(uri), phone, listener)
        assertEquals("paired", next())
        assertEquals("authenticated", next())
        assertTrue(pairing.sendInput(InputMessage.Move(120, -40)))
        assertTrue(pairing.sendInput(InputMessage.Click(MouseButton.LEFT, 1)))
        assertTrue(pairing.sendInput(InputMessage.Scroll(0, -60)))
        assertTrue(pairing.sendInput(InputMessage.Text("Halo dunia \uD83D\uDC4B\n")))
        assertTrue(pairing.sendInput(InputMessage.Key(KeyCode.RETURN)))
        assertTrue(pairing.sendInput(InputMessage.Key(KeyCode.C, KeyModifiers.COMMAND)))
        Thread.sleep(300)
        pairing.close()
        assertEquals("ended:null", next())

        // 2. Sambung ulang dengan challenge-response.
        val reconnect = AgentConnection(ConnectTarget.Auth(uri.hostId, uri.address, uri.fingerprint), phone, listener)
        assertEquals("authenticated", next())
        reconnect.close()
        assertEquals("ended:null", next())

        // 3. Fingerprint salah: koneksi dibatalkan sebelum data apa pun terkirim.
        AgentConnection(ConnectTarget.Auth(uri.hostId, uri.address, "fingerprint-salah"), phone, listener)
        assertEquals("ended:FingerprintMismatch", next())

        // 4. QR yang sama tidak bisa dipakai perangkat lain (token sekali pakai).
        AgentConnection(ConnectTarget.Pair(uri), JvmCredentials("Penyusup"), listener)
        assertEquals("ended:PairRejected(code=token_invalid)", next())

        // 5. Perangkat yang belum pernah pairing ditolak.
        AgentConnection(ConnectTarget.Auth(uri.hostId, uri.address, uri.fingerprint), JvmCredentials("Asing"), listener)
        assertEquals("ended:AuthRejected(code=unknown_device)", next())
    }
}
