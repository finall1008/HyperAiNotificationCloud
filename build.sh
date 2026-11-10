#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"
LIBXPOSED_JAR="$PROJECT_DIR/libs/libxposed-api-102.jar"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")"
APK_NAME="HyperAiNotificationCloud-API102-v${VERSION}"
if [[ "${GITHUB_REF:-}" == refs/tags/* && "$GITHUB_REF_NAME" != "v$VERSION" ]]; then
    echo "Release tag must match AndroidManifest.xml version: v$VERSION" >&2
    exit 1
fi

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "[1/5] Compiling resources with aapt2..."
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/res.zip"
"$BUILD_TOOLS/aapt2" link \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unsigned.unaligned.apk" \
    "$BUILD_DIR/res.zip"

echo "[2/5] Compiling Java sources with javac..."
find "$PROJECT_DIR/src" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac --release 17 \
    -cp "$ANDROID_JAR:$LIBXPOSED_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

echo "[3/5] Converting classes to DEX with d8..."
find "$BUILD_DIR/classes" -name "*.class" > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/d8" \
    --min-api 34 \
    --lib "$ANDROID_JAR" \
    --classpath "$LIBXPOSED_JAR" \
    --output "$BUILD_DIR/dex" \
    @"$BUILD_DIR/classes.txt"

echo "[4/5] Packaging DEX and META-INF/xposed into APK..."
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes.dex"
(
    cd "$BUILD_DIR"
    zip -q -u unsigned.unaligned.apk classes.dex
)
(
    cd "$PROJECT_DIR"
    zip -q -r "$BUILD_DIR/unsigned.unaligned.apk" META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list
)

echo "[5/5] Zipaligning and signing APK..."
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unsigned.unaligned.apk" "$BUILD_DIR/aligned.apk"
if [ -n "${RELEASE_KEYSTORE:-}" ]; then
    if [ -z "${HYPERAI_SIGNING_PASSWORD:-}" ]; then
        HYPERAI_SIGNING_PASSWORD="$(/usr/bin/security find-generic-password \
            -s "${RELEASE_KEYCHAIN_SERVICE:-hyper-ai-third-party-mcp.release-keystore}" \
            -a "$USER" -w)"
    fi
    export HYPERAI_SIGNING_PASSWORD
    trap 'unset HYPERAI_SIGNING_PASSWORD' EXIT
    APK="$DIST_DIR/$APK_NAME-release.apk"
    "$BUILD_TOOLS/apksigner" sign \
        --ks "$RELEASE_KEYSTORE" \
        --ks-key-alias "${RELEASE_KEY_ALIAS:-finall}" \
        --ks-pass env:HYPERAI_SIGNING_PASSWORD \
        --key-pass env:HYPERAI_SIGNING_PASSWORD \
        --out "$APK" "$BUILD_DIR/aligned.apk"
    unset HYPERAI_SIGNING_PASSWORD
else
    DEBUG_KEYSTORE="$HOME/.android/debug.keystore"
    if [ ! -f "$DEBUG_KEYSTORE" ]; then
        mkdir -p "$(dirname "$DEBUG_KEYSTORE")"
        keytool -genkeypair -keystore "$DEBUG_KEYSTORE" -storepass android \
            -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
            -keyalg RSA -keysize 2048 -validity 10000
    fi
    APK="$DIST_DIR/$APK_NAME.apk"
    "$BUILD_TOOLS/apksigner" sign --ks "$DEBUG_KEYSTORE" \
        --ks-pass pass:android --key-pass pass:android \
        --out "$APK" "$BUILD_DIR/aligned.apk"
fi

"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$APK"
echo "Build complete: $APK"
