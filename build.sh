#!/bin/bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$SDK_DIR/platforms/android-34/android.jar"
LIBXPOSED_JAR="$PROJECT_DIR/libs/libxposed-api-102.jar"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"

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
"$BUILD_TOOLS/apksigner" sign \
    --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$DIST_DIR/HyperAiNotificationCloud-API102-v1.0.0.apk" \
    "$BUILD_DIR/aligned.apk"

if [ -n "${RELEASE_KEYSTORE:-}" ]; then
    export HYPERAI_SIGNING_PASSWORD
    HYPERAI_SIGNING_PASSWORD="$(/usr/bin/security find-generic-password -s "${RELEASE_KEYCHAIN_SERVICE:-hyper-ai-third-party-mcp.release-keystore}" -a "$USER" -w)"
    trap 'unset HYPERAI_SIGNING_PASSWORD' EXIT
    "$BUILD_TOOLS/apksigner" sign \
        --ks "$RELEASE_KEYSTORE" \
        --ks-key-alias "${RELEASE_KEY_ALIAS:-finall}" \
        --ks-pass env:HYPERAI_SIGNING_PASSWORD \
        --key-pass env:HYPERAI_SIGNING_PASSWORD \
        --out "$DIST_DIR/HyperAiNotificationCloud-API102-v1.0.0-release.apk" \
        "$BUILD_DIR/aligned.apk"
    unset HYPERAI_SIGNING_PASSWORD
    echo "Release APK signed: $DIST_DIR/HyperAiNotificationCloud-API102-v1.0.0-release.apk"
fi

echo "Build complete: $DIST_DIR/HyperAiNotificationCloud-API102-v1.0.0.apk"
unzip -l "$DIST_DIR/HyperAiNotificationCloud-API102-v1.0.0.apk"
