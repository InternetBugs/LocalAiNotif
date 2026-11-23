#!/bin/bash
# LocalAiNotif build (no Gradle; aapt2 + javac + d8 + apksigner)
# Windows Git Bash: SDK at D:/Android/Sdk; jar signing uses debug keystore.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && cygpath -am .)"
cd "$PROJECT_DIR"
SDK_DIR="$(cygpath -am "${ANDROID_SDK_ROOT:-/d/Android/Sdk}")"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
ANDROID_JAR="$(cygpath -am "${ANDROID_JAR:-$SDK_DIR/platforms/android-36/android.jar}")"
LIBXPOSED_JAR="$PROJECT_DIR/libs/libxposed-api-102.jar"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")"
APK_NAME="LocalAiNotif-v${VERSION}"
PY="${PYTHON:-/d/Python312/python.exe}"
USERHOME="${HOME:-${USERPROFILE:-$PROJECT_DIR}}"
MNN_DIR="$(cygpath -am "${MNN_DIR:-$USERHOME/tmp/MNN}")"
NDK_DIR="${NDK_DIR:-$SDK_DIR/ndk/28.2.13676358}"
NDK_POSIX="${NDK_POSIX:-$NDK_DIR}"
CXX="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
MNN_LIB="$MNN_DIR/project/android/build_local/libMNN.so"
JNI_OUT="$BUILD_DIR/jniLibs/arm64-v8a"

rm -rf "$BUILD_DIR" "$DIST_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "[1/6] aapt2: compiling resources..."
"$BUILD_TOOLS/aapt2.exe" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/res.zip"

echo "[2/6] aapt2: linking manifest..."
"$BUILD_TOOLS/aapt2.exe" link \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unsigned.unaligned.apk" \
    "$BUILD_DIR/res.zip"

echo "[3/6] javac: compiling sources..."
find src -name "*.java" > "$BUILD_DIR/sources.txt"
javac --release 17 \
    -encoding UTF-8 \
    -cp "$ANDROID_JAR;$LIBXPOSED_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt" 2> "$BUILD_DIR/javac.err" || { cat "$BUILD_DIR/javac.err"; exit 1; }
cat "$BUILD_DIR/javac.err"

echo "[4/6] d8: dexing..."
find build/classes -name "*.class" > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/d8.bat" \
    --min-api 34 \
    --lib "$ANDROID_JAR" \
    --classpath "$LIBXPOSED_JAR" \
    --output "$BUILD_DIR/dex" \
    @"$BUILD_DIR/classes.txt"
echo "[4b/6] native: MNN + JNI wrapper..."
if [ ! -f "$MNN_LIB" ]; then
    echo "libMNN.so missing at $MNN_LIB (build MNN first; see docs/交接.md)" >&2
    exit 1
fi
mkdir -p "$BUILD_DIR/jniLibs/arm64-v8a"
"$CXX" --target=aarch64-linux-android24 -std=c++17 -O2 -fPIC -shared \
    -I "$MNN_DIR/include" -I "$MNN_DIR/source" -I "$MNN_DIR/express" \
    -I "$MNN_DIR/schema/current" -I "$MNN_DIR/3rd_party/flatbuffers/include" \
    -I "$MNN_DIR/3rd_party/half" -I "$MNN_DIR/transformers/llm/engine/include" \
    "$PROJECT_DIR/jni/localai_llm_jni.cpp" \
    -L "$MNN_DIR/project/android/build_local" -lMNN \
    "-Wl,-z,max-page-size=16384" \
    -o "$JNI_OUT/liblocalai_llm.so" || exit 1
cp "$MNN_LIB" "$JNI_OUT/libMNN.so"
CXX_SHARED="$(find "$NDK_POSIX/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib" -name libc++_shared.so -path '*aarch64-linux-android*' | head -1)"
[ -n "$CXX_SHARED" ] || CXX_SHARED="$(find "$NDK_POSIX" -name libc++_shared.so -path '*aarch64*' | head -1)"
[ -n "$CXX_SHARED" ] || { echo "libc++_shared.so not found in NDK" >&2; exit 1; }
cp "$CXX_SHARED" "$JNI_OUT/"
ls -la "$JNI_OUT"

echo "[5/6] packaging APK..."
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes.dex"
"$PY" - "$BUILD_DIR" <<'EOF'
import sys, zipfile, os
bd, base = sys.argv[1], os.path.basename(os.getcwd())
boot_dir = os.getcwd()
apk = os.path.join(bd, 'unsigned.unaligned.apk')
with zipfile.ZipFile(apk, 'a') as z:
    z.write(os.path.join(bd, 'classes.dex'), 'classes.dex')
    jni = os.path.join(bd, 'jniLibs')
    if os.path.isdir(jni):
        for root, _, fs in os.walk(jni):
            for f in fs:
                full = os.path.join(root, f)
                z.write(full, 'lib/arm64-v8a/' + f, compress_type=zipfile.ZIP_STORED)
    for root, _, files in os.walk(os.path.join(os.getcwd(), 'META-INF', 'xposed')):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, os.getcwd()).replace('\\', '/'))
print('apk entries:', [i.filename for i in zipfile.ZipFile(apk).infolist()])
EOF

echo "[6/6] zipalign + sign..."
"$BUILD_TOOLS/zipalign.exe" -f -P 16 4 "$BUILD_DIR/unsigned.unaligned.apk" "$BUILD_DIR/aligned.apk"
DEBUG_KEYSTORE="$(cygpath -am "${DEBUG_KEYSTORE:-$USERHOME/.android/debug.keystore}")"
if [ ! -f "$DEBUG_KEYSTORE" ]; then
    mkdir -p "$(dirname "$DEBUG_KEYSTORE")"
    keytool -genkeypair -keystore "$DEBUG_KEYSTORE" -storepass android \
        -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000
fi
APK="$DIST_DIR/$APK_NAME.apk"
"$BUILD_TOOLS/apksigner.bat" sign --ks "$DEBUG_KEYSTORE" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$BUILD_DIR/aligned.apk"
"$BUILD_TOOLS/apksigner.bat" verify --print-certs "$APK"
echo "Build complete: $APK"
