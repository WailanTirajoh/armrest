package io.github.wailantirajoh.cursorcontroller

import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wailantirajoh.cursorcontroller.ui.theme.CursorControllerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val version = packageManager.getPackageInfo(packageName, 0).displayVersion()
        setContent {
            CursorControllerTheme {
                ComputerListScreen(version = version)
            }
        }
    }
}

private fun PackageInfo.displayVersion(): String {
    val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
    return "$versionName (build $build)"
}

/** Mockup A1, state "Kosong". Daftar komputer dan pairing menyusul di M2–M3. */
@Composable
fun ComputerListScreen(version: String) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = "Komputer",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .background(colors.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(112.dp),
                    )
                }
                Text(text = "Belum ada komputer", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Di Mac, klik ikon app di menu bar lalu pilih Tambah perangkat. QR akan muncul untuk dipindai.",
                    textAlign = TextAlign.Center,
                    color = colors.onSurfaceVariant,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Pair komputer baru")
            }
            Text(
                text = "Versi $version · pairing menyusul di M3",
                color = colors.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 16.dp),
            )
        }
    }
}
