package io.github.wailantirajoh.cursorcontroller.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/** Test vector bersama di protocol/vectors (juga dipakai test Swift). */
private fun vector(name: String) = JSONObject(File("../../protocol/vectors/$name").readText())

private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

class ProtocolVectorTest {
    @Test
    fun inputVectorsRoundTrip() {
        val cases = vector("input.json").getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val expected: InputMessage = when (c.getString("message")) {
                "move" -> InputMessage.Move(c.getInt("dx").toShort(), c.getInt("dy").toShort())
                "scroll" -> InputMessage.Scroll(c.getInt("dx").toShort(), c.getInt("dy").toShort())
                "button" -> InputMessage.Button(MouseButton.fromCode(c.getInt("button"))!!, c.getBoolean("down"))
                "click" -> InputMessage.Click(MouseButton.fromCode(c.getInt("button"))!!, c.getInt("count"))
                "text" -> InputMessage.Text(c.getString("text"))
                "key" -> InputMessage.Key(KeyCode.fromCode(c.getInt("key"))!!, c.getInt("modifiers"))
                else -> error("tipe tidak dikenal: $c")
            }
            assertEquals(expected, InputMessage.decode(hex(c.getString("hex"))))
            assertEquals(c.getString("hex"), expected.encode().hex())
        }
    }

    @Test
    fun invalidInputFramesAreRejected() {
        val invalid = vector("input.json").getJSONArray("invalid_hex")
        for (i in 0 until invalid.length()) {
            assertNull(invalid.getString(i), InputMessage.decode(hex(invalid.getString(i))))
        }
    }

    @Test
    fun authVectorsVerifyLikeOpenSsl() {
        val root = vector("auth.json")
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val payload = AuthCrypto.payload(hex(c.getString("nonce_hex")), c.getString("hostId"), c.getString("deviceId"))
            assertEquals(c.getString("payload_hex"), payload.hex())

            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(AuthCrypto.fromBase64(c.getString("publicKey_b64"))))
            val verifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(publicKey)
                update(payload)
            }
            assertEquals(c.getString("name"), c.getBoolean("valid"), verifier.verify(AuthCrypto.fromBase64(c.getString("signature_b64"))))
        }
        val fingerprint = root.getJSONObject("fingerprint")
        assertEquals(fingerprint.getString("sha256_b64url"), AuthCrypto.fingerprint(hex(fingerprint.getString("input_hex"))))
    }

    @Test
    fun screenVectorsParse() {
        val cases = vector("screen.json").getJSONArray("cases")
        assertTrue(cases.length() >= 3)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val packet = ScreenPacket.parse(hex(c.getString("hex")))
            when (c.getString("packet")) {
                "config" -> assertEquals(
                    c.getString("name"),
                    ScreenPacket.Config(c.getInt("width"), c.getInt("height"), hex(c.getString("parameterSets_hex"))),
                    packet,
                )
                "frame" -> {
                    val frame = packet as ScreenPacket.Frame
                    assertEquals(c.getLong("seq"), frame.seq)
                    assertEquals(c.getBoolean("keyframe"), frame.keyframe)
                    assertEquals(c.getString("data_hex"), frame.data.hex())
                }
                else -> fail("paket tidak dikenal: $c")
            }
        }
    }

    @Test
    fun invalidScreenPacketsAreRejected() {
        val invalid = vector("screen.json").getJSONArray("invalid_hex")
        for (i in 0 until invalid.length()) {
            assertNull(invalid.getString(i), ScreenPacket.parse(hex(invalid.getString(i))))
        }
    }

    @Test
    fun annexBSplitsNalUnitsWithBothStartCodes() {
        val vector = vector("screen.json").getJSONObject("annexb")
        val units = vector.getJSONArray("units_hex").let { a -> List(a.length()) { a.getString(it) } }
        assertEquals(units, AnnexB.split(hex(vector.getString("annexb_hex"))).map { it.hex() })
        assertEquals(units, AnnexB.split(hex(vector.getString("mixedStartCodes_hex"))).map { it.hex() })
        assertEquals(AnnexB.NAL_IDR, AnnexB.nalType(hex(units[0])))
    }

    @Test
    fun controlMessagesRoundTrip() {
        val messages = listOf(
            ControlMessage.Hello(1, "d", ControlMessage.MODE_PAIR),
            ControlMessage.PairRequest("tok", "Pixel 8", "AAA="),
            ControlMessage.PairResult(true, "h", "MacBook", null),
            ControlMessage.PairResult(false, null, null, "denied"),
            ControlMessage.Challenge("bm9uY2U="),
            ControlMessage.Auth("c2ln"),
            ControlMessage.AuthResult(true, null),
            ControlMessage.AuthResult(false, "bad_sig"),
            ControlMessage.AuthResult(true, null, listOf("focus", "screen")),
            ControlMessage.Settings(1.5, 2.0),
            ControlMessage.Settings(1.5, 2.0, focusUpdates = true),
            ControlMessage.Focus(true),
            ControlMessage.Focus(false),
            ControlMessage.Screen(true, 2712, 1220),
            ControlMessage.Screen(false),
            ControlMessage.ScreenAck(4_294_967_295),
            ControlMessage.ScreenStatus(ControlMessage.SCREEN_DENIED),
            ControlMessage.Ping(1_790_000_000_000),
            ControlMessage.Pong(42),
            ControlMessage.Error("bad_message"),
        )
        for (message in messages) {
            assertEquals(message, ControlMessage.parse(message.toJson()))
        }
    }

    @Test
    fun controlMessageParsesJsonFromMac() {
        // Bentuk persis yang dikirim agent (JSONSerialization dengan sortedKeys).
        assertEquals(
            ControlMessage.PairResult(true, "host-1", "Mac Test", null),
            ControlMessage.parse("""{"hostId":"host-1","hostName":"Mac Test","ok":true,"t":"pair_result"}"""),
        )
        assertEquals(ControlMessage.Focus(true), ControlMessage.parse("""{"t":"focus","text":true}"""))
        assertEquals(
            ControlMessage.AuthResult(true, null, listOf("focus", "screen")),
            ControlMessage.parse("""{"features":["focus","screen"],"ok":true,"t":"auth_result"}"""),
        )
        assertEquals(ControlMessage.AuthResult(true, null), ControlMessage.parse("""{"ok":true,"t":"auth_result"}"""))
        assertEquals(ControlMessage.ScreenStatus("streaming"), ControlMessage.parse("""{"state":"streaming","t":"screen_status"}"""))
        assertNull(ControlMessage.parse("""{"t":"nope"}"""))
        assertNull(ControlMessage.parse("bukan json"))
    }

    @Test
    fun parsesPairingUriProducedByMac() {
        // Sama dengan string di test Swift pairingURIEncodesEveryField.
        val uri = PairingUri.parse("cursorctl://pair?h=h-1&n=Mac%20Wailan%2BKantor&a=192.168.1.20%3A47810&t=a_b-c&fp=f_p")
        assertEquals(PairingUri("h-1", "Mac Wailan+Kantor", "192.168.1.20:47810", "a_b-c", "f_p"), uri)
        assertNull(PairingUri.parse("https://example.com/pair?h=1"))
        assertNull(PairingUri.parse("cursorctl://pair?h=h-1&a=1.2.3.4:1&fp=x"))
    }

    @Test
    fun deltaAccumulatorKeepsFractionsAndClampsToInt16() {
        val acc = DeltaAccumulator()
        acc.add(0.05f, 0f)
        assertNull(acc.drain())
        acc.add(0.07f, -0.26f)
        assertEquals(1.toShort() to (-2).toShort(), acc.drain())
        acc.add(5000f, 0f)
        assertEquals(Short.MAX_VALUE to 0.toShort(), acc.drain())
        assertEquals((50_000 - Short.MAX_VALUE).toShort() to 0.toShort(), acc.drain())
    }

    @Test
    fun reconnectBackoffMatchesSpec() {
        assertArrayEquals(longArrayOf(500, 1_000, 2_000, 5_000, 5_000), LongArray(5) { ReconnectPolicy.delayMs(it) })
    }
}
