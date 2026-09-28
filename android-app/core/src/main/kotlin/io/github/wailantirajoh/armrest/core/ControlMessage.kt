package io.github.wailantirajoh.armrest.core

import org.json.JSONArray
import org.json.JSONObject

/** Pesan kontrol JSON (protocol/PROTOCOL.md). */
sealed interface ControlMessage {
    data class Hello(val version: Int, val deviceId: String, val mode: String) : ControlMessage
    data class PairRequest(val token: String, val deviceName: String, val publicKey: String) : ControlMessage
    data class PairResult(val ok: Boolean, val hostId: String?, val hostName: String?, val error: String?) : ControlMessage
    data class Challenge(val nonce: String) : ControlMessage
    data class Auth(val sig: String) : ControlMessage
    /**
     * `features`: fitur opsional agent, mis. [ProtocolConstants.FEATURE_SCREEN]. `platform`: mis.
     * [ProtocolConstants.PLATFORM_WINDOWS]. Agent lama tidak mengirim keduanya. `mac`: alamat hardware komputer
     * untuk Wake-on-LAN, mis. `a4:83:e7:12:34:56`.
     */
    data class AuthResult(
        val ok: Boolean,
        val error: String?,
        val features: List<String> = emptyList(),
        val platform: String? = null,
        val mac: String? = null,
    ) : ControlMessage
    /**
     * `focusUpdates`: minta Mac mengirim [Focus] setiap kali fokus kolom teks berubah. `volumeUpdates`: minta
     * [VolumeStatus] setiap kali volume berubah.
     */
    data class Settings(
        val sensitivity: Double,
        val scrollSpeed: Double,
        val focusUpdates: Boolean = false,
        val volumeUpdates: Boolean = false,
    ) : ControlMessage
    /** Apakah kolom teks sedang fokus di Mac. */
    data class Focus(val text: Boolean) : ControlMessage
    /**
     * Mulai (`on`) atau berhenti melihat layar Mac. Diminta lagi saat aktif = minta keyframe. Ukuran dalam piksel.
     * `cursor`: minta posisi kursor ([ScreenCursor]) supaya tampilan yang di-zoom bisa mengikutinya.
     */
    data class Screen(val on: Boolean, val maxWidth: Int = 0, val maxHeight: Int = 0, val cursor: Boolean = false) : ControlMessage
    /** Frame layar sampai `seq` sudah diterima. */
    data class ScreenAck(val seq: Long) : ControlMessage
    /** [SCREEN_STREAMING], [SCREEN_DENIED], atau [SCREEN_FAILED]. */
    data class ScreenStatus(val state: String) : ControlMessage
    /** Posisi kursor di video layar, 0–1 dari kiri atas. */
    data class ScreenCursor(val x: Double, val y: Double) : ControlMessage
    /** Perintah volume, berisi tepat satu: naik/turun `step` langkah 1/16, `level` 0–1, atau `muted`. */
    data class Volume(val step: Int? = null, val level: Double? = null, val muted: Boolean? = null) : ControlMessage
    /** Volume output komputer. `level` null = perangkat output tidak bisa diatur volumenya. */
    data class VolumeStatus(val level: Double?, val muted: Boolean) : ControlMessage
    /** Tidurkan, mulai ulang, atau matikan komputer: [POWER_SLEEP], [POWER_RESTART], atau [POWER_SHUTDOWN]. */
    data class Power(val action: String) : ControlMessage
    data class Ping(val ts: Long) : ControlMessage
    data class Pong(val ts: Long) : ControlMessage
    data class Error(val error: String) : ControlMessage

    fun toJson(): String {
        val o = JSONObject()
        when (this) {
            is Hello -> o.put("t", "hello").put("v", version).put("deviceId", deviceId).put("mode", mode)
            is PairRequest -> o.put("t", "pair_request").put("token", token).put("deviceName", deviceName).put("publicKey", publicKey)
            is PairResult -> o.put("t", "pair_result").put("ok", ok).putOpt("hostId", hostId).putOpt("hostName", hostName).putOpt("error", error)
            is Challenge -> o.put("t", "challenge").put("nonce", nonce)
            is Auth -> o.put("t", "auth").put("sig", sig)
            is AuthResult -> o.put("t", "auth_result").put("ok", ok).putOpt("error", error).putOpt("platform", platform).putOpt("mac", mac)
                .apply { if (features.isNotEmpty()) put("features", JSONArray(features)) }
            is Settings -> o.put("t", "settings").put("sensitivity", sensitivity).put("scrollSpeed", scrollSpeed)
                .put("focusUpdates", focusUpdates).put("volumeUpdates", volumeUpdates)
            is Focus -> o.put("t", "focus").put("text", text)
            is Screen -> o.put("t", "screen").put("on", on)
                .apply { if (on) put("maxWidth", maxWidth).put("maxHeight", maxHeight) }
                .apply { if (on && cursor) put("cursor", true) }
            is ScreenAck -> o.put("t", "screen_ack").put("seq", seq)
            is ScreenStatus -> o.put("t", "screen_status").put("state", state)
            is ScreenCursor -> o.put("t", "screen_cursor").put("x", x).put("y", y)
            is Volume -> o.put("t", "volume").putOpt("step", step).putOpt("level", level).putOpt("muted", muted)
            is VolumeStatus -> o.put("t", "volume_status").putOpt("level", level).put("muted", muted)
            is Power -> o.put("t", "power").put("action", action)
            is Ping -> o.put("t", "ping").put("ts", ts)
            is Pong -> o.put("t", "pong").put("ts", ts)
            is Error -> o.put("t", "error").put("error", error)
        }
        return o.toString()
    }

    companion object {
        const val MODE_PAIR = "pair"
        const val MODE_AUTH = "auth"
        const val SCREEN_STREAMING = "streaming"
        const val SCREEN_DENIED = "denied"
        const val SCREEN_FAILED = "failed"
        const val POWER_SLEEP = "sleep"
        const val POWER_RESTART = "restart"
        const val POWER_SHUTDOWN = "shutdown"
        private val POWER_ACTIONS = setOf(POWER_SLEEP, POWER_RESTART, POWER_SHUTDOWN)

        fun parse(text: String): ControlMessage? = try {
            val o = JSONObject(text)
            when (o.optString("t")) {
                "hello" -> Hello(o.getInt("v"), o.getString("deviceId"), o.getString("mode"))
                "pair_request" -> PairRequest(o.getString("token"), o.getString("deviceName"), o.getString("publicKey"))
                "pair_result" -> PairResult(
                    o.getBoolean("ok"), o.optStringOrNull("hostId"), o.optStringOrNull("hostName"), o.optStringOrNull("error"),
                )
                "challenge" -> Challenge(o.getString("nonce"))
                "auth" -> Auth(o.getString("sig"))
                "auth_result" -> AuthResult(
                    o.getBoolean("ok"), o.optStringOrNull("error"), o.optStrings("features"), o.optStringOrNull("platform"),
                    o.optStringOrNull("mac"),
                )
                "settings" -> Settings(
                    o.getDouble("sensitivity"), o.getDouble("scrollSpeed"), o.optBoolean("focusUpdates", false),
                    o.optBoolean("volumeUpdates", false),
                )
                "focus" -> Focus(o.getBoolean("text"))
                "screen" ->
                    if (o.getBoolean("on")) Screen(true, o.getInt("maxWidth"), o.getInt("maxHeight"), o.optBoolean("cursor", false)) else Screen(false)
                "screen_ack" -> ScreenAck(o.getLong("seq"))
                "screen_status" -> ScreenStatus(o.getString("state"))
                "screen_cursor" -> ScreenCursor(o.getDouble("x"), o.getDouble("y"))
                "volume" -> Volume(o.optIntOrNull("step"), o.optDoubleOrNull("level"), if (o.has("muted")) o.getBoolean("muted") else null)
                "volume_status" -> VolumeStatus(o.optDoubleOrNull("level"), o.getBoolean("muted"))
                "power" -> o.getString("action").takeIf { it in POWER_ACTIONS }?.let(::Power)
                "ping" -> Ping(o.getLong("ts"))
                "pong" -> Pong(o.getLong("ts"))
                "error" -> Error(o.optString("error", "unknown"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

        private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

        private fun JSONObject.optStrings(key: String): List<String> =
            optJSONArray(key)?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList()
    }
}
