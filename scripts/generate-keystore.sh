#!/usr/bin/env bash
# Generates a release-signing keystore (my-upload-key.jks, alias "upload")
# and writes the credentials to a gitignored keystore.properties, so that
# `./gradlew :app:assembleRelease` works locally with no env vars.
#
# CI (release.yml) does not use this script — it restores its own keystore
# from the KEYSTORE_BASE64 secret and passes env vars instead.
#
# Usage:  bash scripts/generate-keystore.sh [output_dir]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT}"
KEYSTORE="$OUT_DIR/my-upload-key.jks"
PROPS="$OUT_DIR/keystore.properties"

if [[ -f "$KEYSTORE" ]]; then
  echo "Keystore already exists at $KEYSTORE — refusing to overwrite." >&2
  exit 1
fi

# keytool's default format (PKCS12) uses a single password for both the
# store and the key — generate one strong password and use it for both.
PASS="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 32)"

keytool -genkeypair -v \
  -keystore "$KEYSTORE" -alias upload \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Slukhayka, OU=Mobile, O=Slukhayka, L=Kyiv, S=Kyiv, C=UA"

umask 177
printf 'keystorePath=%s\nkeyAlias=upload\nstorePassword=%s\nkeyPassword=%s\n' \
  "${KEYSTORE#$ROOT/}" "$PASS" "$PASS" > "$PROPS"

echo "Keystore:   $KEYSTORE"
echo "Credential: $PROPS (gitignored)"
echo
echo "⚠️  Back these up somewhere safe — losing the keystore or its password"
echo "   means the app can never be updated under this signature again."
