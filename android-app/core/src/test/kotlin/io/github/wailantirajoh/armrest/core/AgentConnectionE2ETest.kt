package io.github.wailantirajoh.armrest.core

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
 * Jalankan lewat scripts/e2e-local.sh; tanpa ARMREST_E2E_PAIRING_FILE test ini dilewati.
 * Agent uji membaca status fokus kolom teks dari ARMREST_E2E_FOCUS_FILE, bukan dari Accessibility, dan
 * mengirim pola uji sebagai layar (lewat encoder H.264 sungguhan), bukan layar asli. Agent tanpa encoder (host
 * headless C# di luar Windows) dijalankan dengan ARMREST_E2E_EXPECT_SCREEN=0. Kontraknya: protocol/E2E.md.
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
    private val packets = LinkedBlockingQueue<ScreenPacket>()
    // Terpisah dari `events`: posisi kursor datang terus selama layar tampil, di sela event lain.
    private val cursors = LinkedBlockingQueue<Pair<Double, Double>>()
    private val listener = object : AgentConnection.Listener {
        override fun onPaired(host: PairedHost) { events.add("paired") }
        override fun onAuthenticated() { events.add("authenticated") }
        override fun onTextFocus(focused: Boolean) { events.add("focus:$focused") }
        override fun onScreenStatus(state: String) { events.add("screen:$state") }
        override fun onScreenCursor(x: Double, y: Double) { cursors.add(x to y) }
        override fun onVolumeStatus(level: Double?, muted: Boolean) { events.add("volume:$level:$muted") }
        override fun onScreenPacket(packet: ScreenPacket) { packets.add(packet) }
        override fun onEnded(failure: ClientFailure?) { events.add("ended:$failure") }
    }

    private fun next(): String? = events.poll(15, TimeUnit.SECONDS)

    private fun nextPacket(): ScreenPacket? = packets.poll(15, TimeUnit.SECONDS)

    private fun nalTypes(annexB: ByteArray) = AnnexB.split(annexB).map(AnnexB::nalType)

    @Test
    fun pairAuthenticateReconnectAndRejectAgainstRealAgent() {
        val file = System.getenv("ARMREST_E2E_PAIRING_FILE").orEmpty()
        assumeTrue("ARMREST_E2E_PAIRING_FILE tidak di-set", file.isNotBlank())
        val uri = requireNotNull(PairingUri.parse(File(file).readText())) { "QR pairing tidak valid" }
        val focusFile = File(System.getenv("ARMREST_E2E_FOCUS_FILE").orEmpty())
        assumeTrue("ARMREST_E2E_FOCUS_FILE tidak di-set", focusFile.path.isNotBlank())
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
        // Tombol media: agent uji hanya mencatat, tidak pernah memutar atau menjeda apa pun.
        assertTrue(ProtocolConstants.FEATURE_MEDIA in pairing.features)
        listOf(KeyCode.PLAY_PAUSE, KeyCode.NEXT_TRACK, KeyCode.PREVIOUS_TRACK).forEach { assertTrue(pairing.sendInput(InputMessage.Key(it))) }

        // Fokus kolom teks: status awal dikirim begitu diminta, lalu setiap kali berubah.
        focusFile.writeText("0")
        pairing.sendSettings(1.5, 2.0, focusUpdates = true)
        assertEquals("focus:false", next())
        focusFile.writeText("1")
        assertEquals("focus:true", next())
        focusFile.writeText("0")
        assertEquals("focus:false", next())

        // Volume: status saat ini begitu diminta, lalu setiap kali berubah. Agent uji memakai volume tiruan mulai 0,5.
        assertTrue(ProtocolConstants.FEATURE_VOLUME in pairing.features)
        pairing.sendSettings(1.5, 2.0, focusUpdates = true, volumeUpdates = true)
        assertEquals("volume:0.5:false", next())
        pairing.changeVolume(1)
        assertEquals("volume:0.5625:false", next())
        pairing.setMuted(true)
        assertEquals("volume:0.5625:true", next())
        // Mengatur level juga menyalakan suara lagi, seperti tombol volume.
        pairing.setVolume(0.25)
        assertEquals("volume:0.25:false", next())

        // Layar: config (SPS + PPS) lalu keyframe IDR, lalu frame terus mengalir karena setiap frame dikonfirmasi.
        val expectScreen = System.getenv("ARMREST_E2E_EXPECT_SCREEN") != "0"
        assertEquals(expectScreen, ProtocolConstants.FEATURE_SCREEN in pairing.features)
        if (expectScreen) checkScreen(pairing)
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

    private fun checkScreen(connection: AgentConnection) {
        connection.requestScreen(1920, 1080)
        assertEquals("screen:streaming", next())
        val config = nextPacket() as ScreenPacket.Config
        assertEquals(1440 to 900, config.width to config.height)
        assertEquals(listOf(AnnexB.NAL_SPS, AnnexB.NAL_PPS), nalTypes(config.parameterSets))
        val first = nextPacket() as ScreenPacket.Frame
        assertTrue(first.keyframe && AnnexB.NAL_IDR in nalTypes(first.data))
        var seq = first.seq
        cursors.clear()
        repeat(20) {
            var packet = nextPacket()
            // Encoder dengan keyframe berkala (mis. Media Foundation) mengirim screen_config sebelum setiap keyframe.
            val afterConfig = packet is ScreenPacket.Config
            if (afterConfig) packet = nextPacket()
            val frame = packet as ScreenPacket.Frame
            assertTrue(frame.seq > seq && (frame.keyframe || !afterConfig))
            seq = frame.seq
        }
        // Posisi kursor ikut dikirim karena HP memintanya: pola uji menaruh kursor tiruan di tengah balok (y = 0,725).
        val cursor = cursors.poll(5, TimeUnit.SECONDS)
        assertTrue("posisi kursor tidak datang", cursor != null && cursor.first in 0.0..1.0 && cursor.second == 0.725)
        // Permintaan ulang (mis. decoder HP dibuat ulang) menghasilkan config dan keyframe baru.
        connection.requestScreen(1920, 1080)
        var packet = nextPacket()
        repeat(10) { if (packet !is ScreenPacket.Config) packet = nextPacket() }
        assertTrue(packet is ScreenPacket.Config)
        assertTrue((nextPacket() as ScreenPacket.Frame).keyframe)
        connection.stopScreen()
    }
}
