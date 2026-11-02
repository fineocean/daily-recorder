#!/usr/bin/env bash
# Offline build: src/ (code, manifest, icon) -> dailyrecorder-<versionName>.apk
#
# Signing key: $KEYSTORE (default ~/.android-keys/release.p12), created on first run.
# Password: $KEYSTORE_PASS, else the login keyring, else you are prompted. To save it in the keyring once:
#   secret-tool store --label="Android signing key" service android-signing keystore ~/.android-keys/release.p12
# Back the key up: without it you can't update the app.
set -euo pipefail
cd "$(dirname "$0")"

SDK=sdk
KEYSTORE=${KEYSTORE:-$HOME/.android-keys/release.p12}
KEY_ALIAS=app
MIN_SDK=$(grep -oP 'minSdkVersion="\K[0-9]+' src/AndroidManifest.xml)
VERSION=$(grep -oP 'versionName="\K[^"]+' src/AndroidManifest.xml)
APK=dailyrecorder-$VERSION.apk

if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi

# The only build dependency, r8.jar (Java -> Android dex), is downloaded on first run (needs internet)
# and checked against its known SHA-256 on every run, so the build always uses the exact same file.
R8_URL=https://dl.google.com/android/maven2/com/android/tools/r8/9.5.22/r8-9.5.22.jar
R8_SHA256=a55b40de220efd4f3c252dd6239d54ef86a319ff4dcb06701084056ecfffc308
if [[ ! -f "$SDK/r8.jar" ]]; then
    echo "Downloading r8.jar..."
    mkdir -p "$SDK"
    curl -sSfo "$SDK/r8.jar.part" "$R8_URL"
    mv "$SDK/r8.jar.part" "$SDK/r8.jar"
fi
echo "$R8_SHA256  $SDK/r8.jar" | sha256sum -c --quiet \
    || { echo "$SDK/r8.jar doesn't match the expected checksum; delete it and run again" >&2; exit 1; }

rm -rf build
mkdir -p build/stubs-src build/stubs build/classes build/apk

# Cut buildtools/stubs into one Java file per "// == <path>" marker, since Java wants one public class per file.
awk '/^\/\/ == / { if (out) close(out); out = "build/stubs-src/" $3; dir = out; sub(/\/[^\/]*$/, "", dir)
                   system("mkdir -p \"" dir "\""); next }
     out { print > out }' buildtools/stubs

echo "Compiling Java..."
"${JAVAC[@]}" -source 17 -target 17 -Xlint:-options -d build/stubs $(find build/stubs-src -name '*.java')
"${JAVAC[@]}" -source 17 -target 17 -Xlint:-options -classpath build/stubs -d build/classes $(find src -name '*.java')

echo "Converting to dex..."
java -cp "$SDK/r8.jar" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" \
    --classpath build/stubs --output build/apk $(find build/classes -name '*.class')

echo "Compiling manifest and resources..."
java buildtools/Build.java manifest src/AndroidManifest.xml src build/apk

if [[ -z "${KEYSTORE_PASS:-}" ]] && command -v secret-tool >/dev/null; then
    KEYSTORE_PASS=$(secret-tool lookup service android-signing keystore "$KEYSTORE" 2>/dev/null || true)
fi
if [[ -z "${KEYSTORE_PASS:-}" ]]; then
    read -rsp "Keystore password: " KEYSTORE_PASS; echo
fi
export KEYSTORE_PASS

if [[ ! -f "$KEYSTORE" ]]; then
    echo "Creating signing key at $KEYSTORE (back this file up!)"
    mkdir -p -m 700 "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=$KEY_ALIAS" \
        -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS
    chmod 600 "$KEYSTORE"
fi

echo "Packing and signing..."
java buildtools/Build.java apk build/apk "$APK" "$KEYSTORE" "$KEY_ALIAS"
sha256sum "$APK" > "$APK.sha256" # attach to a release with the APK; check with: sha256sum -c <file>.sha256

echo "Built $APK ($(du -h "$APK" | cut -f1)) and $APK.sha256"
