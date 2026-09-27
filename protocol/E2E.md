# Profil uji end-to-end

Semua agent (Mac dan Windows) punya profil uji yang sama, supaya bisa diuji oleh klien yang sama:
`android-app/core/src/test/.../AgentConnectionE2ETest.kt`, yaitu kode koneksi app Android yang dijalankan di JVM.
Klien itu melakukan pairing, autentikasi, kirim input, fokus kolom teks, layar, sambung ulang, dan tiga skenario
penolakan (fingerprint salah, token dipakai ulang, perangkat tidak dikenal).

## Variabel environment agent

| Variabel | Arti |
| --- | --- |
| `CURSORCTL_PROFILE` | Nama profil. Data, identitas TLS, dan pengaturan terpisah dari app normal (mis. `e2e`). Mode tanpa UI tanpa nama profil memakai `e2e`, jadi uji tidak pernah menyentuh data app normal. |
| `CURSORCTL_PORT` | Port server, mis. `47811` supaya tidak bentrok dengan app yang sedang dipakai. |
| `CURSORCTL_E2E_ADDRESS` | Alamat di QR: `127.0.0.1`, atau `10.0.2.2` untuk emulator Android. |
| `CURSORCTL_E2E_AUTO_APPROVE=1` | Permintaan pairing langsung diizinkan. |
| `CURSORCTL_E2E_PAIRING_FILE` | Agent menulis URI QR ke file ini begitu server siap. Variabel ini juga menyalakan mode tanpa UI. |
| `CURSORCTL_E2E_FOCUS_FILE` | Status fokus kolom teks dibaca dari file ini: `1` = kolom teks fokus. |
| `CURSORCTL_E2E_LOG=1` | Log ke stderr: `server: …`, `features: …`, `input: …`, `focus: …`, `screen: …`, `volume: …`. |

## Perilaku mode tanpa UI

- **Tanpa jendela, tanpa input sungguhan**: input dari HP hanya dicatat, tidak pernah menggerakkan kursor atau mengetik.
- **Fokus kolom teks**: dari file di atas, atau selalu "bukan kolom teks" kalau tidak di-set. Tidak pernah membaca app lain.
- **Layar**: pola uji bergerak, seolah layar 1440 × 900, lewat encoder H.264 sungguhan. Tidak pernah menangkap layar asli.
  - HP uji meminta 1920 × 1080, jadi `screen_config` harus berukuran 1440 × 900.
  - Posisi kursor (`screen_cursor`) tiruan: di tengah balok yang bergerak, `y` = 0,725.
- **Volume**: tiruan di memori, mulai dari `level` 0,5 dan tidak bisu. Tidak pernah mengubah volume sungguhan.
- **Fitur `screen`** hanya diumumkan kalau encoder tersedia.
  - Agent tanpa encoder: host C# di macOS/Linux, atau Windows Server tanpa Media Foundation.
  - Untuk agent seperti itu, jalankan klien dengan `CURSORCTL_E2E_EXPECT_SCREEN=0`.

## Menjalankan

| Agent | Perintah |
| --- | --- |
| Mac, dan inti C# agent Windows (tanpa layar) | `./scripts/e2e-local.sh` |
| Windows (juga dijalankan CI) | `./scripts/e2e-windows.ps1` |
