#!/usr/bin/env bash
# Merakit "Cursor Controller.app" dan DMG dari build SwiftPM, hasilnya di dist/.
#
# Env opsional:
#   BUILD_NUMBER   nomor build (default: GITHUB_RUN_NUMBER, lalu 0)
#   SIGN_IDENTITY  identitas codesign; kosong = ad-hoc ("-")
#   ARCHS          arsitektur yang di-build (default: "arm64 x86_64")
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(cd .. && pwd)"

APP_NAME="Cursor Controller"
EXE="CursorControllerAgent"
VERSION="$(tr -d '[:space:]' < "$ROOT/VERSION")"
BUILD_NUMBER="${BUILD_NUMBER:-${GITHUB_RUN_NUMBER:-0}}"
SIGN_IDENTITY="${SIGN_IDENTITY:--}"
ARCHS="${ARCHS:-arm64 x86_64}"
DIST="$ROOT/dist"
APP="$DIST/$APP_NAME.app"
DMG="$DIST/cursor-controller-mac-$VERSION-b$BUILD_NUMBER.dmg"

binaries=()
for arch in $ARCHS; do
  swift build -c release --arch "$arch" --product "$EXE"
  binaries+=("$(swift build -c release --arch "$arch" --show-bin-path)/$EXE")
done

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
lipo -create "${binaries[@]}" -output "$APP/Contents/MacOS/$EXE"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD_NUMBER/" Resources/Info.plist > "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns "$APP/Contents/Resources/AppIcon.icns"

# Identitas tetap (bukan ad-hoc) menjaga izin Accessibility dan akses Keychain antar update.
codesign --force --options runtime --timestamp=none --sign "$SIGN_IDENTITY" "$APP"
codesign --verify --strict --verbose=2 "$APP"

stage="$(mktemp -d)"
cp -R "$APP" "$stage/"
ln -s /Applications "$stage/Applications"
rm -f "$DMG"
hdiutil create -volname "$APP_NAME" -srcfolder "$stage" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$stage"

echo "App: $APP"
echo "DMG: $DMG"
