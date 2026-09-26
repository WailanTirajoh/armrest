package io.github.wailantirajoh.cursorcontroller

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.wailantirajoh.cursorcontroller.core.AgentConnection
import io.github.wailantirajoh.cursorcontroller.core.ClientFailure
import io.github.wailantirajoh.cursorcontroller.core.ConnectTarget
import io.github.wailantirajoh.cursorcontroller.core.ControlMessage
import io.github.wailantirajoh.cursorcontroller.core.KeyboardPanelState
import io.github.wailantirajoh.cursorcontroller.core.PairedHost
import io.github.wailantirajoh.cursorcontroller.core.PairingUri
import io.github.wailantirajoh.cursorcontroller.core.ProtocolConstants
import io.github.wailantirajoh.cursorcontroller.core.ReconnectPolicy
import io.github.wailantirajoh.cursorcontroller.core.ScreenPacket
import io.github.wailantirajoh.cursorcontroller.data.HostDiscovery
import io.github.wailantirajoh.cursorcontroller.data.HostStore
import io.github.wailantirajoh.cursorcontroller.data.InputSender
import io.github.wailantirajoh.cursorcontroller.data.KeystoreCredentials
import io.github.wailantirajoh.cursorcontroller.data.SavedHost
import io.github.wailantirajoh.cursorcontroller.data.ScreenDecoder
import io.github.wailantirajoh.cursorcontroller.data.SettingsStore
import io.github.wailantirajoh.cursorcontroller.data.TouchSettings
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
    data class Touchpad(val hostId: String, val hostName: String) : Screen
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

data class UiState(
    val screen: Screen = Screen.Hosts,
    val hosts: List<HostUi> = emptyList(),
    val link: Link = Link.Connecting,
    val keyboardOpen: Boolean = false,
    val macScreen: ScreenUi = ScreenUi(),
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

    /** Dari kamera atau deep link cursorctl://pair. Kode QR lain diabaikan. */
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
        _state.update { it.copy(screen = Screen.Touchpad(host.hostId, host.name), link = Link.Connecting) }
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

            // Langsung ke decoder tanpa lewat main thread, supaya jeda tetap kecil.
            override fun onScreenPacket(packet: ScreenPacket) {
                if (gen == generation) screenDecoder.submit(packet)
            }

            override fun onEnded(failure: ClientFailure?) = onMain(gen) { ended(hostId, failure) }
        })
    }

    private fun authenticated(hostId: String, address: String) {
        attempt = 0
        val host = hostStore.find(hostId) ?: return
        hostStore.upsert(host.copy(lastAddress = address, lastUsed = System.currentTimeMillis()))
        refreshHosts()
        _state.update {
            it.copy(
                screen = Screen.Touchpad(host.hostId, host.name),
                link = Link.Connected,
                showGestureHints = !settingsStore.gestureHintsSeen,
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
        when (val screen = _state.value.screen) {
            is Screen.Pairing -> _state.update { it.copy(screen = Screen.PairingFailed(pairingError(failure))) }
            is Screen.Touchpad -> when {
                failure is ClientFailure.AuthRejected && failure.code == "unknown_device" -> {
                    hostStore.remove(hostId)
                    backToHosts("Mac sudah mencabut akses HP ini. Pasangkan ulang lewat QR.")
                }
                failure is ClientFailure.FingerprintMismatch ->
                    backToHosts("Sertifikat Mac berubah. Demi keamanan, pasangkan ulang lewat QR.")
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
        _state.update { it.copy(settings = settings) }
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
        _state.update { it.copy(screen = Screen.Hosts, message = message ?: it.message, manualAddressFor = null) }

    private fun sendSettings() {
        val settings = _state.value.settings
        connection?.sendSettings(settings.sensitivity.toDouble(), settings.scrollSpeed.toDouble(), focusUpdates = settings.autoKeyboard)
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
}
