# Cursor Controller

Pakai HP Android sebagai touchpad dan keyboard untuk Mac atau PC Windows lewat WiFi yang sama: gerak kursor, klik, klik ganda, klik kanan, scroll, drag, dan mengetik. Layar komputer juga bisa ditampilkan di HP. HP cukup dipasangkan sekali lewat QR, lalu tersambung ulang otomatis.

| Folder | Isi |
| --- | --- |
| [`mac-agent/`](mac-agent) | Agent menu bar macOS (Swift, SwiftPM, macOS 13+) |
| [`windows-agent/`](windows-agent) | Agent tray Windows 10/11 (C# .NET 10, WPF), x64 dan ARM64 |
| [`android-app/`](android-app) | App Android (Kotlin, Jetpack Compose, Android 8+) |
| [`protocol/`](protocol) | [Spesifikasi protokol](protocol/PROTOCOL.md), [profil uji e2e](protocol/E2E.md), dan test vector bersama |
| [`.github/workflows/`](.github/workflows) | Build + test APK, DMG, dan installer Windows |
| [`scripts/`](scripts) | Setup signing CI dan uji end-to-end |

## Cara pakai

1. **Komputer**:
   - **Mac**: install DMG, buka **Cursor Controller**, lalu ikuti jendela izin **Accessibility**. App ini hanya muncul sebagai ikon di menu bar, tanpa ikon di Dock.
   - **Windows 10/11**: jalankan installer `.exe`: `x64` untuk kebanyakan PC, `arm64` untuk Windows on ARM (termasuk VM di Mac Apple Silicon).
     - Installer belum ditandatangani, jadi SmartScreen memperingatkan: pilih **More info → Run anyway**.
     - Cursor Controller lalu berjalan di area notifikasi (tray) di pojok kanan bawah. Tidak ada izin tambahan.
2. **HP**: install APK, lalu buka **Cursor Controller**.
3. **Pairing** (sekali saja):
   1. Di komputer: klik ikon Cursor Controller (menu bar Mac, atau tray Windows) → **Tambah perangkat…**. QR muncul dan berlaku 120 detik.
   2. Di HP: ketuk **Pair komputer baru**, lalu scan QR. Scan lewat kamera bawaan HP juga bisa; app akan terbuka otomatis.
   3. Di komputer: klik **Izinkan**.
4. Selanjutnya cukup ketuk nama komputer di HP. Kalau koneksi putus, HP menyambung ulang sendiri.

| Gesture di HP | Hasil di komputer |
| --- | --- |
| Geser 1 jari | Gerakkan kursor |
| Tap | Klik kiri |
| Tap 2 kali | Klik ganda |
| Tap 2 jari | Klik kanan |
| Geser 2 jari | Scroll dengan arah natural seperti touchpad. Di Mac mengikuti setelan natural scrolling. |
| Tap, lalu tahan dan geser | Drag |

### Keyboard

Di layar touchpad, ketuk ikon keyboard di kanan atas. Apa yang kamu ketik langsung muncul di app yang sedang aktif di komputer, termasuk Backspace dan koreksi otomatis. Tombol Enter di keyboard HP mengirim Return/Enter.

Baris di atas kolom ketik berisi Esc, Tab, dan tombol panah, plus modifier untuk shortcut: ⌘ ⌃ ⌥ ⇧ untuk Mac, atau Ctrl Win Alt Shift untuk Windows. Contohnya, ketuk ⌘ lalu ketik `c` untuk ⌘C di Mac, atau Ctrl lalu `c` untuk Ctrl+C di Windows. Modifier hanya berlaku untuk satu tombol berikutnya. Touchpad tetap bisa dipakai selama keyboard terbuka.

Keyboard juga terbuka sendiri saat kolom teks di komputer aktif, misalnya setelah kamu mengklik kolom pencarian, dan tertutup lagi saat fokus pindah dari kolom teks. Keyboard yang kamu buka manual tidak ditutup otomatis, dan tombol Back di HP menutup panel keyboard. Matikan lewat **Buka keyboard otomatis** di pengaturan touchpad.

### Layar komputer di HP

Ketuk ikon monitor di kanan atas layar touchpad. Layar komputer, termasuk kursornya, tampil di area touchpad, dan semua gesture tetap berfungsi di atasnya. Jadi kamu bisa melihat posisi kursor tanpa melihat ke komputer. Dengan beberapa monitor, yang tampil adalah monitor tempat kursor berada. Miringkan HP untuk gambar yang lebih besar.

- **Mac**: pertama kali dipakai, Mac meminta izin **Screen Recording**. Nyalakan Cursor Controller di System Settings, lalu pilih **Quit & Reopen**. Selama layar tampil di HP, macOS menampilkan indikator perekaman layar di menu bar.
- **Windows**: tidak perlu izin.

Panel Cursor Controller menulis "melihat layar" di sesi HP yang sedang menampilkan layar. Video hanya dikirim selama app di HP terbuka; ketuk ikon monitor lagi untuk berhenti.

### Pengaturan

Sensitivitas, kecepatan scroll, tombol Kiri/Kanan, getar saat klik, dan keyboard otomatis diatur lewat ikon pengaturan di layar touchpad. Akses HP bisa dicabut kapan saja lewat **Cabut…** di panel Cursor Controller di komputer.

### Kalau ada masalah

Umum:

- **HP tidak menemukan komputer** (WiFi kantor atau hotspot sering memblokir mDNS): di HP, menu ⋮ pada kartu komputer → **Sambungkan via IP**. Alamatnya tertulis di panel Cursor Controller (mis. `Siap · 192.168.1.20:47810`).
- **Keyboard tidak terbuka otomatis**: sebagian app tidak melaporkan kolom teksnya, misalnya game atau remote desktop. VS Code, Slack, dan app Electron lain sengaja tidak dicek, karena VS Code lalu mengira ada screen reader. Di app seperti itu, buka keyboard lewat ikonnya.
- **Sebagian layar tampil hitam**: video yang dilindungi DRM (Netflix, Apple TV+) dan jendela yang memblokir tangkapan layar.

Mac:

- **Kursor tidak bergerak atau ketikan tidak masuk**: pastikan panel menu bar Mac menampilkan "Izin Accessibility: Diizinkan".
- **Layar tidak muncul di HP**: pastikan panel menu bar Mac menampilkan "Izin Screen Recording: Diizinkan". Kalau toggle di System Settings sudah menyala tapi tetap ditolak (biasanya setelah update build tanpa signing), jalankan `tccutil reset ScreenCapture io.github.wailantirajoh.cursorcontroller.agent`, lalu izinkan ulang.
- **Ketikan tidak masuk ke kolom password**: macOS bisa memblokir ketikan dari app lain saat Secure Input aktif, misalnya di kolom password atau Terminal dengan Secure Keyboard Entry.
- **macOS menolak membuka app**: app belum dinotarisasi Apple. Klik kanan → Open (macOS 14), atau System Settings → Privacy & Security → **Open Anyway** (macOS 15+).

Windows:

- **HP tidak bisa tersambung**: installer hanya membuka firewall untuk jaringan **Private** dan Domain. Kalau WiFi-nya berprofil Public, ubah di Settings → Network & internet → Wi-Fi → nama jaringan → **Private network**.
- **Sebagian jendela tidak bisa dikontrol**: jendela yang berjalan sebagai administrator (mis. Task Manager, installer), dialog UAC, dan layar kunci. Ini batasan Windows untuk app biasa.
- **Tidak ada ikon di taskbar**: ikonnya ada di tray. Klik panah ^ di pojok kanan bawah kalau tersembunyi. Membuka Cursor Controller lagi dari Start menu juga menampilkan panelnya.

## Mengunduh hasil build

- **Setiap push ke `main`**: tab Actions → run **Android**, **macOS**, atau **Windows** → bagian Artifacts. File `.apk`, `.dmg`, dan installer `.exe` (x64 dan arm64) terunduh langsung tanpa zip, dan perlu login GitHub.
- **Lewat terminal**: `gh run download <run-id> --repo WailanTirajoh/cursor-controller`.
- **Rilis bertag** (`v*`): halaman Releases berisi semuanya, bisa diunduh tanpa login.

## Build dan test lokal

Kebutuhan:

- **Mac**: macOS 13+ dengan Command Line Tools (Xcode tidak wajib).
- **Android**: JDK 17 dan Android SDK dengan platform 37. Buat `android-app/local.properties` berisi `sdk.dir=<lokasi Android SDK>`, atau set `ANDROID_HOME`.
- **Windows**: .NET 10 SDK. Agent Windows bisa dibangun dan dites dari macOS/Linux, tapi hanya bisa dijalankan di Windows.

```bash
make test      # unit test Swift, Kotlin, dan C#
make mac       # dist/Cursor Controller.app + DMG, tanda tangan ad-hoc
make android   # APK debug
make windows   # agent Windows (installer dibuat CI)
```

Uji end-to-end menjalankan agent sungguhan dengan profil uji terpisah, lalu klien Android di JVM melakukan pairing, auth, input, fokus, layar, sambung ulang, dan skenario penolakan. Semua data uji dihapus di akhir; kontraknya ada di [protocol/E2E.md](protocol/E2E.md).

```bash
./scripts/e2e-local.sh      # agent Mac, lalu inti agent Windows (C#) tanpa layar
```

```powershell
./scripts/e2e-windows.ps1   # di Windows: agent Windows lengkap, termasuk encoder video
```

Agent juga bisa diuji dengan emulator Android: jalankan agent dengan `CURSORCTL_PROFILE=e2e CURSORCTL_E2E_ADDRESS=10.0.2.2 CURSORCTL_E2E_AUTO_APPROVE=1 CURSORCTL_E2E_PAIRING_FILE=<file>`, lalu buka isi file itu di emulator lewat `adb shell am start -a android.intent.action.VIEW -d '<uri>'`.

## Signing di CI

Tanpa secret, CI tetap jalan: APK ditandatangani debug key dan app Mac ditandatangani ad-hoc. Akibatnya APK baru tidak bisa menimpa yang lama, dan izin Accessibility, Screen Recording, serta akses Keychain di Mac perlu diberikan ulang setiap update. Installer Windows belum ditandatangani, jadi SmartScreen selalu memperingatkan; identitas TLS dan perangkat terpercaya di Windows tetap tersimpan antar-update.

Untuk signing Android dan Mac yang tetap, jalankan sekali:

```bash
./scripts/setup-signing.sh
```

Skrip ini membuat keystore Android dan sertifikat code signing self-signed, mengisi 6 GitHub Secrets, dan menyimpan backup di `~/cursor-controller-signing/`. Simpan backup itu di password manager.

## Berikutnya

App iPhone (sedang direncanakan), lalu dari spec: mode presentasi/media, transport UDP, air mouse (gyroscope), koneksi lintas jaringan (WebRTC), dan dukungan Linux.
