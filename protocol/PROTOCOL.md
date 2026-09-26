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
| HP → Mac | `settings` | `sensitivity`, `scrollSpeed` | Setelah `auth_result` ok, dan setiap kali diubah |
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

Frame dengan panjang yang salah, tipe tidak dikenal, atau nilai di luar rentang dibuang.

Aturan:

- HP mengirim delta **mentah**. Akselerasi dihitung di agent: `gain = sensitivity × min(6, 1 + 2 × max(0, v − 0,2))`, dengan `v` dalam dp/ms. Sisa pecahan piksel disimpan antar paket.
- `scroll` dikalikan `scrollSpeed` tanpa akselerasi. Arahnya mengikuti setelan natural scrolling di Mac.
- HP mengumpulkan delta per frame dan mengirim maksimal satu `move` per frame. Kalau antrean kirim menumpuk, delta digabung ke paket berikutnya. `click` dan `button` tidak pernah digabung atau dibuang.

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
