package io.github.wailantirajoh.cursorcontroller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Warna tambahan dari mockup yang tidak ada di ColorScheme Material. */
object Tones {
    @Composable
    fun warning(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFF2B37A) else Color(0xFF9A4A06)

    @Composable
    fun warningContainer(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4A2B0F) else Color(0xFFFCE9D6)

    @Composable
    fun error(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFF2B8B5) else Color(0xFFB3261E)

    @Composable
    fun errorContainer(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF5C1A15) else Color(0xFFFBE3E1)

    private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}

/** Baris judul: tombol kiri opsional, judul, aksi di kanan. */
@Composable
fun TopBar(
    title: String,
    navigationIcon: ImageVector? = null,
    navigationLabel: String? = null,
    onNavigate: () -> Unit = {},
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (navigationIcon == null) 20.dp else 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            IconButton(onClick = onNavigate) { Icon(navigationIcon, contentDescription = navigationLabel) }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = if (subtitle == null) 22.sp else 16.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.invoke()
        }
        actions()
    }
}

/** Layar pesan di tengah: ikon dalam lingkaran, judul, penjelasan, lalu tombol di bawah. */
@Composable
fun ColumnScope.CenteredMessage(
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color,
    title: String,
    body: String,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(iconBackground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(48.dp))
        }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        extra()
    }
}
