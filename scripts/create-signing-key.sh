#!/bin/sh
# Creates the release signing key once, outside the repository, with a random password kept in the
# macOS Keychain. The password is never printed. Back up the key file and the Keychain entry: every
# update to an installed app must be signed with this same key.
set -eu

KEY_DIR="$HOME/.android-keys/unalome-pocket"
KEYSTORE="$KEY_DIR/upload.jks"
SERVICE="unalome-pocket-upload-key"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"

if [ -f "$KEYSTORE" ]; then
    echo "A key already exists at $KEYSTORE; nothing changed."
    exit 0
fi

mkdir -p "$KEY_DIR"
chmod 700 "$HOME/.android-keys" "$KEY_DIR"

if ! security find-generic-password -s "$SERVICE" -a keystore >/dev/null 2>&1; then
    security add-generic-password -s "$SERVICE" -a keystore \
        -w "$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
fi
STOREPASS="$(security find-generic-password -s "$SERVICE" -a keystore -w)"
export STOREPASS

"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias upload \
    -keyalg RSA -keysize 4096 -validity 10000 -storepass:env STOREPASS \
    -dname "CN=Unalome Pocket, O=Unalome" >/dev/null 2>&1
chmod 600 "$KEYSTORE"

echo "Created $KEYSTORE (password in Keychain item \"$SERVICE\")."
"$JAVA_HOME/bin/keytool" -list -v -keystore "$KEYSTORE" -storepass:env STOREPASS | grep "SHA256:"
