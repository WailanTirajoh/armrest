#!/usr/bin/env bash
# Membuat material signing yang tetap untuk CI, lalu menyimpannya sebagai GitHub Secrets.
# Jalankan sekali dari mana saja: ./scripts/setup-signing.sh
#
# Hasil:
#   - Keystore Android (APK baru bisa menimpa versi lama tanpa uninstall).
#   - Sertifikat code signing self-signed untuk Mac (izin Accessibility tetap ada antar update).
#   - 6 secret di repo GitHub, dan backup file + password di $OUT.
# Butuh: gh (sudah login), keytool (JDK), openssl.
set -euo pipefail

REPO="${REPO:-WailanTirajoh/cursor-controller}"
OUT="${OUT:-$HOME/cursor-controller-signing}"
CERT_NAME="Cursor Controller Dev Signing"

if [ -e "$OUT" ]; then
  echo "Folder $OUT sudah ada. Pindahkan atau hapus dulu supaya kunci lama tidak tertimpa." >&2
  exit 1
fi
mkdir -p "$OUT"
chmod 700 "$OUT"
cd "$OUT"

random_password() { openssl rand -base64 32 | tr -d '/+=\n' | cut -c1-28; }

echo "1/3 Membuat keystore Android…"
ks_password="$(random_password)"
key_alias="cursor-controller"
# Keystore PKCS12 memakai password yang sama untuk store dan key.
keytool -genkeypair -keystore android-release.jks -storetype PKCS12 -alias "$key_alias" \
  -keyalg RSA -keysize 3072 -validity 10000 \
  -storepass "$ks_password" -keypass "$ks_password" \
  -dname "CN=Cursor Controller" > /dev/null 2>&1

echo "2/3 Membuat sertifikat code signing Mac…"
p12_password="$(random_password)"
cat > codesign.cnf <<EOF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $CERT_NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
EOF
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -config codesign.cnf \
  -keyout codesign.key -out codesign.crt 2> /dev/null
# Algoritma lama (3DES/SHA1) supaya p12 bisa diimpor `security` di macOS.
openssl pkcs12 -export -inkey codesign.key -in codesign.crt -name "$CERT_NAME" \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 \
  -out codesign.p12 -passout "pass:$p12_password"
rm -f codesign.key codesign.cnf

echo "3/3 Mengisi GitHub Secrets di $REPO…"
base64 < android-release.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$ks_password" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$key_alias" | gh secret set ANDROID_KEY_ALIAS --repo "$REPO"
printf '%s' "$ks_password" | gh secret set ANDROID_KEY_PASSWORD --repo "$REPO"
base64 < codesign.p12 | tr -d '\n' | gh secret set MAC_CERT_P12_BASE64 --repo "$REPO"
printf '%s' "$p12_password" | gh secret set MAC_CERT_PASSWORD --repo "$REPO"

cat > passwords.txt <<EOF
ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_PASSWORD: $ks_password
ANDROID_KEY_ALIAS: $key_alias
MAC_CERT_PASSWORD: $p12_password
EOF
chmod 600 ./*

echo
echo "Selesai. Secret sudah diisi di $REPO."
echo "Backup ada di $OUT. Pindahkan ke password manager, lalu hapus foldernya."
echo "Jalankan ulang workflow Android dan macOS supaya build berikutnya memakai signing tetap."
