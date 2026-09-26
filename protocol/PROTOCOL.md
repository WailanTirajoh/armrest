# Protokol Cursor Controller

Versi protokol: **1** (field `v` di pesan `hello`). Agent menolak versi lain dengan `error` `unsupported_version`, lalu menutup koneksi.

## Transport

- WebSocket di atas TLS (`wss://`) di jaringan lokal, port default **47810**, dengan `TCP_NODELAY`.
- Sertifikat agent self-signed (ECDSA P-256). HP tidak memakai CA: HP mencocokkan **fingerprint** = base64url tanpa padding dari SHA-256 sertifikat (DER). Fingerprint dibawa lewat QR saat pairing, lalu disimpan.
- Agent mengiklankan diri lewat Bonjour dengan tipe `_cursorctl._tcp`. TXT record berisi `hostId` dan `v`.
- Pesan kontrol memakai **text frame JSON**. Event input memakai **binary frame** 3–5 byte.

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
| Mac → HP | `auth_result` | `ok: true`, atau `ok: false`, `error` | Setelah verifikasi |
| HP → Mac | `settings` | `sensitivity`, `scrollSpeed`, `focusUpdates` | Setelah `auth_result` ok, dan setiap kali diubah |
| Mac → HP | `focus` | `text` (bool) | Setelah HP meminta lewat `focusUpdates`, lalu setiap kali berubah |
| Dua arah | `ping` / `pong` | `ts` (ms) | Tiap 5 detik; koneksi ditutup kalau 15 detik tidak ada pesan masuk |
| Mac → HP | `error` | `error` | Pesan tidak valid atau versi tidak didukung, lalu koneksi ditutup |

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
