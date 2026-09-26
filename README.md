# Cursor Controller

Pakai HP Android sebagai touchpad untuk Mac lewat WiFi yang sama: gerak kursor, klik, klik kanan, scroll, dan drag. HP cukup dipasangkan sekali lewat QR, lalu tersambung ulang otomatis.

Status: MVP dalam pengerjaan. Skeleton kedua app dan pipeline CI sudah jalan; fitur dikerjakan per milestone di bawah.

| Folder | Isi |
| --- | --- |
| [`mac-agent/`](mac-agent) | Agent menu bar macOS (Swift, SwiftPM, macOS 13+) |
| [`android-app/`](android-app) | App Android (Kotlin, Jetpack Compose, Android 8+) |
| [`protocol/`](protocol) | [Spesifikasi protokol](protocol/PROTOCOL.md) dan test vector bersama |
| [`.github/workflows/`](.github/workflows) | Build APK dan DMG |
| [`scripts/`](scripts) | Setup signing untuk CI |

## Mengunduh hasil build

- **Setiap push ke `main`**: tab Actions → run **Android** atau **macOS** → bagian Artifacts. File `.apk` dan `.dmg` terunduh langsung, tanpa zip. Perlu login GitHub.
- **Lewat terminal**: `gh run download <run-id> --repo WailanTirajoh/cursor-controller`.
- **Rilis bertag** (`v*`): halaman Releases berisi APK + DMG, bisa diunduh tanpa login.

### Install di Mac

1. Buka DMG, lalu seret **Cursor Controller** ke Applications.
2. App belum dinotarisasi Apple. Saat pertama dibuka: klik kanan → Open (macOS 14), atau System Settings → Privacy & Security → **Open Anyway** (macOS 15+).
3. Ikon app muncul di menu bar. App ini tidak punya ikon di Dock.

### Install di Android

Buka file APK di HP dan izinkan install dari sumber tersebut, atau lewat USB:

```bash
adb install -r cursor-controller-android-0.1.0-b1.apk
```

## Build lokal

Kebutuhan: macOS 13+ dengan Command Line Tools (Xcode tidak wajib), JDK 17, dan Android SDK dengan platform 36. Untuk Android, buat `android-app/local.properties` berisi `sdk.dir=<lokasi Android SDK>` atau set `ANDROID_HOME`.

```bash
make test      # unit test Swift dan Kotlin
make mac       # dist/Cursor Controller.app + DMG, tanda tangan ad-hoc
make android   # APK debug
```

Ikon app Mac dibuat ulang dengan `swift scripts/make-icon.swift Resources/AppIcon.icns` dari folder `mac-agent/`.

## Signing di CI

Tanpa secret, CI tetap jalan: APK ditandatangani debug key dan app Mac ditandatangani ad-hoc. Akibatnya APK baru tidak bisa menimpa yang lama, dan izin Accessibility di Mac hilang setiap update.

Untuk signing yang tetap, jalankan sekali:

```bash
./scripts/setup-signing.sh
```

Skrip ini membuat keystore Android dan sertifikat code signing self-signed, mengisi 6 GitHub Secrets, dan menyimpan backup di `~/cursor-controller-signing/`. Simpan backup itu di password manager: kalau hilang, APK harus di-uninstall dulu dan izin Accessibility perlu diberikan ulang sekali.

## Rencana kerja

| Milestone | Isi |
| --- | --- |
| M0 Spike | WebSocket tanpa TLS, IP diketik manual, HP menggerakkan kursor |
| M1 Input lengkap | Gesture, klik, klik ganda, klik kanan, scroll, drag, akselerasi |
| M2 Discovery | Bonjour di Mac, NSD di Android, daftar komputer online |
| M3 Pairing & keamanan | QR, TLS + pinning sertifikat, keypair Keystore, challenge-response |
| M4 Siap harian | Revoke, indikator sesi, launch at login, heartbeat, reconnect |
