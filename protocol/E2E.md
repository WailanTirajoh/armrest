# End-to-end test profile

Every agent (Mac and Windows) has the same test profile, so the same client can test them all:
`android-app/core/src/test/.../AgentConnectionE2ETest.kt`, the Android app's connection code running on the JVM.
The client pairs, authenticates, sends input and media keys, and checks text field focus, volume, the screen,
reconnection, and three rejection scenarios (wrong fingerprint, reused token, unknown device).

## Agent environment variables

| Variable | Meaning |
| --- | --- |
| `ARMREST_PROFILE` | Profile name. Data, TLS identity, and settings are kept apart from the normal app (for example `e2e`). Headless mode without a profile name uses `e2e`, so tests never touch the normal app's data. |
| `ARMREST_PORT` | Server port, for example `47811` so it doesn't clash with an app in use. |
| `ARMREST_E2E_ADDRESS` | Address in the QR code: `127.0.0.1`, or `10.0.2.2` for the Android emulator. |
| `ARMREST_E2E_AUTO_APPROVE=1` | Pairing requests are allowed right away. |
| `ARMREST_E2E_PAIRING_FILE` | The agent writes the QR code's URI to this file once the server is ready. This variable also turns on headless mode. |
| `ARMREST_E2E_FOCUS_FILE` | Text field focus is read from this file: `1` = a text field has focus. |
| `ARMREST_E2E_HOST_NAME` | The computer name to announce, for example for screenshots without the real computer name. Headless mode only. |
| `ARMREST_E2E_LOG=1` | Logs to stderr: `server: …`, `features: …`, `input: …`, `focus: …`, `screen: …`, `volume: …`. |

## Headless mode behavior

- **No windows, no real input**: input from the phone is only logged, and never moves the cursor or types.
- **Text field focus**: from the file above, or always "not a text field" when unset. Never reads other apps.
- **Screen**: a moving test pattern, as if the screen were 1440 × 900, through a real H.264 encoder. Never captures the real screen.
  - The test phone asks for 1920 × 1080, so `screen_config` must be 1440 × 900.
  - The cursor position (`screen_cursor`) is simulated: in the middle of the moving bar, with `y` = 0.725.
- **Volume**: simulated in memory, starting at `level` 0.5 and unmuted. Never changes the real volume.
- **Media keys**: logged as `input: key(…)` like any other key. Never controls real playback.
- **The `screen` feature** is only announced when an encoder is available.
  - Agents without an encoder: the C# host on macOS/Linux, or Windows Server without Media Foundation.
  - For such an agent, run the client with `ARMREST_E2E_EXPECT_SCREEN=0`.

## Running

| Agent | Command |
| --- | --- |
| Mac, and the C# core of the Windows agent (no screen) | `./scripts/e2e-local.sh` |
| Windows (also run by CI) | `./scripts/e2e-windows.ps1` |
