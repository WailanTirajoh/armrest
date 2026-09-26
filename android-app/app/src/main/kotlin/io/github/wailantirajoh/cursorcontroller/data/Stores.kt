package io.github.wailantirajoh.cursorcontroller.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Mac yang sudah dipasangkan. `fingerprint` dipakai untuk pinning TLS. */
data class SavedHost(
    val hostId: String,
    val name: String,
    val fingerprint: String,
    val lastAddress: String,
    val lastUsed: Long,
)

class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    fun load(): List<SavedHost> {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SavedHost(o.getString("hostId"), o.getString("name"), o.getString("fingerprint"), o.getString("lastAddress"), o.optLong("lastUsed"))
        }.sortedByDescending { it.lastUsed }
    }

    fun find(hostId: String): SavedHost? = load().firstOrNull { it.hostId == hostId }

    fun upsert(host: SavedHost) = save(load().filterNot { it.hostId == host.hostId } + host)

    fun remove(hostId: String) = save(load().filterNot { it.hostId == hostId })

    private fun save(hosts: List<SavedHost>) {
        val array = JSONArray()
        hosts.forEach {
            array.put(
                JSONObject()
                    .put("hostId", it.hostId)
                    .put("name", it.name)
                    .put("fingerprint", it.fingerprint)
                    .put("lastAddress", it.lastAddress)
                    .put("lastUsed", it.lastUsed),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "hosts"
    }
}

data class TouchSettings(
    val sensitivity: Float = 1.5f,
    val scrollSpeed: Float = 2.0f,
    val showButtons: Boolean = true,
    val haptics: Boolean = true,
    /** Buka panel keyboard saat kolom teks di Mac aktif. */
    val autoKeyboard: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = TouchSettings(
        sensitivity = prefs.getFloat("sensitivity", 1.5f),
        scrollSpeed = prefs.getFloat("scrollSpeed", 2.0f),
        showButtons = prefs.getBoolean("showButtons", true),
        haptics = prefs.getBoolean("haptics", true),
        autoKeyboard = prefs.getBoolean("autoKeyboard", true),
    )

    fun save(settings: TouchSettings) {
        prefs.edit()
            .putFloat("sensitivity", settings.sensitivity)
            .putFloat("scrollSpeed", settings.scrollSpeed)
            .putBoolean("showButtons", settings.showButtons)
            .putBoolean("haptics", settings.haptics)
            .putBoolean("autoKeyboard", settings.autoKeyboard)
            .apply()
    }

    var gestureHintsSeen: Boolean
        get() = prefs.getBoolean("gestureHintsSeen", false)
        set(value) = prefs.edit().putBoolean("gestureHintsSeen", value).apply()
}
