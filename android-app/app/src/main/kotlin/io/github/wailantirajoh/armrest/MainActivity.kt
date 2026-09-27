package io.github.wailantirajoh.armrest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wailantirajoh.armrest.ui.HostsScreen
import io.github.wailantirajoh.armrest.ui.PairingFailedScreen
import io.github.wailantirajoh.armrest.ui.PairingWaitScreen
import io.github.wailantirajoh.armrest.ui.ScanScreen
import io.github.wailantirajoh.armrest.ui.TouchpadScreen
import io.github.wailantirajoh.armrest.ui.theme.ArmrestTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ControllerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            ArmrestTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    App(viewModel)
                }
            }
        }
    }

    /** Tombol volume HP mengatur volume komputer selama touchpad terbuka (kalau setelannya menyala). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        viewModel.onVolumeKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground(true)
    }

    override fun onStop() {
        // Putar layar membuat ulang Activity; itu bukan berarti app pindah ke latar belakang.
        if (!isChangingConfigurations) viewModel.onForeground(false)
        super.onStop()
    }

    /** QR yang dipindai kamera bawaan HP membuka app lewat armrest://pair?… */
    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (!viewModel.onPairingText(data)) {
            Toast.makeText(this, "Tautan pairing tidak valid", Toast.LENGTH_SHORT).show()
        }
    }
}

@androidx.compose.runtime.Composable
private fun App(viewModel: ControllerViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }

    val screen = state.screen
    BackHandler(enabled = screen != Screen.Hosts) {
        when (screen) {
            is Screen.Pairing -> viewModel.cancelPairing()
            // Fullscreen dan panel keyboard ditutup dulu sebelum keluar dari touchpad.
            is Screen.Touchpad -> when {
                state.fullscreen -> viewModel.toggleFullscreen()
                state.keyboardOpen && state.link == Link.Connected -> viewModel.toggleKeyboard()
                else -> viewModel.disconnect()
            }
            else -> viewModel.backToHosts()
        }
    }

    // Fullscreen: bar status dan navigasi disembunyikan; usap dari tepi layar menampilkannya sebentar.
    val view = LocalView.current
    val fullscreen = state.fullscreen && screen is Screen.Touchpad
    DisposableEffect(fullscreen) {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }

    // Layar penuh menggambar sampai tepi layar, juga di bawah handle aplikasi tablet; hanya keyboard dan notch yang dihindari.
    val insets = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.displayCutout)) else Modifier.safeDrawingPadding()
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().then(insets)) {
        when (screen) {
            Screen.Hosts -> HostsScreen(
                hosts = state.hosts,
                manualAddressFor = state.manualAddressFor,
                onScan = viewModel::openScanner,
                onConnect = viewModel::connect,
                onRequestManual = viewModel::requestManualAddress,
                onConnectManually = viewModel::connectManually,
                onRemove = viewModel::removeHost,
            )
            Screen.Scan -> ScanScreen(onBack = { viewModel.backToHosts() }, onScanned = viewModel::onPairingText)
            is Screen.Pairing -> PairingWaitScreen(screen.hostName, onCancel = viewModel::cancelPairing)
            is Screen.PairingFailed -> PairingFailedScreen(screen.error, onRetry = viewModel::openScanner, onClose = { viewModel.backToHosts() })
            is Screen.Touchpad -> TouchpadScreen(
                hostName = screen.hostName,
                platform = screen.platform,
                link = state.link,
                keyboardOpen = state.keyboardOpen,
                macScreen = state.macScreen,
                fullscreen = state.fullscreen,
                screenCursor = viewModel.screenCursor,
                volume = state.volume,
                volumeHud = state.volumeHud,
                settings = state.settings,
                showGestureHints = state.showGestureHints,
                onActions = viewModel.sender::submit,
                onText = viewModel.sender::sendText,
                onKey = { key, modifiers, times -> viewModel.sender.sendKey(key, modifiers, times) },
                onToggleKeyboard = viewModel::toggleKeyboard,
                onToggleScreen = viewModel::toggleScreen,
                onToggleFullscreen = viewModel::toggleFullscreen,
                onVolumeStep = viewModel::changeVolume,
                onVolumeLevel = viewModel::setVolume,
                onToggleMute = viewModel::toggleMute,
                onRetryScreen = viewModel::retryScreen,
                onScreenSurface = viewModel::onScreenSurface,
                onSettingsChange = viewModel::updateSettings,
                onDismissHints = viewModel::dismissGestureHints,
                onBack = viewModel::disconnect,
            )
        }
    }
}
