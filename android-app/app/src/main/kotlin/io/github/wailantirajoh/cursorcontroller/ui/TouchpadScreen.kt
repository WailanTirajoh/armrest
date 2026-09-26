package io.github.wailantirajoh.cursorcontroller.ui

import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wailantirajoh.cursorcontroller.Link
import io.github.wailantirajoh.cursorcontroller.ScreenState
import io.github.wailantirajoh.cursorcontroller.ScreenUi
import io.github.wailantirajoh.cursorcontroller.core.GestureEngine
import io.github.wailantirajoh.cursorcontroller.core.InputAction
import io.github.wailantirajoh.cursorcontroller.core.KeyCode
import io.github.wailantirajoh.cursorcontroller.core.MouseButton
import io.github.wailantirajoh.cursorcontroller.data.TouchSettings
import java.util.Locale

/** Mockup A3: touchpad. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(
    hostName: String,
    link: Link,
    keyboardOpen: Boolean,
    macScreen: ScreenUi,
    settings: TouchSettings,
    showGestureHints: Boolean,
    onActions: (List<InputAction>) -> Unit,
    onText: (String) -> Unit,
    onKey: (key: KeyCode, modifiers: Int, times: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleScreen: () -> Unit,
    onRetryScreen: () -> Unit,
    onScreenSurface: (Surface?) -> Unit,
    onSettingsChange: (TouchSettings) -> Unit,
    onDismissHints: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    var showSettings by remember { mutableStateOf(false) }
    val connected = link == Link.Connected

    // Layar tetap menyala selama touchpad terbuka.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val currentSettings by rememberUpdatedState(settings)
    val emit: (List<InputAction>) -> Unit = { actions ->
        if (actions.isNotEmpty()) {
            if (currentSettings.haptics && actions.any { it is InputAction.Click || (it is InputAction.Button && it.down) }) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onActions(actions)
        }
    }
    val currentEmit by rememberUpdatedState(emit)

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = hostName,
            navigationIcon = AppIcons.Back,
            navigationLabel = "Putuskan dan kembali",
            onNavigate = onBack,
            subtitle = { LinkStatus(link) },
            actions = {
                IconButton(onClick = onToggleScreen, enabled = connected) {
                    Icon(
                        AppIcons.Monitor,
                        contentDescription = if (settings.screenPreview) "Sembunyikan layar Mac" else "Tampilkan layar Mac",
                        tint = if (settings.screenPreview) colors.primary else colors.onSurface,
                    )
                }
                IconButton(onClick = onToggleKeyboard, enabled = connected) {
                    Icon(
                        AppIcons.Keyboard,
                        contentDescription = if (keyboardOpen) "Tutup keyboard" else "Buka keyboard",
                        tint = if (keyboardOpen) colors.primary else colors.onSurface,
                    )
                }
                IconButton(onClick = { showSettings = true }) { Icon(AppIcons.Settings, contentDescription = "Pengaturan touchpad") }
            },
        )
        if (link is Link.Reconnecting) {
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(Tones.warningContainer(), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(AppIcons.Refresh, contentDescription = null, tint = Tones.warning(), modifier = Modifier.size(20.dp))
                Text("Koneksi putus. Mencoba lagi… (percobaan ${link.attempt})", color = Tones.warning(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val touchpadShape = RoundedCornerShape(28.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                .clip(touchpadShape)
                .background(colors.surfaceVariant.copy(alpha = if (connected) 1f else 0.45f))
                .border(1.dp, colors.outline, touchpadShape),
            contentAlignment = Alignment.Center,
        ) {
            if (connected && (macScreen.state == ScreenState.LOADING || macScreen.state == ScreenState.SHOWING)) {
                ScreenPreview(macScreen, onScreenSurface)
            }
            // Lapisan gesture di atas video: layar Mac hanya ditampilkan, semua sentuhan tetap untuk touchpad.
            Box(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = "Area touchpad" }
                    .pointerInput(connected) {
                        if (!connected) return@pointerInput
                        val engine = GestureEngine()
                        awaitPointerEventScope {
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val actions = ArrayList<InputAction>()
                                    for (change in event.changes) {
                                        val id = change.id.value
                                        val x = change.position.x.toDp().value
                                        val y = change.position.y.toDp().value
                                        val t = change.uptimeMillis
                                        when {
                                            change.pressed && !change.previousPressed -> actions += engine.down(id, x, y, t)
                                            change.pressed -> if (change.position != change.previousPosition) actions += engine.move(id, x, y, t)
                                            change.previousPressed -> actions += engine.up(id, t)
                                        }
                                        change.consume()
                                    }
                                    currentEmit(actions)
                                }
                            } finally {
                                currentEmit(engine.cancel())
                            }
                        }
                    },
            )
            TouchpadMessage(connected, macScreen.state, onRetryScreen)
        }
        if (keyboardOpen && connected) {
            KeyboardPanel(onText = onText, onKey = onKey)
        } else if (settings.showButtons) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                listOf(MouseButton.LEFT to "Kiri", MouseButton.RIGHT to "Kanan").forEach { (button, label) ->
                    OutlinedButton(
                        onClick = { emit(listOf(InputAction.Click(button, 1))) },
                        enabled = connected,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp),
                    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            SettingsSheet(settings, onSettingsChange)
        }
    }
    if (showGestureHints && connected) {
        GestureHints(onDismissHints)
    }
}

/** Video layar Mac, pas di area touchpad dengan rasio aslinya. */
@Composable
private fun ScreenPreview(screen: ScreenUi, onSurface: (Surface?) -> Unit) {
    val currentOnSurface by rememberUpdatedState(onSurface)
    AndroidEmbeddedExternalSurface(
        modifier = Modifier
            .aspectRatio(screen.width.toFloat() / screen.height)
            // Tersembunyi sampai frame pertama tampil, supaya tidak ada kotak hitam saat memuat.
            .alpha(if (screen.state == ScreenState.SHOWING) 1f else 0f),
    ) {
        onSurface { surface, _, _ ->
            currentOnSurface(surface)
            surface.onDestroyed { currentOnSurface(null) }
        }
    }
}

@Composable
private fun TouchpadMessage(connected: Boolean, state: ScreenState, onRetry: () -> Unit) {
    val text = when {
        !connected -> "Touchpad nonaktif sampai tersambung"
        state == ScreenState.SHOWING -> return
        state == ScreenState.LOADING -> "Memuat layar Mac…"
        state == ScreenState.DENIED -> "Mac belum mengizinkan Screen Recording. Izinkan lewat menu Cursor Controller di Mac."
        state == ScreenState.FAILED -> "Layar Mac tidak bisa ditampilkan."
        state == ScreenState.UNSUPPORTED -> "Perbarui Cursor Controller di Mac ke versi 0.5 atau lebih baru untuk melihat layar."
        else -> "Geser untuk menggerakkan kursor"
    }
    Column(
        Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (connected && (state == ScreenState.DENIED || state == ScreenState.FAILED)) {
            OutlinedButton(onClick = onRetry) { Text("Coba lagi") }
        }
    }
}

@Composable
private fun LinkStatus(link: Link) {
    val colors = MaterialTheme.colorScheme
    val (label, dot) = when (link) {
        Link.Connected -> "Terhubung" to colors.primary
        Link.Connecting -> "Menyambung…" to Tones.warning()
        is Link.Reconnecting -> "Menyambung ulang" to Tones.warning()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun SettingsSheet(settings: TouchSettings, onChange: (TouchSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pengaturan touchpad", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        SliderRow("Sensitivitas", settings.sensitivity, 0.5f..3f) { onChange(settings.copy(sensitivity = it)) }
        SliderRow("Kecepatan scroll", settings.scrollSpeed, 0.5f..4f) { onChange(settings.copy(scrollSpeed = it)) }
        SwitchRow("Tombol kiri/kanan", settings.showButtons) { onChange(settings.copy(showButtons = it)) }
        SwitchRow("Getar saat klik", settings.haptics) { onChange(settings.copy(haptics = it)) }
        SwitchRow("Buka keyboard otomatis", settings.autoKeyboard, "Saat kolom teks di Mac aktif") {
            onChange(settings.copy(autoKeyboard = it))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 15.sp)
            Text(String.format(Locale.forLanguageTag("id-ID"), "%.1f×", value), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = { onChange((it * 10).toInt() / 10f) }, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            if (description != null) {
                Text(description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GestureHints(onDismiss: () -> Unit) {
    val rows = listOf(
        "Geser 1 jari" to "Gerakkan kursor",
        "Tap" to "Klik kiri",
        "Tap 2 kali" to "Klik ganda",
        "Tap 2 jari" to "Klik kanan",
        "Geser 2 jari" to "Scroll",
        "Tap, lalu tahan dan geser" to "Drag",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cara pakai touchpad") },
        text = {
            Column {
                rows.forEachIndexed { index, (gesture, action) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(gesture, fontSize = 15.sp)
                        Text(action, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index < rows.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Mengerti") } },
    )
}
