package io.github.wailantirajoh.armrest.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.wailantirajoh.armrest.PairingError

/** Mockup A2 (1 dan 5): kamera untuk scan QR, atau penjelasan kalau izin kamera ditolak. */
@Composable
fun ScanScreen(onBack: () -> Unit, onScanned: (String) -> Boolean) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "", navigationIcon = AppIcons.Back, navigationLabel = "Kembali", onNavigate = onBack)
            if (denied) {
                CenteredMessage(
                    icon = AppIcons.CameraOff,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    iconBackground = MaterialTheme.colorScheme.surfaceVariant,
                    title = "Kamera dibutuhkan untuk scan QR",
                    body = "Izinkan akses kamera di Setelan. Gambar kamera hanya dipakai untuk membaca QR dan tidak disimpan.",
                )
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Buka Setelan") }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Kembali") }
                }
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1B19))) {
        QrCameraPreview(onScanned = onScanned, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.background(Color(0x99000000))) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color.White,
                ) {
                    TopBar(title = "Scan QR di komputer", navigationIcon = AppIcons.Back, navigationLabel = "Kembali", onNavigate = onBack)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(250.dp).border(4.dp, Color.White, RoundedCornerShape(24.dp)))
            }
            Column(
                Modifier.fillMaxWidth().background(Color(0x99000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Arahkan kamera ke QR di komputer", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Di komputer: menu Armrest › Tambah perangkat", color = Color(0xFFD4D5CE), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCameraPreview(onScanned: (String) -> Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    var done by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    val image = proxy.image
                    if (image == null || done) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            if (!done && codes.any { code -> code.rawValue?.let(onScanned) == true }) done = true
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            ProcessCameraProvider.getInstance(context).addListener(
                { ProcessCameraProvider.getInstance(context).get().unbindAll() },
                ContextCompat.getMainExecutor(context),
            )
            scanner.close()
        }
    }
}

/** Mockup A2 (2): menunggu user klik Izinkan di komputer. */
@Composable
fun PairingWaitScreen(hostName: String, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "Pair komputer baru")
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 5.dp)
            Text("Menunggu konfirmasi di komputer…", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(
                "Klik Izinkan pada dialog yang muncul di $hostName.",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(24.dp).height(48.dp)) { Text("Batal") }
    }
}

/** Mockup A2 (3 dan 4): pairing gagal. */
@Composable
fun PairingFailedScreen(error: PairingError, onRetry: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (title, body) = when (error) {
        PairingError.FINGERPRINT -> "Koneksi dibatalkan" to
            "Sertifikat komputer tidak sama dengan yang ada di QR. Bisa jadi ada perangkat lain yang menyadap jaringan ini. Tidak ada data yang dikirim."
        PairingError.EXPIRED -> "QR sudah kedaluwarsa" to
            "QR hanya berlaku 120 detik dan sekali pakai. Buat QR baru di komputer, lalu scan lagi."
        PairingError.INVALID -> "QR sudah tidak berlaku" to
            "QR ini sudah dipakai atau sudah diganti. Buat QR baru di komputer, lalu scan lagi."
        PairingError.TOO_MANY -> "Terlalu banyak percobaan" to
            "QR dibatalkan demi keamanan. Buat QR baru di komputer, lalu scan lagi."
        PairingError.DENIED -> "Ditolak di komputer" to
            "Permintaan pairing ditolak di komputer. Kalau itu tidak sengaja, buat QR baru dan coba lagi."
        PairingError.NETWORK -> "Komputer tidak bisa dihubungi" to
            "Pastikan HP dan komputer ada di WiFi yang sama dan Armrest berjalan di komputer."
    }
    val warning = error == PairingError.EXPIRED || error == PairingError.INVALID || error == PairingError.TOO_MANY
    Column(Modifier.fillMaxSize()) {
        TopBar(title = "", navigationIcon = AppIcons.Close, navigationLabel = "Tutup", onNavigate = onClose)
        CenteredMessage(
            icon = when (error) {
                PairingError.FINGERPRINT, PairingError.DENIED -> AppIcons.ShieldX
                PairingError.NETWORK -> AppIcons.LaptopOff
                else -> AppIcons.Clock
            },
            iconTint = if (warning) Tones.warning() else if (error == PairingError.NETWORK) colors.onSurface else Tones.error(),
            iconBackground = if (warning) Tones.warningContainer() else if (error == PairingError.NETWORK) colors.surfaceVariant else Tones.errorContainer(),
            title = title,
            body = body,
        )
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Scan ulang") }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Tutup") }
        }
    }
}
