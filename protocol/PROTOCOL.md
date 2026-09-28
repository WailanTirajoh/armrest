# Armrest protocol

Protocol version: **1** (field `v` in the `hello` message). The agent rejects any other version with an `error` of `unsupported_version`, then closes the connection.

"Phone" is the Armrest app; "agent" is the computer running the Armrest agent (macOS or Windows).

## Transport

- WebSocket over TLS (`wss://`) on the local network, default port **47810**, with `TCP_NODELAY`.
- The agent's certificate is self-signed (ECDSA P-256). The phone uses no CA: it checks the **fingerprint**, the unpadded base64url SHA-256 of the certificate (DER). The fingerprint arrives in the pairing QR code and is stored.
- The agent advertises itself over Bonjour as `_armrest._tcp`. The TXT record holds `hostId`, `v`, and `os` (`macos` or `windows`).
- Control messages are **JSON text frames**. Input events (phone → agent) and screen video (agent → phone) are **binary frames**.

## Value formats

| Value | Format |
| --- | --- |
| `hostId`, `deviceId` | Lowercase UUID, created once and stored |
| `token` | 32 random bytes, unpadded base64url |
| `nonce` | 32 random bytes, standard base64 |
| `publicKey` | P-256 public key as DER X.509 SubjectPublicKeyInfo, standard base64 |
| `sig` | ECDSA P-256 with SHA-256, DER encoded, standard base64 |
| `auth` payload | `nonce (32 bytes) ‖ UTF-8(hostId) ‖ UTF-8(deviceId)` |

## Control messages (JSON, field `t` = type)

| Direction | `t` | Fields | When |
| --- | --- | --- | --- |
| Phone → agent | `hello` | `v`, `deviceId`, `mode` (`pair` / `auth`) | First, once the WebSocket is open |
| Phone → agent | `pair_request` | `token`, `deviceName`, `publicKey` | Pair mode |
| Agent → phone | `pair_result` | `ok: true`, `hostId`, `hostName`, or `ok: false`, `error` | After the user clicks Allow or Deny, or when the token is rejected |
| Agent → phone | `challenge` | `nonce` | Auth mode, or right after a successful `pair_result` |
| Phone → agent | `auth` | `sig` | Reply to the challenge |
| Agent → phone | `auth_result` | `ok: true` with `features`, `platform`, and `mac` (optional), or `ok: false`, `error` | After verification |
| Phone → agent | `settings` | `sensitivity`, `scrollSpeed`, `focusUpdates`, `volumeUpdates` | After a successful `auth_result`, and whenever they change |
| Agent → phone | `focus` | `text` (bool) | Once the phone asks with `focusUpdates`, then on every change |
| Phone → agent | `screen` | `on: true`, `maxWidth`, `maxHeight`, `cursor` (optional), or `on: false` | Start or stop viewing the screen; repeating it asks for a keyframe |
| Agent → phone | `screen_status` | `state`: `streaming`, `denied`, `failed` | After `screen` on, and when the stream stops on an error |
| Phone → agent | `screen_ack` | `seq` | For every screen frame received |
| Agent → phone | `screen_cursor` | `x`, `y` (0–1) | While the screen is shown and the phone asked for `cursor`, whenever the cursor moves |
| Phone → agent | `volume` | Exactly one of `step`, `level`, `muted` | Change the computer's output volume |
| Agent → phone | `volume_status` | `level` (0–1, optional), `muted` | Once the phone asks with `volumeUpdates`, then on every change |
| Phone → agent | `power` | `action`: `sleep`, `restart`, `shutdown` | Put the computer to sleep, restart it, or shut it down |
| Both | `ping` / `pong` | `ts` (ms) | Every 5 seconds; the connection is closed after 15 seconds without incoming messages |
| Agent → phone | `error` | `error` | Invalid message or unsupported version, then the connection is closed |

Optional fields in `auth_result`:

- `features`: the agent's optional features: `focus`, `screen`, `volume`, `media` (media keys `0x60`–`0x62`; the phone only shows its media buttons when this is present), and `power` (see [Power](#power)).
- `platform`: `macos` or `windows`. The phone uses it for modifier labels (⌘ ⌃ ⌥ ⇧ or Ctrl Win Alt Shift), icons, and text. When missing (agents before v0.6), it is `macos`.
- `mac`: the hardware address of the computer's main network interface, lowercase and colon separated (`a4:83:e7:12:34:56`), so the phone can wake it later with Wake-on-LAN. Missing when the agent can't find one.

`error` codes:

| Message | Codes |
| --- | --- |
| `pair_result` | `token_invalid`, `token_expired`, `too_many_attempts`, `denied` |
| `auth_result` | `unknown_device`, `bad_sig` |
| `error` | `unsupported_version`, `bad_message` |

## Input events (binary, little-endian, first byte = type)

Only processed after a successful `auth_result`. Events that arrive earlier are dropped.

| Type | Bytes | Payload | Meaning |
| --- | --- | --- | --- |
| `0x01` move | 5 | `dx: i16`, `dy: i16` | Delta in units of 0.1 dp of the phone's screen |
| `0x02` button | 3 | `button: u8` (0 left, 1 right), `state: u8` (1 down, 0 up) | For dragging |
| `0x03` click | 3 | `button: u8`, `count: u8` (1 or 2) | Single or double click |
| `0x04` scroll | 5 | `dx: i16`, `dy: i16` | Scroll delta in 0.1 dp |
| `0x05` text | 2–1025 | UTF-8 text, 1–1024 bytes | Type text into the active app; `\n` = Return, `\t` = Tab |
| `0x06` key | 3 | `key: u8`, `modifiers: u8` | Press one key while holding modifiers |

Frames with the wrong length, an unknown type, or an out-of-range value are dropped. For `text`, that includes invalid UTF-8 and control characters other than `\n` and `\t`.

`key` codes:

| Code | Key |
| --- | --- |
| `0x01`–`0x06` | Return, Backspace, Tab, Esc, Space, Forward Delete |
| `0x07`–`0x0A` | ←, →, ↑, ↓ |
| `0x0B`–`0x0E` | Home, End, Page Up, Page Down |
| `0x10`–`0x1B` | F1–F12 |
| `0x20`–`0x39` | A–Z |
| `0x40`–`0x49` | 0–9 |
| `0x50`–`0x5A` | `-` `=` `[` `]` `\` `;` `'` `,` `.` `/` `` ` `` |
| `0x60`–`0x62` | Play/pause, next track, previous track (system media keys; modifiers are ignored) |

`modifiers` bits: `0x01` Shift, `0x02` Control, `0x04` Option, `0x08` Command. All other bits must be 0. Letters and punctuation use ANSI key positions, so ⌘C is always the key in the C position.

Modifier bits mean physical key positions. On Windows: Control = Ctrl, Option = Alt, Command = the Windows key, so Ctrl+C is sent as `C` with the Control bit. The Windows agent presses letters by the scan code of their ANSI position, just like the Mac agent.

Rules:

- The phone sends **raw** deltas. The agent applies acceleration: `gain = sensitivity × min(6, 1 + 2 × max(0, v − 0.2))`, with `v` in dp/ms. Fractional pixels carry over between packets.
- `scroll` is multiplied by `scrollSpeed` without acceleration. Its direction follows the Mac's natural scrolling setting.
- The phone accumulates deltas per frame and sends at most one `move` per frame. When the send queue backs up, deltas are merged into the next packet. `click`, `button`, `text`, and `key` are never merged or dropped, and are always sent after any pending movement.
- `text` is typed as Unicode events, so it doesn't depend on the computer's keyboard layout. Backspace is sent as `key` `0x02`.

## Text field focus

So the phone can open and close its keyboard on its own, the agent reports whether the focused element on the computer is a text field.

- The phone asks with `focusUpdates: true` in `settings`, and stops with `false`. When the field is missing (phone v0.3), it is `false`.
- The agent sends the current state right away, then again on every change: `{"t":"focus","text":true}`.
- The agent checks the focused element every 250 ms, only while a phone is asking: through Accessibility on macOS and UI Automation on Windows. On macOS, a text field is the role `AXTextField` (including password and search fields), `AXTextArea`, `AXComboBox`, or an element inside an editable area (`AXEditableAncestor`, such as a contenteditable editor in a browser).
- Focus moving into a text field is sent immediately. Focus leaving is sent only after it lasts 500 ms, so moving between fields doesn't close and reopen the phone's keyboard.
- Phone v0.3 ignores `focus` messages, and agent v0.3 ignores `focusUpdates`.

## Computer screen

The phone can show the computer's screen, for example behind the touchpad. The video is H.264, sent as binary frames on the same connection.

- **Feature**: agents that support it send `features: ["focus", "screen"]` in `auth_result`. The phone only sends `screen` when `"screen"` is present, because older agents close the connection on unknown messages.
- **Start**: the phone sends `screen` with `maxWidth` and `maxHeight` in pixels, usually its own screen size. The agent replies with `screen_status` `streaming`, then sends `screen_config` and a keyframe.
- **Permission**: if the Mac hasn't granted Screen Recording, the agent replies with `screen_status` `denied` and sends no video. If capture fails or stops on an error, the state is `failed`. In both cases the phone may send `screen` again to retry.
- **Keyframe**: `screen` on sent again while streaming means the phone's decoder needs a keyframe, for example after its Surface was recreated. The agent sends `screen_config` and then a keyframe of the latest image, even when the screen is idle.
- **Stop**: `screen` off, or a disconnect.
- **Cursor position**: if `screen` carries `cursor: true`, the agent sends `{"t":"screen_cursor","x":0.4213,"y":0.25}` whenever the cursor position in the video changes, at most once per captured image, and once more after a repeated request. `x` and `y` are measured from the top left of the video, 0–1, with four decimals. The phone uses them so a zoomed view follows the cursor. Agents before v0.7 ignore `cursor`.

Binary frames, agent → phone (little-endian, first byte = type):

| Type | Bytes | Contents | Meaning |
| --- | --- | --- | --- |
| `0x81` screen_config | 7+ | `codec: u8` (1 = H.264), `width: u16`, `height: u16`, SPS and PPS | Video size and decoder parameters, sent before every keyframe |
| `0x82` screen_frame | 7+ | `seq: u32`, `flags: u8` (bit 0 = keyframe), access unit | One video frame |

SPS, PPS, and access units use Annex B format (every NAL starts with `00 00 00 01`). Frames with an unknown type or codec, a zero size, flags other than bit 0, or no payload are dropped.

Video rules:

- H.264 without B-frames (Baseline, Main, or High profile), so every frame can be shown right away. The Mac agent uses Constrained High, the Windows agent Main. At most 1920 × 1200 and 30 fps, keeping the screen's aspect ratio, with even dimensions and BT.709 color.
- An idle screen produces no frames. The cursor is drawn into the video. With several monitors, the agent follows the monitor the cursor is on; when the size changes, a new `screen_config` precedes the next keyframe.
- **Flow control**: `seq` increases by one per frame. The phone sends `screen_ack` for every frame it receives, and acknowledgements are cumulative. The agent holds back new frames while 4 frames are unacknowledged, then sends the latest image as soon as an acknowledgement arrives. So on slow Wi-Fi, images are skipped instead of piling up.
- The phone only turns video on while the app is visible, to save Wi-Fi and battery.

## Volume

Lets the phone control the computer's volume, for example with the phone's volume buttons.

- **Feature**: agents that support it send `"volume"` in `features`. The phone only sends `volume` when it is present, because older agents close the connection on unknown messages.
- **Commands** contain exactly one field:
  - `{"t":"volume","step":1}` goes up one step, `-1` down (at most 16 steps at once). One step is 1/16, and the result is always a multiple of 1/16, like the Mac's volume keys: from 0.53, up goes to 0.5625 and down to 0.5.
  - `{"t":"volume","level":0.4}` sets the volume directly (0–1).
  - `{"t":"volume","muted":true}` mutes, `false` unmutes.
  - `step` and `level` also unmute, like volume keys.
- **Status**: the phone asks with `volumeUpdates: true` in `settings`. The agent sends the current state right away, then again on every change, including changes made on the computer: `{"t":"volume_status","level":0.5625,"muted":false}`.
- Without `level`, the computer's output has no adjustable volume (for example an HDMI monitor on a Mac). `step` and `level` commands are then ignored.
- The agent checks the volume every 250 ms, only while a phone is asking. It controls the default output device: through CoreAudio on macOS (no extra permission), and Core Audio (`IAudioEndpointVolume`) on Windows.
- Phones before v0.7 don't send `volumeUpdates`, so they never receive `volume_status`.

## Power

Lets the phone put the computer to sleep, restart it, or shut it down, and wake it again.

- **Feature**: agents that support it send `"power"` in `features`. The phone only sends `power` when it is present, because older agents close the connection on unknown messages.
- **Command**: `{"t":"power","action":"shutdown"}`. `action` is `sleep`, `restart`, or `shutdown`; any other value is a `bad_message`. The agent doesn't reply: the connection simply drops as the computer goes down. The phone asks the user to confirm before sending `restart` or `shutdown`.
- **macOS**: sleep with `pmset sleepnow`; restart and shut down through System Events, like the Apple menu, so apps can still ask to save documents. The first time, macOS asks to allow Armrest to control System Events.
- **Windows**: sleep with `SetSuspendState`; restart and shut down with `shutdown.exe` (`/r` or `/s`, no delay).
- The test profile only logs the command, for example `power: shutdown`.

### Wake-on-LAN

A computer that is off or asleep can't run the agent, so the phone wakes it directly:

- The phone stores `mac` from `auth_result` with the host.
- To wake it, the phone sends a magic packet over UDP to port 9 of the broadcast address `255.255.255.255`, and of the host's last known address: 6 bytes `0xFF`, then the 6-byte hardware address repeated 16 times (102 bytes).
- The packet only reaches computers on the same local network. Wake-on-LAN must be enabled on the computer: in the BIOS/UEFI and the network adapter's settings on Windows (Fast Startup can block it after a shutdown), and **Wake for network access** on a Mac (Macs generally wake from sleep, not from shut down). Wi-Fi adapters rarely support waking from shut down; Ethernet is the reliable choice.

## Pairing QR code

```
armrest://pair?h=<hostId>&n=<hostName>&a=<ip>:<port>&t=<token>&fp=<fingerprint>
```

`n` is percent-encoded. The address `a` is only used for pairing; after that, the phone finds the agent over Bonjour, with the last known address as a fallback. The Android app also accepts this URI as a deep link.

### Pairing flow

1. The user clicks **Add device…** in the agent's menu. The agent creates a token (120-second TTL, single use) and shows the QR code.
2. The phone scans the QR code, then opens `wss://<a>`, pinning `fp`. If the fingerprint doesn't match, the connection is aborted.
3. The phone sends `hello` (mode `pair`), then `pair_request`.
4. The agent checks the token. The token is spent as soon as it is used, whether the request is then allowed or denied. The agent then asks "Allow <deviceName> to control this computer?".
5. Allow: the agent stores the device and sends a successful `pair_result`, followed right away by `challenge`. The phone stores the host and answers with `auth` on the same connection.

### Reconnection flow

1. The phone resolves `_armrest._tcp` through NSD and matches the `hostId` from the TXT record. If it finds nothing, it tries the last known address.
2. The phone opens `wss`, pinning the stored fingerprint, then sends `hello` (mode `auth`).
3. The agent sends `challenge`; the phone signs the payload with its key in the Android Keystore.
4. The agent verifies it with the stored public key, sends a successful `auth_result`, and updates `lastSeen`.

### Security rules

- Pairing tokens are single use, expire after 120 seconds, and are canceled after 5 failed attempts.
- A fresh nonce for every connection. The agent drops input events until authentication completes.
- One active session per device. A new connection from the same device closes the old session.
- Revoking removes the device from the trusted list and ends its session.

## Test vectors

`vectors/` is shared by the Swift, Kotlin, and C# tests, so every side is guaranteed to agree:

- `input.json`: the binary encoding of every event type, plus frames that must be rejected.
- `auth.json`: payloads, public keys, and signatures made with openssl (valid and invalid), plus a sample fingerprint.
- `screen.json`: the encoding of `screen_config` and `screen_frame`, frames that must be rejected, and the conversion from length-prefixed NALs to Annex B.

End-to-end tests use the same test profile in every agent: see [E2E.md](E2E.md).
