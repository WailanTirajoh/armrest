#!/usr/bin/env bash
# Uji end-to-end lokal: agent Mac sungguhan (TLS, Keychain, Bonjour) melawan klien Android di JVM (OkHttp), lalu
# inti C# agent Windows (tanpa layar) melawan klien yang sama kalau .NET SDK terpasang. Kontrak: protocol/E2E.md.
#
# Agent dijalankan dengan profil "e2e": port 47811, tanpa jendela, pairing otomatis diizinkan, status fokus
# kolom teks dibaca dari file (bukan dari app lain), layar diganti pola uji, dan data terpisah dari app normal.
# Semua data profil e2e (Keychain, Application Support, defaults) dihapus di akhir.
# Tanpa izin Accessibility untuk build ini, event input diterima tapi tidak menggerakkan kursor.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PAIRING="$WORK/pairing.txt"
FOCUS="$WORK/focus.txt"
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
CURSORCTL_E2E_FOCUS_FILE="$FOCUS" \
CURSORCTL_E2E_LOG=1 \
  "$AGENT" > "$LOG" 2>&1 &
AGENT_PID=$!

WIN_PID=""
cleanup() {
  kill "$AGENT_PID" 2> /dev/null || true
  wait "$AGENT_PID" 2> /dev/null || true
  if [ -n "$WIN_PID" ]; then
    kill "$WIN_PID" 2> /dev/null || true
    wait "$WIN_PID" 2> /dev/null || true
  fi
  rm -rf "$HOME/Library/Application Support/Cursor Controller-e2e-core"
  security delete-identity -c "Cursor Controller-e2e" > /dev/null 2>&1 || true
  rm -rf "$HOME/Library/Application Support/Cursor Controller-e2e"
  defaults delete io.github.wailantirajoh.cursorcontroller.agent-e2e > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

wait_for_pairing() {
  for _ in $(seq 1 100); do
    [ -s "$1" ] && break
    sleep 0.2
  done
  if [ ! -s "$1" ]; then
    echo "Agent tidak siap dalam 20 detik. Log:"
    cat "$2"
    exit 1
  fi
  echo "QR: $(cat "$1")"
}

# Setiap baris dicek satu per satu: dengan set -e, "grep a && grep b" tidak menghentikan skrip kalau grep a gagal.
expect_log() {
  local log="$1"
  shift
  for text in "$@"; do
    if ! grep -qF -- "$text" "$log"; then
      echo "Log agent tidak berisi: $text"
      exit 1
    fi
  done
}

wait_for_pairing "$PAIRING" "$LOG"

echo "== Klien JVM"
(cd "$ROOT/android-app" && CURSORCTL_E2E_PAIRING_FILE="$PAIRING" CURSORCTL_E2E_FOCUS_FILE="$FOCUS" ./gradlew -q :core:test --tests '*AgentConnectionE2ETest' --rerun)

echo "== Log agent"
cat "$LOG"
expect_log "$LOG" "input: move" "input: click" "input: scroll" 'input: text("Halo dunia' \
  "input: key(AgentCore.KeyCode.returnKey" "input: key(AgentCore.KeyCode.c" "focus: true" "focus: false" \
  "screen: start" "screen: streaming" "screen: stop"
echo "E2E Mac OK"

if ! command -v dotnet > /dev/null; then
  echo "(.NET SDK tidak ada: inti agent Windows dilewati)"
  exit 0
fi

echo "== Build inti agent Windows (C#)"
dotnet build "$ROOT/windows-agent/src/Agent.Headless" -c Release -o "$WORK/headless" --nologo -v quiet > /dev/null
WIN_PAIRING="$WORK/win-pairing.txt"
WIN_FOCUS="$WORK/win-focus.txt"
WIN_LOG="$WORK/win-agent.log"

echo "== Jalankan inti agent Windows (profil e2e-core, tanpa layar)"
CURSORCTL_PROFILE=e2e-core \
CURSORCTL_PORT=47813 \
CURSORCTL_E2E_ADDRESS=127.0.0.1 \
CURSORCTL_E2E_AUTO_APPROVE=1 \
CURSORCTL_E2E_PAIRING_FILE="$WIN_PAIRING" \
CURSORCTL_E2E_FOCUS_FILE="$WIN_FOCUS" \
CURSORCTL_E2E_LOG=1 \
  dotnet "$WORK/headless/CursorController.Headless.dll" > "$WIN_LOG" 2>&1 &
WIN_PID=$!
wait_for_pairing "$WIN_PAIRING" "$WIN_LOG"

echo "== Klien JVM"
(cd "$ROOT/android-app" && CURSORCTL_E2E_PAIRING_FILE="$WIN_PAIRING" CURSORCTL_E2E_FOCUS_FILE="$WIN_FOCUS" CURSORCTL_E2E_EXPECT_SCREEN=0 \
  ./gradlew -q :core:test --tests '*AgentConnectionE2ETest' --rerun)

echo "== Log inti agent Windows"
cat "$WIN_LOG"
# Baris yang sama dengan yang dicek scripts/e2e-windows.ps1.
expect_log "$WIN_LOG" "input: move(dx: 120, dy: -40)" "input: click(left, count: 1)" "input: scroll(dx: 0, dy: -60)" \
  'input: text("Halo dunia' "input: key(return, modifiers: 0)" "input: key(c, modifiers: 8)" "focus: true" "focus: false"
echo "E2E OK"
