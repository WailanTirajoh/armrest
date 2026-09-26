package io.github.wailantirajoh.cursorcontroller

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wailantirajoh.cursorcontroller.ui.HostsScreen
import io.github.wailantirajoh.cursorcontroller.ui.PairingFailedScreen
import io.github.wailantirajoh.cursorcontroller.ui.PairingWaitScreen
import io.github.wailantirajoh.cursorcontroller.ui.ScanScreen
import io.github.wailantirajoh.cursorcontroller.ui.TouchpadScreen
import io.github.wailantirajoh.cursorcontroller.ui.theme.CursorControllerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ControllerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            CursorControllerTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    App(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground(true)
    }

    override fun onStop() {
        viewModel.onForeground(false)
        super.onStop()
    }

    /** QR yang dipindai kamera bawaan HP membuka app lewat cursorctl://pair?… */
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
            // Panel keyboard ditutup dulu sebelum keluar dari touchpad.
            is Screen.Touchpad ->
                if (state.keyboardOpen && state.link == Link.Connected) viewModel.toggleKeyboard() else viewModel.disconnect()
            else -> viewModel.backToHosts()
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().safeDrawingPadding()) {
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
                link = state.link,
                keyboardOpen = state.keyboardOpen,
                settings = state.settings,
                showGestureHints = state.showGestureHints,
                onActions = viewModel.sender::submit,
                onText = viewModel.sender::sendText,
                onKey = { key, modifiers, times -> viewModel.sender.sendKey(key, modifiers, times) },
                onToggleKeyboard = viewModel::toggleKeyboard,
                onSettingsChange = viewModel::updateSettings,
                onDismissHints = viewModel::dismissGestureHints,
                onBack = viewModel::disconnect,
            )
        }
    }
}
