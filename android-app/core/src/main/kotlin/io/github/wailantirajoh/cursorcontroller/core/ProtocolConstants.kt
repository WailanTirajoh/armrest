package io.github.wailantirajoh.cursorcontroller.core

/** Nilai yang harus sama persis dengan protocol/PROTOCOL.md dan agent Mac. */
object ProtocolConstants {
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_PORT = 47810
    const val SERVICE_TYPE = "_cursorctl._tcp"
    /** Fitur opsional di `auth_result`: agent bisa mengirim layar Mac. */
    const val FEATURE_SCREEN = "screen"
    /** Fitur opsional di `auth_result`: agent bisa mengatur volume output komputer. */
    const val FEATURE_VOLUME = "volume"
    /** Platform komputer di `auth_result`. Agent lama tidak mengirimnya dan selalu macOS. */
    const val PLATFORM_MACOS = "macos"
    const val PLATFORM_WINDOWS = "windows"
}
