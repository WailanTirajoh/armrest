package io.github.wailantirajoh.armrest

import android.app.Application
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wailantirajoh.armrest.core.AgentConnection
import io.github.wailantirajoh.armrest.core.ClientFailure
import io.github.wailantirajoh.armrest.core.ConnectTarget
import io.github.wailantirajoh.armrest.core.ControlMessage
import io.github.wailantirajoh.armrest.core.KeyCode
import io.github.wailantirajoh.armrest.core.KeyboardPanelState
import io.github.wailantirajoh.armrest.core.PairedHost
import io.github.wailantirajoh.armrest.core.PairingUri
import io.github.wailantirajoh.armrest.core.ProtocolConstants
import io.github.wailantirajoh.armrest.core.ReconnectPolicy
import io.github.wailantirajoh.armrest.core.ScreenPacket
import io.github.wailantirajoh.armrest.data.HostDiscovery
import io.github.wailantirajoh.armrest.data.HostStore
import io.github.wailantirajoh.armrest.data.InputSender
import io.github.wailantirajoh.armrest.data.KeystoreCredentials
import io.github.wailantirajoh.armrest.data.SavedHost
import io.github.wailantirajoh.armrest.data.ScreenDecoder
import io.github.wailantirajoh.armrest.data.SettingsStore
import io.github.wailantirajoh.armrest.data.TouchSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingError { FINGERPRINT, EXPIRED, INVALID, DENIED, TOO_MANY, NETWORK }

sealed interface Screen {
    data object Hosts : Screen
    data object Scan : Screen
    data class Pairing(val hostName: String) : Screen
    data class PairingFailed(val error: PairingError) : Screen
    data class Touchpad(val hostId: String, val hostName: String, val platform: String) : Screen
}

sealed interface Link {
    data object Connecting : Link
    data object Connected : Link
    data class Reconnecting(val attempt: Int) : Link
}

data class HostUi(val host: SavedHost, val online: Boolean)

enum class ScreenState { OFF, UNSUPPORTED, LOADING, SHOWING, DENIED, FAILED }

/** Preview layar Mac di area touchpad. `width` dan `height` = ukuran video, untuk rasio tampilan. */
data class ScreenUi(val state: ScreenState = ScreenState.OFF, val width: Int = 16, val height: Int = 10)

/**
 * Volume komputer. `supported`: agent bisa mengatur volume. `known`: status pertama sudah datang. `level` null
 * setelah diketahui = perangkat output komputer tidak bisa diatur volumenya.
 */
data class VolumeUi(val supported: Boolean = false, val known: Boolean = false, val level: Float? = null, val muted: Boolean = false)

/** Posisi kursor di video layar, 0–1 dari kiri atas. */
data class CursorPoint(val x: Float, val y: Float)

data class UiState(
    val screen: Screen = Screen.Hosts,
    val hosts: List<HostUi> = emptyList(),
    val link: Link = Link.Connecting,
    val keyboardOpen: Boolean = false,
    val macScreen: ScreenUi = ScreenUi(),
    /** Layar komputer memenuhi layar HP, tanpa bar atas dan bar sistem. */
    val fullscreen: Boolean = false,
    val volume: VolumeUi = VolumeUi(),
    /** Agent bisa menekan tombol media (putar/jeda, berikutnya, sebelumnya). */
    val media: Boolean = false,
    /** Naik setiap tombol volume HP ditekan, untuk menampilkan indikator volume sebentar. */
    val volumeHud: Int = 0,
    val settings: TouchSettings = TouchSettings(),
    val showGestureHints: Boolean = false,
    val manualAddressFor: SavedHost? = null,
    val message: String? = null,
)

class ControllerViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = KeystoreCredentials(application)
    private val hostStore = HostStore(application)
    private val settingsStore = SettingsStore(application)
    private var discovered: Map<String, String> = emptyMap()
    private val discovery = HostDiscovery(application) { found ->
        viewModelScope.launch {
            discovered = found
            refreshHosts()
        }
    }

    private val _state = MutableStateFlow(UiState(settings = settingsStore.load()))
    val state: StateFlow<UiState> = _state

    // Terpisah dari UiState karena bisa berubah 30 kali per detik; hanya tampilan zoom yang membacanya.
    private val _screenCursor = MutableStateFlow<CursorPoint?>(null)
    val screenCursor: StateFlow<CursorPoint?> = _screenCursor

    @Volatile private var connection: AgentConnection? = null
    // Dibaca juga dari thread OkHttp untuk membuang paket layar dari koneksi lama.
    @Volatile private var generation = 0
    private var attempt = 0
    private var reconnectJob: Job? = null
    private val manualAddress = HashMap<String, String>()
    private val keyboardPanel = KeyboardPanelState()
    private var foreground = false
    /** Layar Mac sudah diminta di koneksi ini. */
    private var screenStreaming = false
    /** Nilai slider volume yang belum terkirim; dikirim paling cepat tiap [VOLUME_SEND_MS]. */
    private var pendingVolume: Double? = null
    private var volumeJob: Job? = null
    /** Ukuran maksimum video = layar HP dalam posisi mendatar, jadi putar layar tidak perlu ukuran baru. */
    private val screenBox = application.resources.displayMetrics.let {
        maxOf(it.widthPixels, it.heightPixels) to minOf(it.widthPixels, it.heightPixels)
    }

    val screenDecoder = ScreenDecoder(object : ScreenDecoder.Listener {
        override fun onVideoSize(width: Int, height: Int) {
            viewModelScope.launch { _state.update { it.copy(macScreen = it.macScreen.copy(width = width, height = height)) } }
        }

        override fun onFirstFrame() {
            viewModelScope.launch { if (_state.value.macScreen.state == ScreenState.LOADING) setScreenState(ScreenState.SHOWING) }
        }

        override fun onKeyframeNeeded() {
            viewModelScope.launch { syncScreen(keyframe = true) }
        }
    })

    val sender = InputSender { connection?.takeIf { it.isAuthenticated } }

    init {
        refreshHosts()
    }

    fun onForeground(visible: Boolean) {
        foreground = visible
        if (visible) discovery.start() else discovery.stop()
        // Layar Mac hanya dikirim selama app terlihat.
        syncScreen()
    }

    // region Pairing

    fun openScanner() = _state.update { it.copy(screen = Screen.Scan) }

    /** Dari kamera atau deep link armrest://pair. Kode QR lain diabaikan. */
    fun onPairingText(text: String): Boolean {
        val uri = PairingUri.parse(text) ?: return false
        closeConnection()
        _state.update { it.copy(screen = Screen.Pairing(uri.hostName)) }
        open(ConnectTarget.Pair(uri), uri.hostId)
        return true
    }

    fun cancelPairing() {
        closeConnection()
        backToHosts()
    }

    // endregion

    // region Koneksi

    fun connect(host: SavedHost) {
        closeConnection()
        attempt = 0
        _state.update { it.copy(screen = Screen.Touchpad(host.hostId, host.name, host.platform), link = Link.Connecting) }
        openAuth(host.hostId)
    }

    fun disconnect() {
        closeConnection()
        backToHosts()
    }

    fun requestManualAddress(host: SavedHost?) = _state.update { it.copy(manualAddressFor = host) }

    fun connectManually(host: SavedHost, input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val address = if (trimmed.contains(':')) trimmed else "$trimmed:${ProtocolConstants.DEFAULT_PORT}"
        manualAddress[host.hostId] = address
        hostStore.upsert(host.copy(lastAddress = address))
        _state.update { it.copy(manualAddressFor = null) }
        connect(host.copy(lastAddress = address))
    }

    fun removeHost(host: SavedHost) {
        hostStore.remove(host.hostId)
        refreshHosts()
    }

    private fun openAuth(hostId: String) {
        val host = hostStore.find(hostId) ?: return backToHosts()
        val address = manualAddress[hostId] ?: discovered[hostId] ?: host.lastAddress
        open(ConnectTarget.Auth(hostId, address, host.fingerprint), hostId)
    }

    private fun open(target: ConnectTarget, hostId: String) {
        val gen = ++generation
        connection = AgentConnection(target, credentials, object : AgentConnection.Listener {
            override fun onPaired(host: PairedHost) = onMain(gen) {
                hostStore.upsert(SavedHost(host.hostId, host.hostName, host.fingerprint, host.address, System.currentTimeMillis()))
                refreshHosts()
            }

            override fun onAuthenticated() = onMain(gen) { authenticated(hostId, target.address) }

            override fun onTextFocus(focused: Boolean) = onMain(gen) { textFocusChanged(focused) }

            override fun onScreenStatus(state: String) = onMain(gen) { screenStatusChanged(state) }

            override fun onScreenCursor(x: Double, y: Double) {
                if (gen == generation) _screenCursor.value = CursorPoint(x.toFloat(), y.toFloat())
            }

            override fun onVolumeStatus(level: Double?, muted: Boolean) = onMain(gen) {
                _state.update { it.copy(volume = it.volume.copy(known = true, level = level?.toFloat(), muted = muted)) }
            }

            // Langsung ke decoder tanpa lewat main thread, supaya jeda tetap kecil.
            override fun onScreenPacket(packet: ScreenPacket) {
                if (gen == generation) screenDecoder.submit(packet)
            }

            override fun onEnded(failure: ClientFailure?) = onMain(gen) { ended(hostId, failure) }
        })
    }

    private fun authenticated(hostId: String, address: String) {
        attempt = 0
        val saved = hostStore.find(hostId) ?: return
        val host = saved.copy(lastAddress = address, lastUsed = System.currentTimeMillis(), platform = connection?.platform ?: saved.platform)
        hostStore.upsert(host)
        refreshHosts()
        _state.update {
            it.copy(
                screen = Screen.Touchpad(host.hostId, host.name, host.platform),
                link = Link.Connected,
                showGestureHints = !settingsStore.gestureHintsSeen,
                volume = VolumeUi(supported = ProtocolConstants.FEATURE_VOLUME in (connection?.features ?: emptySet())),
                media = ProtocolConstants.FEATURE_MEDIA in (connection?.features ?: emptySet()),
            )
        }
        sendSettings()
        syncScreen()
    }

    private fun ended(hostId: String, failure: ClientFailure?) {
        connection = null
        sender.reset()
        keyboardPanel.onTextFocus(null, auto = false)
        screenStreaming = false
        screenDecoder.reset()
        forgetConnectionState()
        when (val screen = _state.value.screen) {
            is Screen.Pairing -> _state.update { it.copy(screen = Screen.PairingFailed(pairingError(failure))) }
            is Screen.Touchpad -> when {
                failure is ClientFailure.AuthRejected && failure.code == "unknown_device" -> {
                    hostStore.remove(hostId)
                    backToHosts(getApplication<Application>().getString(R.string.message_revoked))
                }
                failure is ClientFailure.FingerprintMismatch ->
                    backToHosts(getApplication<Application>().getString(R.string.message_certificate_changed))
                else -> scheduleReconnect(screen.hostId)
            }
            else -> Unit
        }
    }

    private fun scheduleReconnect(hostId: String) {
        val delayMs = ReconnectPolicy.delayMs(attempt)
        attempt++
        _state.update { it.copy(link = Link.Reconnecting(attempt)) }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if ((_state.value.screen as? Screen.Touchpad)?.hostId == hostId) openAuth(hostId)
        }
    }

    private fun closeConnection() {
        generation++
        reconnectJob?.cancel()
        connection?.close()
        connection = null
        sender.reset()
        keyboardPanel.reset()
        syncKeyboard()
        screenStreaming = false
        screenDecoder.reset()
        forgetConnectionState()
    }

    /** Status volume dan posisi kursor hanya berlaku untuk satu koneksi. */
    private fun forgetConnectionState() {
        _screenCursor.value = null
        pendingVolume = null
        volumeJob?.cancel()
        _state.update { it.copy(volume = VolumeUi(), media = false) }
    }

    private fun pairingError(failure: ClientFailure?): PairingError = when (failure) {
        ClientFailure.FingerprintMismatch -> PairingError.FINGERPRINT
        is ClientFailure.PairRejected -> when (failure.code) {
            "token_expired" -> PairingError.EXPIRED
            "denied" -> PairingError.DENIED
            "too_many_attempts" -> PairingError.TOO_MANY
            else -> PairingError.INVALID
        }
        else -> PairingError.NETWORK
    }

    // endregion

    // region Pengaturan & UI

    fun updateSettings(settings: TouchSettings) {
        settingsStore.save(settings)
        // Mode otomatis dimatikan: lupakan fokus terakhir supaya status baru dari Mac berlaku saat dinyalakan lagi.
        if (!settings.autoKeyboard) keyboardPanel.onTextFocus(null, auto = false)
        // Layar komputer disembunyikan: fullscreen tidak ada gunanya lagi.
        _state.update { it.copy(settings = settings, fullscreen = it.fullscreen && settings.screenPreview) }
        sendSettings()
        syncScreen()
    }

    fun toggleKeyboard() {
        keyboardPanel.toggle()
        syncKeyboard()
    }

    private fun textFocusChanged(focused: Boolean) {
        keyboardPanel.onTextFocus(focused, _state.value.settings.autoKeyboard)
        syncKeyboard()
    }

    private fun syncKeyboard() = _state.update { it.copy(keyboardOpen = keyboardPanel.isOpen) }

    fun toggleScreen() = updateSettings(_state.value.settings.let { it.copy(screenPreview = !it.screenPreview) })

    fun toggleFullscreen() = _state.update { it.copy(fullscreen = !it.fullscreen && it.settings.screenPreview) }

    // region Volume

    fun changeVolume(steps: Int) {
        connection?.takeIf { it.isAuthenticated }?.changeVolume(steps)
    }

    /** Putar/jeda, berikutnya, atau sebelumnya: dikirim sebagai tombol media biasa. */
    fun sendMediaKey(key: KeyCode) {
        if (_state.value.media) sender.sendKey(key)
    }

    fun toggleMute() {
        val volume = _state.value.volume
        if (volume.known) connection?.takeIf { it.isAuthenticated }?.setMuted(!volume.muted)
    }

    /** Dari slider: nilai pertama langsung dikirim, lalu paling cepat tiap [VOLUME_SEND_MS], dan nilai terakhir selalu terkirim. */
    fun setVolume(level: Float) {
        pendingVolume = level.toDouble()
        if (volumeJob?.isActive == true) return
        volumeJob = viewModelScope.launch {
            while (true) {
                val next = pendingVolume ?: break
                pendingVolume = null
                connection?.takeIf { it.isAuthenticated }?.setVolume(next)
                delay(VOLUME_SEND_MS)
            }
        }
    }

    /**
     * Tombol volume HP mengatur volume komputer selama touchpad tersambung, kalau setelannya menyala. true = event
     * sudah dipakai (termasuk saat tombol dilepas), jadi volume HP sendiri tidak ikut berubah.
     */
    fun onVolumeKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        val steps = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 1
            KeyEvent.KEYCODE_VOLUME_DOWN -> -1
            KeyEvent.KEYCODE_VOLUME_MUTE -> 0
            else -> return false
        }
        val state = _state.value
        if (state.screen !is Screen.Touchpad || state.link != Link.Connected || !state.volume.supported || !state.settings.volumeKeys) {
            return false
        }
        if (down) {
            // Tombol yang ditahan berulang, jadi volume terus naik atau turun; bisu hanya sekali per tekan.
            if (steps != 0) changeVolume(steps) else if (repeatCount == 0) toggleMute()
            _state.update { it.copy(volumeHud = it.volumeHud + 1) }
        }
        return true
    }

    // endregion

    /** Coba lagi setelah Mac menolak atau gagal menampilkan layar. */
    fun retryScreen() {
        stopScreenStream()
        syncScreen()
    }

    fun onScreenSurface(surface: Surface?) = screenDecoder.setSurface(surface)

    /** Minta atau hentikan layar Mac sesuai setelan, koneksi, dan apakah app terlihat. */
    private fun syncScreen(keyframe: Boolean = false) {
        val active = connection?.takeIf { it.isAuthenticated }
        val preview = _state.value.settings.screenPreview
        // Selama belum tersambung, anggap didukung; kepastiannya datang dari auth_result.
        val supported = active == null || ProtocolConstants.FEATURE_SCREEN in active.features
        val wanted = active != null && preview && foreground && supported
        when {
            wanted && !screenStreaming -> {
                active.requestScreen(screenBox.first, screenBox.second)
                screenStreaming = true
                setScreenState(ScreenState.LOADING)
            }
            wanted && keyframe -> active.requestScreen(screenBox.first, screenBox.second)
            !wanted && screenStreaming -> stopScreenStream()
        }
        if (!preview) setScreenState(ScreenState.OFF) else if (!supported) setScreenState(ScreenState.UNSUPPORTED)
    }

    private fun stopScreenStream() {
        if (screenStreaming) connection?.stopScreen()
        screenStreaming = false
        screenDecoder.reset()
    }

    private fun screenStatusChanged(state: String) {
        when (state) {
            ControlMessage.SCREEN_DENIED -> setScreenState(ScreenState.DENIED)
            ControlMessage.SCREEN_FAILED -> setScreenState(ScreenState.FAILED)
        }
    }

    private fun setScreenState(state: ScreenState) = _state.update { it.copy(macScreen = it.macScreen.copy(state = state)) }

    fun dismissGestureHints() {
        settingsStore.gestureHintsSeen = true
        _state.update { it.copy(showGestureHints = false) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun backToHosts(message: String? = null) =
        _state.update { it.copy(screen = Screen.Hosts, message = message ?: it.message, manualAddressFor = null, fullscreen = false) }

    private fun sendSettings() {
        val settings = _state.value.settings
        val active = connection ?: return
        active.sendSettings(
            settings.sensitivity.toDouble(), settings.scrollSpeed.toDouble(), focusUpdates = settings.autoKeyboard,
            // Status volume selalu diminta kalau agent mendukungnya: dipakai panel volume dan indikator tombol volume.
            volumeUpdates = ProtocolConstants.FEATURE_VOLUME in active.features,
        )
    }

    private fun refreshHosts() {
        _state.update { state -> state.copy(hosts = hostStore.load().map { HostUi(it, discovered.containsKey(it.hostId)) }) }
    }

    /** Callback koneksi datang dari thread OkHttp; abaikan kalau koneksinya sudah diganti. */
    private fun onMain(gen: Int, block: () -> Unit) {
        viewModelScope.launch { if (gen == generation) block() }
    }

    override fun onCleared() {
        closeConnection()
        discovery.stop()
        screenDecoder.close()
    }

    // endregion

    private companion object {
        const val VOLUME_SEND_MS = 60L
    }
}
