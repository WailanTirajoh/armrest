#!/usr/bin/env bash
# Uji end-to-end lokal: agent Mac sungguhan (TLS, Keychain, Bonjour) melawan klien Android di JVM (OkHttp).
#
# Agent dijalankan dengan profil "e2e": port 47811, tanpa jendela, pairing otomatis diizinkan, dan data
# terpisah dari app normal. Semua data profil e2e (Keychain, Application Support, defaults) dihapus di akhir.
# Tanpa izin Accessibility untuk build ini, event input diterima tapi tidak menggerakkan kursor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PAIRING="$WORK/pairing.txt"
LOG="$WORK/agent.log"

echo "== Build agent"
(cd "$ROOT/mac-agent" && ARCHS="$(uname -m)" ./scripts/bundle.sh > /dev/null)
AGENT="$ROOT/dist/Cursor Controller.app/Contents/MacOS/CursorControllerAgent"

echo "== Jalankan agent (profil e2e)"
CURSORCTL_PROFILE=e2e \
CURSORCTL_PORT=47811 \
CURSORCTL_E2E_ADDRESS=127.0.0.1 \
CURSORCTL_E2E_AUTO_APPROVE=1 \
CURSORCTL_E2E_PAIRING_FILE="$PAIRING" \
CURSORCTL_E2E_LOG=1 \
  "$AGENT" > "$LOG" 2>&1 &
AGENT_PID=$!

cleanup() {
  kill "$AGENT_PID" 2> /dev/null || true
  wait "$AGENT_PID" 2> /dev/null || true
  security delete-identity -c "Cursor Controller-e2e" > /dev/null 2>&1 || true
  rm -rf "$HOME/Library/Application Support/Cursor Controller-e2e"
  defaults delete io.github.wailantirajoh.cursorcontroller.agent-e2e > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

for _ in $(seq 1 100); do
  [ -s "$PAIRING" ] && break
  sleep 0.2
done
if [ ! -s "$PAIRING" ]; then
  echo "Agent tidak siap dalam 20 detik. Log:"
  cat "$LOG"
  exit 1
fi
echo "QR: $(cat "$PAIRING")"

echo "== Klien JVM"
(cd "$ROOT/android-app" && CURSORCTL_E2E_PAIRING_FILE="$PAIRING" ./gradlew -q :core:test --tests '*AgentConnectionE2ETest' --rerun)

echo "== Log agent"
cat "$LOG"
grep -q "input: move" "$LOG" && grep -q "input: click" "$LOG" && grep -q "input: scroll" "$LOG"
echo "E2E OK"
