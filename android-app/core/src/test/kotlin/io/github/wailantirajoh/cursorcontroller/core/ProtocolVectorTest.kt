package io.github.wailantirajoh.cursorcontroller.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            ControlMessage.Settings(1.5, 2.0),
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
