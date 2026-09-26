# Cursor Controller

Pakai HP Android sebagai touchpad untuk Mac lewat WiFi yang sama: gerak kursor, klik, klik ganda, klik kanan, scroll, dan drag. HP cukup dipasangkan sekali lewat QR, lalu tersambung ulang otomatis.

| Folder | Isi |
| --- | --- |
| [`mac-agent/`](mac-agent) | Agent menu bar macOS (Swift, SwiftPM, macOS 13+) |
| [`android-app/`](android-app) | App Android (Kotlin, Jetpack Compose, Android 8+) |
| [`protocol/`](protocol) | [Spesifikasi protokol](protocol/PROTOCOL.md) dan test vector bersama |
| [`.github/workflows/`](.github/workflows) | Build + test APK dan DMG |
| [`scripts/`](scripts) | Setup signing CI dan uji end-to-end lokal |

## Cara pakai

1. **Mac**: install DMG, buka **Cursor Controller**, lalu ikuti jendela izin **Accessibility**. App ini hanya muncul sebagai ikon di menu bar, tanpa ikon di Dock.
2. **HP**: install APK, lalu buka **Cursor Controller**.
3. **Pairing** (sekali saja):
   1. Di Mac: klik ikon menu bar → **Tambah perangkat…**. QR muncul dan berlaku 120 detik.
   2. Di HP: ketuk **Pair komputer baru**, lalu scan QR. Scan lewat kamera bawaan HP juga bisa; app akan terbuka otomatis.
   3. Di Mac: klik **Izinkan**.
4. Selanjutnya cukup ketuk nama Mac di HP. Kalau koneksi putus, HP menyambung ulang sendiri.

| Gesture di HP | Hasil di Mac |
| --- | --- |
| Geser 1 jari | Gerakkan kursor |
| Tap | Klik kiri |
| Tap 2 kali | Klik ganda |
| Tap 2 jari | Klik kanan |
| Geser 2 jari | Scroll (arah mengikuti setelan natural scrolling Mac) |
| Tap, lalu tahan dan geser | Drag |

### Keyboard

Di layar touchpad, ketuk ikon keyboard di kanan atas. Apa yang kamu ketik langsung muncul di app yang sedang aktif di Mac, termasuk Backspace dan koreksi otomatis. Tombol Enter di keyboard HP mengirim Return.

Baris di atas kolom ketik berisi Esc, Tab, dan tombol panah, plus ⌘ ⌃ ⌥ ⇧ untuk shortcut. Contohnya, ketuk ⌘ lalu ketik `c` untuk ⌘C. Modifier hanya berlaku untuk satu tombol berikutnya. Touchpad tetap bisa dipakai selama keyboard terbuka.

### Pengaturan

Sensitivitas, kecepatan scroll, tombol Kiri/Kanan, dan getar saat klik diatur lewat ikon pengaturan di layar touchpad. Akses HP bisa dicabut kapan saja lewat **Cabut…** di menu bar Mac.

### Kalau ada masalah

- **HP tidak menemukan Mac** (WiFi kantor atau hotspot sering memblokir mDNS): di HP, menu ⋮ pada kartu komputer → **Sambungkan via IP**. Alamatnya tertulis di panel menu bar Mac (mis. `Siap · 192.168.1.20:47810`).
- **Kursor tidak bergerak atau ketikan tidak masuk**: pastikan panel menu bar Mac menampilkan "Izin Accessibility: Diizinkan".
- **Ketikan tidak masuk ke kolom password**: macOS bisa memblokir ketikan dari app lain saat Secure Input aktif, misalnya di kolom password atau Terminal dengan Secure Keyboard Entry.
- **macOS menolak membuka app**: app belum dinotarisasi Apple. Klik kanan → Open (macOS 14), atau System Settings → Privacy & Security → **Open Anyway** (macOS 15+).

## Mengunduh hasil build

- **Setiap push ke `main`**: tab Actions → run **Android** atau **macOS** → bagian Artifacts. File `.apk` dan `.dmg` terunduh langsung tanpa zip, dan perlu login GitHub.
- **Lewat terminal**: `gh run download <run-id> --repo WailanTirajoh/cursor-controller`.
- **Rilis bertag** (`v*`): halaman Releases berisi APK + DMG, bisa diunduh tanpa login.

## Build dan test lokal

Kebutuhan: macOS 13+ dengan Command Line Tools (Xcode tidak wajib), JDK 17, dan Android SDK dengan platform 37. Untuk Android, buat `android-app/local.properties` berisi `sdk.dir=<lokasi Android SDK>` atau set `ANDROID_HOME`.

```bash
make test      # unit test Swift dan Kotlin
make mac       # dist/Cursor Controller.app + DMG, tanda tangan ad-hoc
make android   # APK debug
```

Uji end-to-end menjalankan agent Mac sungguhan (TLS, Keychain, WebSocket) dengan profil terpisah, lalu klien Android di JVM melakukan pairing, auth, input, sambung ulang, dan skenario penolakan. Semua data uji dihapus di akhir.

```bash
./scripts/e2e-local.sh
```

Agent juga bisa diuji dengan emulator Android: jalankan agent dengan `CURSORCTL_PROFILE=e2e CURSORCTL_E2E_ADDRESS=10.0.2.2 CURSORCTL_E2E_AUTO_APPROVE=1 CURSORCTL_E2E_PAIRING_FILE=<file>`, lalu buka isi file itu di emulator lewat `adb shell am start -a android.intent.action.VIEW -d '<uri>'`.

## Signing di CI

Tanpa secret, CI tetap jalan: APK ditandatangani debug key dan app Mac ditandatangani ad-hoc. Akibatnya APK baru tidak bisa menimpa yang lama, dan izin Accessibility serta akses Keychain di Mac perlu diberikan ulang setiap update.

Untuk signing yang tetap, jalankan sekali:

```bash
./scripts/setup-signing.sh
```

Skrip ini membuat keystore Android dan sertifikat code signing self-signed, mengisi 6 GitHub Secrets, dan menyimpan backup di `~/cursor-controller-signing/`. Simpan backup itu di password manager.

## Setelah MVP

Urutan berikutnya dari spec: mode presentasi/media, transport UDP, air mouse (gyroscope), koneksi lintas jaringan (WebRTC), lalu dukungan Windows/Linux.
