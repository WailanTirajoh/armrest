# Protokol Cursor Controller

Versi protokol: **1** (field `v` di pesan `hello`). Agent menolak versi lain dengan `error` `unsupported_version`, lalu menutup koneksi.

## Transport

- WebSocket di atas TLS (`wss://`) di jaringan lokal, port default **47810**, dengan `TCP_NODELAY`.
- Sertifikat agent self-signed (ECDSA P-256). HP tidak memakai CA: HP mencocokkan **fingerprint** = base64url tanpa padding dari SHA-256 sertifikat (DER). Fingerprint dibawa lewat QR saat pairing, lalu disimpan.
- Agent mengiklankan diri lewat Bonjour dengan tipe `_cursorctl._tcp`. TXT record berisi `hostId`, `v`, dan `os` (`macos` atau `windows`).
- Pesan kontrol memakai **text frame JSON**. Event input (HP → Mac) dan video layar (Mac → HP) memakai **binary frame**.

## Format nilai

| Nilai | Format |
| --- | --- |
| `hostId`, `deviceId` | UUID huruf kecil, dibuat sekali dan disimpan |
| `token` | 32 byte acak, base64url tanpa padding |
| `nonce` | 32 byte acak, base64 standar |
| `publicKey` | kunci publik P-256 dalam DER X.509 SubjectPublicKeyInfo, base64 standar |
| `sig` | ECDSA P-256 dengan SHA-256, encoding DER, base64 standar |
| Payload `auth` | `nonce (32 byte) ‖ UTF-8(hostId) ‖ UTF-8(deviceId)` |

## Pesan kontrol (JSON, field `t` = tipe)

| Arah | `t` | Isi | Kapan |
| --- | --- | --- | --- |
| HP → Mac | `hello` | `v`, `deviceId`, `mode` (`pair` / `auth`) | Pertama setelah WebSocket terbuka |
| HP → Mac | `pair_request` | `token`, `deviceName`, `publicKey` | Mode pair |
| Mac → HP | `pair_result` | `ok: true`, `hostId`, `hostName`, atau `ok: false`, `error` | Setelah user klik Izinkan/Tolak, atau token ditolak |
| Mac → HP | `challenge` | `nonce` | Mode auth, atau langsung setelah `pair_result` ok |
| HP → Mac | `auth` | `sig` | Balasan challenge |
| Mac → HP | `auth_result` | `ok: true` dengan `features` dan `platform` (opsional), atau `ok: false`, `error` | Setelah verifikasi |
| HP → Mac | `settings` | `sensitivity`, `scrollSpeed`, `focusUpdates` | Setelah `auth_result` ok, dan setiap kali diubah |
| Mac → HP | `focus` | `text` (bool) | Setelah HP meminta lewat `focusUpdates`, lalu setiap kali berubah |
| HP → Mac | `screen` | `on: true`, `maxWidth`, `maxHeight`, atau `on: false` | Mulai atau berhenti melihat layar Mac; permintaan ulang = minta keyframe |
| Mac → HP | `screen_status` | `state`: `streaming`, `denied`, `failed` | Setelah `screen` on, dan saat aliran berhenti karena error |
| HP → Mac | `screen_ack` | `seq` | Setiap frame layar yang diterima |
| Dua arah | `ping` / `pong` | `ts` (ms) | Tiap 5 detik; koneksi ditutup kalau 15 detik tidak ada pesan masuk |
| Mac → HP | `error` | `error` | Pesan tidak valid atau versi tidak didukung, lalu koneksi ditutup |

"Mac" di tabel di atas berarti komputer yang menjalankan agent, termasuk Windows. Field opsional di `auth_result`:

- `features`: fitur opsional agent, yaitu `focus` dan `screen`.
- `platform`: `macos` atau `windows`. HP memakainya untuk label modifier (⌘ ⌃ ⌥ ⇧ atau Ctrl Win Alt Shift), ikon, dan teks. Kalau tidak ada (agent sebelum v0.6), nilainya `macos`.

Kode `error`:

| Pesan | Kode |
| --- | --- |
| `pair_result` | `token_invalid`, `token_expired`, `too_many_attempts`, `denied` |
| `auth_result` | `unknown_device`, `bad_sig` |
| `error` | `unsupported_version`, `bad_message` |

## Event input (binary, little-endian, byte pertama = tipe)

Hanya diproses setelah `auth_result` ok. Event yang datang sebelumnya dibuang.

| Tipe | Byte | Payload | Arti |
| --- | --- | --- | --- |
| `0x01` move | 5 | `dx: i16`, `dy: i16` | Delta dalam satuan 0,1 dp layar HP |
| `0x02` button | 3 | `button: u8` (0 kiri, 1 kanan), `state: u8` (1 down, 0 up) | Untuk drag |
| `0x03` click | 3 | `button: u8`, `count: u8` (1 atau 2) | Klik tunggal atau ganda |
| `0x04` scroll | 5 | `dx: i16`, `dy: i16` | Delta scroll dalam 0,1 dp |
| `0x05` text | 2–1025 | teks UTF-8, 1–1024 byte | Ketik teks di app yang sedang aktif; `\n` = Return, `\t` = Tab |
| `0x06` key | 3 | `key: u8`, `modifiers: u8` | Tekan satu tombol sambil menahan modifier |

Frame dengan panjang yang salah, tipe tidak dikenal, atau nilai di luar rentang dibuang. Untuk `text`, itu termasuk UTF-8 yang tidak valid dan karakter kontrol selain `\n` dan `\t`.

Kode `key`:

| Kode | Tombol |
| --- | --- |
| `0x01`–`0x06` | Return, Backspace, Tab, Esc, Space, Forward Delete |
| `0x07`–`0x0A` | ←, →, ↑, ↓ |
| `0x0B`–`0x0E` | Home, End, Page Up, Page Down |
| `0x10`–`0x1B` | F1–F12 |
| `0x20`–`0x39` | A–Z |
| `0x40`–`0x49` | 0–9 |
| `0x50`–`0x5A` | `-` `=` `[` `]` `\` `;` `'` `,` `.` `/` `` ` `` |

Bit `modifiers`: `0x01` Shift, `0x02` Control, `0x04` Option, `0x08` Command. Bit lain harus 0. Huruf dan tanda baca memakai posisi tombol ANSI, jadi ⌘C selalu tombol di posisi C.

Bit modifier berarti posisi tombol fisik. Di Windows: Control = Ctrl, Option = Alt, Command = tombol Windows, jadi Ctrl+C dikirim sebagai `C` dengan bit Control. Agent Windows menekan huruf lewat scan code posisi ANSI, sama dengan agent Mac.

Aturan:

- HP mengirim delta **mentah**. Akselerasi dihitung di agent: `gain = sensitivity × min(6, 1 + 2 × max(0, v − 0,2))`, dengan `v` dalam dp/ms. Sisa pecahan piksel disimpan antar paket.
- `scroll` dikalikan `scrollSpeed` tanpa akselerasi. Arahnya mengikuti setelan natural scrolling di Mac.
- HP mengumpulkan delta per frame dan mengirim maksimal satu `move` per frame. Kalau antrean kirim menumpuk, delta digabung ke paket berikutnya. `click`, `button`, `text`, dan `key` tidak pernah digabung atau dibuang, dan selalu dikirim setelah gerakan yang tertunda.
- `text` diketik lewat event Unicode, jadi tidak bergantung pada layout keyboard Mac. Backspace dikirim sebagai `key` `0x02`.

## Fokus kolom teks

Supaya HP bisa membuka dan menutup keyboard sendiri, agent memberi tahu apakah elemen yang sedang fokus di Mac adalah kolom teks.

- HP meminta dengan `focusUpdates: true` di pesan `settings`, dan berhenti dengan `false`. Kalau field ini tidak ada (HP v0.3), nilainya `false`.
- Agent langsung mengirim status saat ini, lalu mengirim ulang setiap kali berubah: `{"t":"focus","text":true}`.
- Agent memeriksa elemen yang fokus lewat Accessibility setiap 250 ms, hanya selama ada HP yang meminta. Yang dihitung kolom teks: role `AXTextField` (termasuk kolom password dan pencarian), `AXTextArea`, `AXComboBox`, atau elemen di dalam area yang bisa diedit (`AXEditableAncestor`, mis. editor contenteditable di browser).
- Fokus masuk ke kolom teks langsung dikirim. Fokus keluar baru dikirim setelah bertahan 500 ms, supaya pindah antar kolom tidak membuat keyboard HP tertutup lalu terbuka lagi.
- HP v0.3 mengabaikan pesan `focus`, dan agent v0.3 mengabaikan `focusUpdates`.

## Layar Mac

HP bisa menampilkan layar Mac, misalnya di belakang area touchpad. Videonya H.264, dikirim sebagai binary frame di koneksi yang sama.

- **Fitur**: agent yang mendukung mengirim `features: ["focus", "screen"]` di `auth_result`. HP hanya mengirim `screen` kalau ada `"screen"`, karena agent lama menutup koneksi saat menerima pesan yang tidak dikenal.
- **Mulai**: HP mengirim `screen` dengan `maxWidth` dan `maxHeight` dalam piksel, biasanya ukuran layar HP. Agent membalas `screen_status` `streaming`, lalu mengirim `screen_config` dan keyframe.
- **Izin**: kalau Mac belum memberi izin Screen Recording, agent membalas `screen_status` `denied` tanpa mengirim video. Kalau tangkapan gagal atau berhenti karena error, statusnya `failed`. Dalam dua kasus itu HP boleh mengirim `screen` lagi untuk mencoba ulang.
- **Keyframe**: `screen` on yang dikirim lagi selama aliran berjalan berarti decoder HP butuh keyframe, misalnya setelah Surface dibuat ulang. Agent mengirim `screen_config` lalu keyframe dari gambar terakhir, juga saat layar sedang diam.
- **Berhenti**: `screen` off, atau koneksi putus.

Binary frame Mac → HP (little-endian, byte pertama = tipe):

| Tipe | Byte | Isi | Arti |
| --- | --- | --- | --- |
| `0x81` screen_config | 7+ | `codec: u8` (1 = H.264), `width: u16`, `height: u16`, SPS dan PPS | Ukuran video dan parameter decoder, dikirim sebelum setiap keyframe |
| `0x82` screen_frame | 7+ | `seq: u32`, `flags: u8` (bit 0 = keyframe), access unit | Satu frame video |

SPS, PPS, dan access unit memakai format Annex B (setiap NAL diawali `00 00 00 01`). Frame dengan tipe atau codec tidak dikenal, ukuran 0, flag selain bit 0, atau tanpa isi dibuang.

Aturan video:

- H.264 tanpa B-frame (profil Baseline, Main, atau High), jadi setiap frame bisa langsung ditampilkan. Agent Mac memakai Constrained High, agent Windows Main. Paling besar 1920 × 1200 dan 30 fps, rasio mengikuti layar, sisi genap, warna BT.709.
- Layar yang diam tidak menghasilkan frame. Kursor ikut tergambar. Dengan beberapa monitor, agent mengikuti monitor tempat kursor berada; kalau ukurannya berubah, `screen_config` baru mendahului keyframe berikutnya.
- **Kontrol aliran**: `seq` naik satu per frame. HP mengirim `screen_ack` untuk setiap frame yang diterima, dan konfirmasinya kumulatif. Agent menahan frame baru selama ada 4 frame yang belum dikonfirmasi, lalu mengirim gambar terbaru begitu ada konfirmasi. Jadi saat WiFi lambat gambar dilewati, bukan menumpuk.
- HP menyalakan video hanya selama app terlihat, supaya WiFi dan baterai tidak terpakai sia-sia.

## QR pairing

```
cursorctl://pair?h=<hostId>&n=<hostName>&a=<ip>:<port>&t=<token>&fp=<fingerprint>
```

`n` di-percent-encode. Alamat `a` hanya dipakai saat pairing; setelah itu HP mencari Mac lewat Bonjour, dengan alamat terakhir sebagai cadangan. App Android juga menerima URI ini sebagai deep link.

### Alur pairing

1. User klik **Tambah perangkat** di menu bar. Agent membuat token (TTL 120 detik, sekali pakai) dan menampilkan QR.
2. HP scan QR, lalu membuka `wss://<a>` dengan pinning `fp`. Kalau fingerprint tidak cocok, koneksi dibatalkan.
3. HP kirim `hello` (mode `pair`), lalu `pair_request`.
4. Agent memeriksa token. Token langsung hangus begitu dipakai, baik hasilnya diizinkan maupun ditolak. Agent lalu menampilkan dialog "Izinkan <deviceName>?".
5. Izinkan: agent menyimpan perangkat dan mengirim `pair_result` ok, lalu langsung `challenge`. HP menyimpan host dan menjawab `auth` di koneksi yang sama.

### Alur koneksi ulang

1. HP resolve `_cursorctl._tcp` lewat NSD dan mencocokkan `hostId` dari TXT record. Kalau tidak ketemu, HP mencoba alamat terakhir.
2. HP membuka `wss` dengan pinning fingerprint tersimpan, lalu kirim `hello` (mode `auth`).
3. Agent kirim `challenge`, HP menandatangani payload dengan kunci di Android Keystore.
4. Agent verifikasi dengan public key tersimpan, kirim `auth_result` ok, dan memperbarui `lastSeen`.

### Aturan keamanan

- Token pairing sekali pakai, TTL 120 detik, dibatalkan setelah 5 percobaan salah.
- Nonce baru untuk setiap koneksi. Agent membuang event input sebelum autentikasi selesai.
- Hanya satu sesi aktif per perangkat. Koneksi baru dari perangkat yang sama menutup sesi lama.
- Revoke = hapus perangkat dari daftar terpercaya dan putus sesinya.

## Test vector

`vectors/` dipakai test Swift dan Kotlin, supaya kedua sisi dijamin sepakat:

- `input.json`: encoding biner setiap tipe event, plus frame yang harus ditolak.
- `auth.json`: payload, public key, dan signature buatan openssl (valid dan tidak valid), plus contoh fingerprint.
- `screen.json`: encoding `screen_config` dan `screen_frame`, frame yang harus ditolak, dan konversi NAL berawalan panjang ke Annex B.

Uji end-to-end memakai profil uji yang sama di setiap agent: lihat [E2E.md](E2E.md).
