#!/bin/sh
# Builds the signed release APK with the key made by scripts/create-signing-key.sh and verifies it.
set -eu

cd "$(dirname "$0")/.."
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
: "${ANDROID_HOME:=/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME ANDROID_HOME

UNALOME_KEYSTORE="$HOME/.android-keys/unalome-pocket/upload.jks"
if [ ! -f "$UNALOME_KEYSTORE" ]; then
    echo "No signing key yet. Run scripts/create-signing-key.sh first." >&2
    exit 1
fi
UNALOME_KEYSTORE_PASSWORD="$(security find-generic-password -s unalome-pocket-upload-key -a keystore -w)"
export UNALOME_KEYSTORE UNALOME_KEYSTORE_PASSWORD

./gradlew assembleRelease bundleRelease

# The APK is for installing directly on a phone; the bundle (.aab) is what Google Play accepts.
APK="app/build/outputs/apk/release/app-release.apk"
AAB="app/build/outputs/bundle/release/app-release.aab"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | grep -E "Signer #1 certificate (DN|SHA-256)"
"$JAVA_HOME/bin/jarsigner" -verify "$AAB" | tail -1
ls -l "$APK" "$AAB"
