package io.github.wailantirajoh.cursorcontroller.core

import org.json.JSONObject

/** Pesan kontrol JSON (protocol/PROTOCOL.md). */
sealed interface ControlMessage {
    data class Hello(val version: Int, val deviceId: String, val mode: String) : ControlMessage
    data class PairRequest(val token: String, val deviceName: String, val publicKey: String) : ControlMessage
    data class PairResult(val ok: Boolean, val hostId: String?, val hostName: String?, val error: String?) : ControlMessage
    data class Challenge(val nonce: String) : ControlMessage
    data class Auth(val sig: String) : ControlMessage
    data class AuthResult(val ok: Boolean, val error: String?) : ControlMessage
    data class Settings(val sensitivity: Double, val scrollSpeed: Double) : ControlMessage
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
            is AuthResult -> o.put("t", "auth_result").put("ok", ok).putOpt("error", error)
            is Settings -> o.put("t", "settings").put("sensitivity", sensitivity).put("scrollSpeed", scrollSpeed)
            is Ping -> o.put("t", "ping").put("ts", ts)
            is Pong -> o.put("t", "pong").put("ts", ts)
            is Error -> o.put("t", "error").put("error", error)
        }
        return o.toString()
    }

    companion object {
        const val MODE_PAIR = "pair"
        const val MODE_AUTH = "auth"

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
                "auth_result" -> AuthResult(o.getBoolean("ok"), o.optStringOrNull("error"))
                "settings" -> Settings(o.getDouble("sensitivity"), o.getDouble("scrollSpeed"))
                "ping" -> Ping(o.getLong("ts"))
                "pong" -> Pong(o.getLong("ts"))
                "error" -> Error(o.optString("error", "unknown"))
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    }
}
