# Protokol Cursor Controller

Versi protokol: **1** (field `v` di pesan `hello`). Agent menolak versi yang tidak didukung dengan pesan error yang jelas.

## Transport

- WebSocket di atas TLS (`wss://`) di jaringan lokal, port default **47810**, dengan `TCP_NODELAY`.
- Agent Mac mengiklankan diri lewat Bonjour dengan tipe `_cursorctl._tcp`. TXT record berisi `hostId` dan `v`.
- Pesan kontrol memakai **text frame JSON** karena jarang dikirim dan mudah di-debug.
- Event input memakai **binary frame** 3–5 byte karena dikirim hingga 120 kali per detik.
- M0 (spike) memakai `ws://` tanpa TLS dan tanpa autentikasi. TLS dan handshake ditambahkan di M3.

## Pesan kontrol (JSON, field `t` = tipe)

| Arah | `t` | Isi | Kapan |
| --- | --- | --- | --- |
| HP → Mac | `hello` | `v`, `deviceId`, `mode` (`pair` / `auth`) | Pertama setelah WebSocket terbuka |
| HP → Mac | `pair_request` | `token`, `deviceName`, `publicKey` (base64 DER, X.509 SubjectPublicKeyInfo) | Mode pair |
| Mac → HP | `pair_result` | `ok`, `hostId`, `hostName`, atau `error` | Setelah user klik Izinkan, atau token ditolak |
| Mac → HP | `challenge` | `nonce` (32 byte acak, base64) | Mode auth |
| HP → Mac | `auth` | `sig` = ECDSA P-256 (DER, base64) atas `nonce ‖ hostId ‖ deviceId` | Balasan challenge |
| Mac → HP | `auth_result` | `ok`, atau `error` (`unknown_device`, `bad_sig`) | Setelah verifikasi |
| Dua arah | `ping` / `pong` | `ts` | Tiap 5 detik; putus kalau 15 detik tanpa balasan |

## Event input (binary, little-endian, byte pertama = tipe)

Hanya diterima setelah `auth_result ok` (di M0: langsung setelah terhubung).

| Tipe | Byte | Payload | Arti |
| --- | --- | --- | --- |
| `0x01` move | 5 | `dx: i16`, `dy: i16` | Delta dalam satuan 0,1 dp layar HP |
| `0x02` button | 3 | `button: u8` (0 kiri, 1 kanan), `state: u8` (1 down, 0 up) | Untuk drag |
| `0x03` click | 3 | `button: u8`, `count: u8` (1 atau 2) | Klik tunggal atau ganda |
| `0x04` scroll | 5 | `dx: i16`, `dy: i16` | Delta scroll dalam 0,1 dp |

Contoh encoding:

| Pesan | Byte (hex) |
| --- | --- |
| move dx = 10, dy = −3 | `01 0a 00 fd ff` |
| button kanan, down | `02 01 01` |
| click kiri, count 2 | `03 00 02` |
| scroll dx = 0, dy = −120 | `04 00 00 88 ff` |

Aturan:

- HP mengirim delta **mentah** (belum diakselerasi), supaya kurva akselerasi bisa diubah di agent tanpa update app.
- Kalau koneksi lambat, delta `move` dan `scroll` yang belum terkirim digabung jadi satu paket. Event `click` dan `button` tidak boleh digabung atau dibuang.

## QR pairing

Isi QR (URI):

```
cursorctl://pair?h=<hostId>&n=<hostName>&a=<ip>:<port>&t=<token>&fp=<sha256 cert, base64url>
```

Alamat `a` hanya dipakai saat pairing. Setelah itu HP mencari Mac lewat Bonjour.

### Alur pairing

1. User klik **Tambah perangkat** di menu bar. Agent membuat token 32 byte (TTL 120 detik, sekali pakai) dan menampilkan QR.
2. HP scan QR, lalu membuka `wss://<a>` dengan pinning `fp`. Kalau fingerprint tidak cocok, koneksi dibatalkan.
3. HP kirim `hello` (mode `pair`), lalu `pair_request` berisi token dan public key.
4. Agent cek token (ada, belum kedaluwarsa, belum dipakai), lalu menampilkan dialog "Izinkan <deviceName>?".
5. Izinkan: agent menyimpan perangkat, menghapus token, dan mengirim `pair_result ok`. HP menyimpan host.

### Alur koneksi ulang

1. HP resolve `_cursorctl._tcp` lewat NSD dan mencocokkan `hostId` dari TXT record.
2. HP membuka `wss` dengan pinning fingerprint tersimpan, lalu kirim `hello` (mode `auth`).
3. Agent kirim `challenge`. HP menandatangani `nonce ‖ hostId ‖ deviceId` dengan kunci di Android Keystore.
4. Agent verifikasi dengan public key tersimpan, kirim `auth_result ok`, dan memperbarui `lastSeen`.

### Aturan keamanan

- Token pairing sekali pakai, TTL 120 detik, dibatalkan setelah 5 percobaan salah.
- Nonce baru untuk setiap koneksi. Agent menolak event input sebelum autentikasi selesai.
- Hanya satu sesi aktif per perangkat. Koneksi baru dari perangkat yang sama menutup sesi lama.
- Revoke = hapus perangkat dari daftar terpercaya dan putus sesi yang sedang aktif.
- Server hanya listen di interface LAN, tanpa port forwarding.

## Test vector

Folder `vectors/` akan berisi contoh pesan biner (M1) dan signature ECDSA (M3). Test di Swift dan Kotlin membaca file yang sama, supaya kedua sisi dijamin sepakat.
