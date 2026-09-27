package io.github.wailantirajoh.armrest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wailantirajoh.armrest.HostUi
import io.github.wailantirajoh.armrest.core.ProtocolConstants
import io.github.wailantirajoh.armrest.data.SavedHost
import java.text.DateFormat
import java.util.Date

/** Mockup A1: daftar komputer. */
@Composable
fun HostsScreen(
    hosts: List<HostUi>,
    manualAddressFor: SavedHost?,
    onScan: () -> Unit,
    onConnect: (SavedHost) -> Unit,
    onRequestManual: (SavedHost?) -> Unit,
    onConnectManually: (SavedHost, String) -> Unit,
    onRemove: (SavedHost) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "Komputer")
            if (hosts.isEmpty()) {
                CenteredMessage(
                    icon = AppIcons.Laptop,
                    iconTint = colors.onPrimaryContainer,
                    iconBackground = colors.primaryContainer,
                    title = "Belum ada komputer",
                    body = "Di komputer, buka Armrest (ikon di menu bar Mac atau di tray Windows), lalu pilih Tambah perangkat. QR akan muncul untuk dipindai.",
                )
                Button(
                    onClick = onScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .height(48.dp),
                ) {
                    Icon(AppIcons.Qr, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Pair komputer baru")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                ) {
                    items(hosts, key = { it.host.hostId }) { item ->
                        HostCard(item, onConnect = { onConnect(item.host) }, onManual = { onRequestManual(item.host) }, onRemove = { onRemove(item.host) })
                    }
                    item {
                        Text(
                            "Ketuk komputer untuk mulai memakai touchpad. HP dan komputer harus di WiFi yang sama.",
                            fontSize = 13.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
        if (hosts.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(AppIcons.Plus, contentDescription = null) },
                text = { Text("Pair komputer baru") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            )
        }
    }

    manualAddressFor?.let { host ->
        ManualAddressDialog(host, onDismiss = { onRequestManual(null) }, onSubmit = { onConnectManually(host, it) })
    }
}

@Composable
private fun HostCard(item: HostUi, onConnect: () -> Unit, onManual: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(20.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onConnect)
            .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (item.host.platform == ProtocolConstants.PLATFORM_WINDOWS) AppIcons.Monitor else AppIcons.Laptop,
                contentDescription = null,
                tint = if (item.online) colors.onSurface else colors.onSurfaceVariant,
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.host.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                StatusChip(item.online)
            }
            Text(
                "Terakhir dipakai ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.host.lastUsed))}",
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "Opsi ${item.host.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Sambungkan via IP…") }, onClick = { menu = false; onManual() })
                DropdownMenuItem(text = { Text("Hapus dari HP ini") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun StatusChip(online: Boolean) {
    val colors = MaterialTheme.colorScheme
    val modifier = if (online) {
        Modifier.background(colors.primaryContainer, CircleShape)
    } else {
        Modifier.border(1.dp, colors.outline, CircleShape)
    }
    Text(
        if (online) "Online" else "Offline",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (online) colors.onPrimaryContainer else colors.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
private fun ManualAddressDialog(host: SavedHost, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var address by remember { mutableStateOf(host.lastAddress) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sambungkan via IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Pakai ini kalau ${host.name} tidak ditemukan otomatis, misalnya karena WiFi memblokir mDNS. Alamatnya tertulis di menu Armrest di komputer.")
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Alamat IP dan port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Text("Sertifikat komputer tetap dicek, jadi koneksi tetap aman.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(address) }) { Text("Sambungkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
