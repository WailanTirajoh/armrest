package io.github.wailantirajoh.armrest.ui

import android.view.HapticFeedbackConstants
import android.view.Surface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wailantirajoh.armrest.CursorPoint
import io.github.wailantirajoh.armrest.Link
import io.github.wailantirajoh.armrest.ScreenState
import io.github.wailantirajoh.armrest.ScreenUi
import io.github.wailantirajoh.armrest.VolumeUi
import io.github.wailantirajoh.armrest.core.GestureEngine
import io.github.wailantirajoh.armrest.core.InputAction
import io.github.wailantirajoh.armrest.core.KeyCode
import io.github.wailantirajoh.armrest.core.MouseButton
import io.github.wailantirajoh.armrest.core.ProtocolConstants
import io.github.wailantirajoh.armrest.core.ScreenViewport
import io.github.wailantirajoh.armrest.data.TouchSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/** Mockup A3: touchpad. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchpadScreen(
    hostName: String,
    platform: String,
    link: Link,
    keyboardOpen: Boolean,
    macScreen: ScreenUi,
    fullscreen: Boolean,
    screenCursor: StateFlow<CursorPoint?>,
    volume: VolumeUi,
    volumeHud: Int,
    settings: TouchSettings,
    showGestureHints: Boolean,
    onActions: (List<InputAction>) -> Unit,
    onText: (String) -> Unit,
    onKey: (key: KeyCode, modifiers: Int, times: Int) -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleScreen: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onVolumeStep: (Int) -> Unit,
    onVolumeLevel: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onRetryScreen: () -> Unit,
    onScreenSurface: (Surface?) -> Unit,
    onSettingsChange: (TouchSettings) -> Unit,
    onDismissHints: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val view = LocalView.current
    var showSettings by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    val connected = link == Link.Connected
    val showingScreen = connected && macScreen.state == ScreenState.SHOWING

    // Zoom layar komputer: cubit memperbesar, lalu tampilan mengikuti kursor. Satuan dp, sama dengan GestureEngine.
    var viewport by remember { mutableStateOf(ScreenViewport()) }
    var areaSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val geometry = remember(areaSize, macScreen.width, macScreen.height, density) {
        with(density) {
            ScreenViewport.Geometry.fit(areaSize.width.toDp().value, areaSize.height.toDp().value, macScreen.width.toFloat() / macScreen.height)
        }
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val zoomEnabled by rememberUpdatedState(showingScreen)
    // Zoom hanya selama layar tampil; ukuran video baru (mis. pindah monitor) mulai lagi tanpa zoom.
    LaunchedEffect(showingScreen, macScreen.width, macScreen.height) { viewport = ScreenViewport.RESET }
    LaunchedEffect(geometry) { viewport = viewport.clamped(geometry) }
    LaunchedEffect(screenCursor) {
        screenCursor.collect { cursor -> if (cursor != null) viewport = viewport.follow(currentGeometry, cursor.x, cursor.y) }
    }

    // Layar tetap menyala selama touchpad terbuka.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val currentSettings by rememberUpdatedState(settings)
    val emit: (List<InputAction>) -> Unit = { all ->
        // Cubit hanya mengubah tampilan di HP; sisanya dikirim ke komputer.
        val (pinches, actions) = all.partition { it is InputAction.Pinch }
        for (pinch in pinches.filterIsInstance<InputAction.Pinch>()) {
            viewport = viewport.pinch(currentGeometry, pinch.scale, pinch.focusX, pinch.focusY, pinch.panX, pinch.panY)
        }
        if (actions.isNotEmpty()) {
            if (currentSettings.haptics && actions.any { it is InputAction.Click || (it is InputAction.Button && it.down) }) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onActions(actions)
        }
    }
    val currentEmit by rememberUpdatedState(emit)

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
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
                            contentDescription = if (settings.screenPreview) "Sembunyikan layar komputer" else "Tampilkan layar komputer",
                            tint = if (settings.screenPreview) colors.primary else colors.onSurface,
                        )
                    }
                    if (volume.supported) {
                        IconButton(onClick = { showVolume = true }, enabled = connected) {
                            Icon(volumeIcon(volume), contentDescription = "Volume komputer")
                        }
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
        }
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
                .then(
                    if (fullscreen) {
                        Modifier.background(Color.Black)
                    } else {
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
                            .clip(touchpadShape)
                            .background(colors.surfaceVariant.copy(alpha = if (connected) 1f else 0.45f))
                            .border(1.dp, colors.outline, touchpadShape)
                    },
                )
                // Video yang di-zoom tidak boleh keluar dari area touchpad.
                .clipToBounds()
                .onSizeChanged { areaSize = it },
            contentAlignment = Alignment.Center,
        ) {
            if (connected && (macScreen.state == ScreenState.LOADING || macScreen.state == ScreenState.SHOWING)) {
                ScreenPreview(macScreen, { viewport }, onScreenSurface)
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
                                    engine.zoomEnabled = zoomEnabled
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
            TouchpadMessage(connected, macScreen.state, platform, onRetryScreen)
            if (viewport.isZoomed) {
                ZoomChip(viewport.scale, onReset = { viewport = ScreenViewport.RESET }, Modifier.align(Alignment.TopStart))
            }
            // Fullscreen tidak punya bar atas, jadi keyboard dan volume pindah ke pojok layar.
            if (fullscreen || (connected && (macScreen.state == ScreenState.LOADING || macScreen.state == ScreenState.SHOWING))) {
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (fullscreen) {
                        OverlayButton(AppIcons.Keyboard, if (keyboardOpen) "Tutup keyboard" else "Buka keyboard", onToggleKeyboard, enabled = connected)
                        if (volume.supported) OverlayButton(volumeIcon(volume), "Volume komputer", { showVolume = true }, enabled = connected)
                        OverlayButton(AppIcons.FullscreenExit, "Keluar dari layar penuh", onToggleFullscreen)
                    } else {
                        OverlayButton(AppIcons.Fullscreen, "Layar penuh", onToggleFullscreen)
                    }
                }
            }
            VolumeHud(volumeHud, volume, Modifier.align(Alignment.TopCenter))
        }
        if (keyboardOpen && connected) {
            KeyboardPanel(onText = onText, onKey = onKey, platform = platform)
        } else if (settings.showButtons && !fullscreen) {
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
    if (showVolume && volume.supported) {
        ModalBottomSheet(onDismissRequest = { showVolume = false }) {
            VolumeSheet(hostName, volume, settings.volumeKeys, onVolumeStep, onVolumeLevel, onToggleMute)
        }
    }
    if (showGestureHints && connected) {
        GestureHints(onDismissHints)
    }
}

/**
 * Video layar Mac, pas di area touchpad dengan rasio aslinya. Zoom lewat graphicsLayer (TextureView bisa
 * ditransformasi), dibaca di dalam layer supaya tidak memicu recomposition 30 kali per detik.
 */
@Composable
private fun ScreenPreview(screen: ScreenUi, viewport: () -> ScreenViewport, onSurface: (Surface?) -> Unit) {
    val currentOnSurface by rememberUpdatedState(onSurface)
    AndroidEmbeddedExternalSurface(
        modifier = Modifier
            .aspectRatio(screen.width.toFloat() / screen.height)
            .graphicsLayer {
                val current = viewport()
                scaleX = current.scale
                scaleY = current.scale
                translationX = current.offsetX * density
                translationY = current.offsetY * density
                // Tersembunyi sampai frame pertama tampil, supaya tidak ada kotak hitam saat memuat.
                alpha = if (screen.state == ScreenState.SHOWING) 1f else 0f
            },
    ) {
        onSurface { surface, _, _ ->
            currentOnSurface(surface)
            surface.onDestroyed { currentOnSurface(null) }
        }
    }
}

private fun volumeIcon(volume: VolumeUi): ImageVector =
    if (volume.known && (volume.muted || volume.level == 0f)) AppIcons.VolumeOff else AppIcons.Volume

private fun volumeLabel(volume: VolumeUi, level: Float? = volume.level): String = when {
    !volume.known -> "…"
    level == null -> "Tidak bisa diatur"
    volume.muted -> "Bisu"
    else -> "${(level * 100).roundToInt()}%"
}

/** Latar gelap transparan dengan tepi tipis: terbaca di atas gambar apa pun, juga di atas pita hitam layar penuh. */
private fun Modifier.overlaySurface(alpha: Float = 0.55f) =
    clip(CircleShape)
        .background(Color.Black.copy(alpha = alpha))
        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)

/** Tombol bulat kecil di atas video. */
@Composable
private fun OverlayButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier
            .size(40.dp)
            .overlaySurface()
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}

/** Besar zoom saat ini; ketuk untuk kembali ke tampilan penuh. */
@Composable
private fun ZoomChip(scale: Float, onReset: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .padding(12.dp)
            .overlaySurface()
            .clickable(onClickLabel = "Kembalikan zoom", onClick = onReset)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(String.format(Locale.forLanguageTag("id-ID"), "%.1f×", scale), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Icon(AppIcons.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** Muncul sebentar setiap tombol volume HP ditekan, karena volume HP sendiri tidak berubah. */
@Composable
private fun VolumeHud(tick: Int, volume: VolumeUi, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        visible = true
        delay(1_500)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier.padding(top = 12.dp), enter = fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .overlaySurface(alpha = 0.7f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(volumeIcon(volume), contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            volume.level?.let { level ->
                LinearProgressIndicator(
                    progress = { if (volume.muted) 0f else level },
                    modifier = Modifier.width(96.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                    drawStopIndicator = {},
                )
            }
            Text(volumeLabel(volume), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun VolumeSheet(
    hostName: String,
    volume: VolumeUi,
    volumeKeys: Boolean,
    onStep: (Int) -> Unit,
    onLevel: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // Selama slider digeser, tampilkan posisi jari, bukan status dari komputer yang datang sedikit terlambat.
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Volume $hostName", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val level = volume.level
        when {
            !volume.known -> Text("Membaca volume komputer…", fontSize = 15.sp, color = colors.onSurfaceVariant)
            level == null -> Text(
                "Perangkat audio komputer ini tidak bisa diatur volumenya dari HP, misalnya monitor HDMI di Mac. " +
                    "Bisukan tetap bisa kalau perangkatnya mendukung.",
                fontSize = 15.sp,
                color = colors.onSurfaceVariant,
            )
            else -> {
                val shown = dragging ?: level
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            if (volume.muted) AppIcons.VolumeOff else AppIcons.Volume,
                            contentDescription = if (volume.muted) "Nyalakan suara" else "Bisukan",
                            tint = if (volume.muted) colors.error else colors.onSurface,
                        )
                    }
                    Slider(
                        value = shown,
                        onValueChange = {
                            dragging = it
                            onLevel(it)
                        },
                        onValueChangeFinished = { dragging = null },
                        modifier = Modifier.weight(1f),
                    )
                    Text(volumeLabel(volume, shown), fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(Triple(-1, AppIcons.Minus, "Pelankan"), Triple(1, AppIcons.Plus, "Keraskan")).forEach { (steps, icon, label) ->
                        OutlinedButton(
                            onClick = { onStep(steps) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }
        if (volumeKeys) {
            Text("Tombol volume HP juga mengatur volume komputer selama touchpad terbuka.", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun TouchpadMessage(connected: Boolean, state: ScreenState, platform: String, onRetry: () -> Unit) {
    val text = when {
        !connected -> "Touchpad nonaktif sampai tersambung"
        state == ScreenState.SHOWING -> return
        state == ScreenState.LOADING -> "Memuat layar komputer…"
        state == ScreenState.DENIED && platform == ProtocolConstants.PLATFORM_MACOS ->
            "Mac belum mengizinkan Screen Recording. Izinkan lewat menu Armrest di Mac."
        state == ScreenState.DENIED -> "Komputer menolak menampilkan layar."
        state == ScreenState.FAILED -> "Layar komputer tidak bisa ditampilkan."
        state == ScreenState.UNSUPPORTED -> "Perbarui Armrest di komputer ke versi terbaru untuk melihat layar."
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
        SwitchRow("Buka keyboard otomatis", settings.autoKeyboard, "Saat kolom teks di komputer aktif") {
            onChange(settings.copy(autoKeyboard = it))
        }
        SwitchRow("Tombol volume HP mengatur komputer", settings.volumeKeys, "Selama touchpad terbuka") {
            onChange(settings.copy(volumeKeys = it))
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
        "Cubit (saat layar tampil)" to "Zoom layar",
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
