#!/bin/bash
# LocalAiNotif build (no Gradle; aapt2 + javac + d8 + apksigner)
# Supports Windows Git Bash and Linux (GitHub Actions ubuntu-latest).
# Signs via SIGNING_CONFIG (or SIGNING_DIR/signing-backup.json).
set -euo pipefail

IS_WINDOWS=0
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;;
esac

norm_path() {
    if [ "$IS_WINDOWS" -eq 1 ]; then
        cygpath -am "$1"
    else
        printf '%s\n' "$1"
    fi
}

PROJECT_DIR="$(cd "$(dirname "$0")" && norm_path "$(pwd)")"
cd "$PROJECT_DIR"

if [ "$IS_WINDOWS" -eq 1 ]; then
    SDK_DEFAULT="/d/Android/Sdk"
    PY_DEFAULT="/d/Python312/python.exe"
    HOST_TAG="windows-x86_64"
    AAPT2_BIN="aapt2.exe"
    D8_BIN="d8.bat"
    ZIPALIGN_BIN="zipalign.exe"
    CXX_BIN="clang++.exe"
    CP_SEP=";"
    SIGNING_DIR_DEFAULT='D:/onedrive/安卓签名密钥/LocalAiNotif'
else
    SDK_DEFAULT="${ANDROID_HOME:-$HOME/Android/Sdk}"
    PY_DEFAULT="python3"
    case "$(uname -s)" in
        Darwin*) HOST_TAG="darwin-x86_64" ;;
        *) HOST_TAG="linux-x86_64" ;;
    esac
    AAPT2_BIN="aapt2"
    D8_BIN="d8"
    ZIPALIGN_BIN="zipalign"
    CXX_BIN="clang++"
    CP_SEP=":"
    SIGNING_DIR_DEFAULT=""
fi

SDK_DIR="$(norm_path "${ANDROID_SDK_ROOT:-$SDK_DEFAULT}")"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
BUILD_TOOLS="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION"
PLATFORM_API="${PLATFORM_API:-36}"
ANDROID_JAR="$(norm_path "${ANDROID_JAR:-$SDK_DIR/platforms/android-$PLATFORM_API/android.jar}")"
LIBXPOSED_JAR="$PROJECT_DIR/libs/libxposed-api-102.jar"
DIST_DIR="$(norm_path "${DIST_DIR:-$PROJECT_DIR/dist}")"
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$PROJECT_DIR/AndroidManifest.xml")"
APK_NAME="LocalAiNotif-v${VERSION}"
LINK_FLAGS=()
case "${BUILD_TYPE:-debug}" in
    debug) LINK_FLAGS+=(--debug-mode); APK_NAME+="-debug" ;;
    release) ;;
    *) echo "BUILD_TYPE must be debug or release" >&2; exit 2 ;;
esac
BUILD_SUBDIR="build/${BUILD_TYPE:-debug}"
BUILD_DIR="$PROJECT_DIR/$BUILD_SUBDIR"
PY="${PYTHON:-$PY_DEFAULT}"
USERHOME="${HOME:-${USERPROFILE:-$PROJECT_DIR}}"
MNN_DIR="$(norm_path "${MNN_DIR:-$USERHOME/tmp/MNN}")"
NDK_VERSION="${NDK_VERSION:-28.2.13676358}"
NDK_DIR="$(norm_path "${NDK_DIR:-$SDK_DIR/ndk/$NDK_VERSION}")"
NDK_POSIX="${NDK_POSIX:-$NDK_DIR}"
CXX="$NDK_DIR/toolchains/llvm/prebuilt/$HOST_TAG/bin/$CXX_BIN"
MNN_NATIVE_BUILD="$PROJECT_DIR/build/mnn-hexagon"
MNN_LIB="$(norm_path "${MNN_LIB:-$MNN_NATIVE_BUILD/libMNN.so}")"
MNN_LIB_DIR="$(dirname "$MNN_LIB")"
HEXAGON_OPS_DIR="$(norm_path "${HEXAGON_OPS_DIR:-$PROJECT_DIR/libs/hexagon}")"
JNI_OUT="$BUILD_DIR/jniLibs/arm64-v8a"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "[1/6] aapt2: compiling resources..."
"$BUILD_TOOLS/$AAPT2_BIN" compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/res.zip"

echo "[2/6] aapt2: linking manifest..."
"$BUILD_TOOLS/$AAPT2_BIN" link \
    "${LINK_FLAGS[@]}" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unsigned.unaligned.apk" \
    "$BUILD_DIR/res.zip"

echo "[3/6] javac: compiling sources..."
find src -name "*.java" | LC_ALL=C sort > "$BUILD_DIR/sources.txt"
javac --release 17 \
    -encoding UTF-8 \
    -cp "$ANDROID_JAR${CP_SEP}$LIBXPOSED_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt" 2> "$BUILD_DIR/javac.err" || { cat "$BUILD_DIR/javac.err"; exit 1; }
cat "$BUILD_DIR/javac.err"

echo "[4/6] d8: dexing..."
find "$BUILD_SUBDIR/classes" -name "*.class" | LC_ALL=C sort > "$BUILD_DIR/classes.txt"
"$BUILD_TOOLS/$D8_BIN" \
    "--${BUILD_TYPE:-debug}" \
    --min-api 34 \
    --lib "$ANDROID_JAR" \
    --classpath "$LIBXPOSED_JAR" \
    --output "$BUILD_DIR/dex" \
    @"$BUILD_DIR/classes.txt"

echo "[4b/6] native: MNN + JNI wrapper..."
if [ "$MNN_LIB" = "$MNN_NATIVE_BUILD/libMNN.so" ]; then
    CMAKE_ARGS=()
    CMAKE=cmake
    if [ "$IS_WINDOWS" -eq 1 ]; then
        CMAKE="$SDK_DIR/cmake/3.22.1/bin/cmake.exe"
        CMAKE_ARGS+=(-G Ninja "-DCMAKE_MAKE_PROGRAM=$SDK_DIR/cmake/3.22.1/bin/ninja.exe")
    fi
    "$CMAKE" -S "$MNN_DIR" -B "$MNN_NATIVE_BUILD" "${CMAKE_ARGS[@]}" \
        "-DCMAKE_PROJECT_INCLUDE=$PROJECT_DIR/jni/MnnHexagon.cmake" \
        "-DCMAKE_TOOLCHAIN_FILE=$NDK_DIR/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_NATIVE_API_LEVEL=android-24 -DANDROID_STL=c++_shared \
        -DCMAKE_BUILD_TYPE=Release "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384" \
        -DMNN_HEXAGON=ON -DMNN_HEXAGON_APP_POWER_HOLD=ON -DMNN_USE_LOGCAT=ON \
        -DMNN_OPENCL=OFF -DMNN_VULKAN=ON -DMNN_VULKAN_IMAGE=OFF -DMNN_USE_SYSTEM_LIB=OFF \
        -DMNN_SEP_BUILD=OFF -DMNN_BUILD_SHARED_LIBS=ON -DMNN_BUILD_FOR_ANDROID_COMMAND=ON \
        -DMNN_BUILD_LLM=ON -DMNN_SUPPORT_TRANSFORMER_FUSE=ON -DMNN_ARM82=ON -DMNN_LOW_MEMORY=ON \
        -DMNN_OPENMP=OFF -DMNN_KLEIDIAI=OFF -DMNN_BUILD_DEMO=OFF -DMNN_LLM_BUILD_DEMO=OFF
    "$CMAKE" --build "$MNN_NATIVE_BUILD" --target MNN --parallel "${BUILD_JOBS:-8}"
fi
mkdir -p "$JNI_OUT"
"$PY" - "$MNN_LIB" <<'EOF'
import pathlib, sys
binary = pathlib.Path(sys.argv[1]).read_bytes()
if any(marker not in binary for marker in (
        b'LocalAiNotif: Hexagon projection backend active',
        b'LocalAiNotif: Hexagon GatedRMSNorm backend active', b'vkCreateInstance')) or b'libOpenCL.so' in binary:
    sys.exit('libMNN.so must match the Hexagon and Vulkan build configuration.')
EOF
cp "$HEXAGON_OPS_DIR/libMNN_htpops.so" "$HEXAGON_OPS_DIR"/libMNN_htpops_skelV[0-9][0-9].so "$JNI_OUT/"
"$PY" "$PROJECT_DIR/tools/hexagon/verify.py" "$JNI_OUT"
"$CXX" --target=aarch64-linux-android24 -std=c++17 -O2 -fPIC -shared \
    -I "$MNN_DIR/include" -I "$MNN_DIR/source" -I "$MNN_DIR/express" \
    -I "$MNN_DIR/schema/current" -I "$MNN_DIR/3rd_party/flatbuffers/include" \
    -I "$MNN_DIR/3rd_party/half" -I "$MNN_DIR/transformers/llm/engine/include" \
    "$PROJECT_DIR/jni/localai_llm_jni.cpp" \
    -L "$MNN_LIB_DIR" -lMNN \
    "-Wl,-z,max-page-size=16384" \
    -o "$JNI_OUT/liblocalai_llm.so" || exit 1
cp "$MNN_LIB" "$JNI_OUT/libMNN.so"
CXX_SHARED="$(find "$NDK_POSIX/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib" -name libc++_shared.so -path '*aarch64-linux-android*' | head -1)"
[ -n "$CXX_SHARED" ] || CXX_SHARED="$(find "$NDK_POSIX" -name libc++_shared.so -path '*aarch64*' | head -1)"
[ -n "$CXX_SHARED" ] || { echo "libc++_shared.so not found in NDK" >&2; exit 1; }
cp "$CXX_SHARED" "$JNI_OUT/"
ls -la "$JNI_OUT"

echo "[5/6] packaging APK..."
"$PY" - "$BUILD_DIR" <<'EOF'
import sys, zipfile, os
bd = sys.argv[1]
apk = os.path.join(bd, 'unsigned.unaligned.apk')
dex_dir = os.path.join(bd, 'dex')
dex_files = sorted(f for f in os.listdir(dex_dir) if f.endswith('.dex'))
if not dex_files:
    sys.exit('ERROR: no .dex files produced by d8')
with zipfile.ZipFile(apk, 'a') as z:
    for f in dex_files:
        z.write(os.path.join(dex_dir, f), f)
    jni = os.path.join(bd, 'jniLibs')
    if os.path.isdir(jni):
        for root, _, fs in os.walk(jni):
            for f in sorted(fs):
                full = os.path.join(root, f)
                z.write(full, 'lib/arm64-v8a/' + f, compress_type=zipfile.ZIP_STORED)
    for root, _, files in os.walk(os.path.join(os.getcwd(), 'META-INF', 'xposed')):
        for f in sorted(files):
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, os.getcwd()).replace('\\', '/'))
print('apk entries:', [i.filename for i in zipfile.ZipFile(apk).infolist()])
EOF

echo "[6/6] zipalign + sign..."
"$BUILD_TOOLS/$ZIPALIGN_BIN" -f -P 16 4 "$BUILD_DIR/unsigned.unaligned.apk" "$BUILD_DIR/aligned.apk"
APK="$DIST_DIR/$APK_NAME.apk"
SIGNING_DIR_INPUT="${SIGNING_DIR:-$SIGNING_DIR_DEFAULT}"
SIGNING_CONFIG_INPUT="${SIGNING_CONFIG:-${SIGNING_DIR_INPUT:+$SIGNING_DIR_INPUT/signing-backup.json}}"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"
"$PY" - "$APKSIGNER_JAR" "$SIGNING_CONFIG_INPUT" "$BUILD_DIR/aligned.apk" "$APK" <<'EOF'
import json, os, subprocess, sys

apksigner_jar, config_path, aligned_apk, out_apk = sys.argv[1:5]
if not os.path.isfile(apksigner_jar):
    sys.exit(f"ERROR: apksigner.jar not found at {apksigner_jar}")
if not os.path.isfile(config_path):
    sys.exit(
        f"ERROR: Signing config not found: {config_path}\n"
        "Set SIGNING_DIR or SIGNING_CONFIG to your signing-backup.json (never auto-generate a key)."
    )

with open(config_path, "r", encoding="utf-8") as f:
    cfg = json.load(f)

sdir = os.path.dirname(os.path.abspath(config_path))
ks_path = os.path.join(sdir, cfg["keystore"])
if not os.path.isfile(ks_path):
    sys.exit(f"ERROR: Release keystore missing: {ks_path}")

env = os.environ.copy()
env["LOCALAI_KS_PASS"] = cfg["store_password"]
env["LOCALAI_KEY_PASS"] = cfg.get("key_password", cfg["store_password"])

cmd = ["java", "-jar", apksigner_jar, "sign"]
lineage_rel = cfg.get("lineage")
legacy_rel = cfg.get("legacy_keystore")
if lineage_rel and legacy_rel:
    lineage_path = os.path.join(sdir, lineage_rel)
    legacy_path = os.path.join(sdir, legacy_rel)
    if not os.path.isfile(lineage_path):
        sys.exit(f"ERROR: v3 signing lineage missing: {lineage_path}")
    if not os.path.isfile(legacy_path):
        sys.exit(f"ERROR: Legacy keystore for v3 rotation missing: {legacy_path}")
    env["LOCALAI_LEGACY_PASS"] = cfg["legacy_password"]
    cmd += [
        "--lineage", lineage_path,
        "--ks", legacy_path,
        "--ks-key-alias", cfg["legacy_alias"],
        "--ks-pass", "env:LOCALAI_LEGACY_PASS",
        "--key-pass", "env:LOCALAI_LEGACY_PASS",
        "--next-signer",
    ]

cmd += [
    "--ks", ks_path,
    "--ks-key-alias", cfg["key_alias"],
    "--ks-pass", "env:LOCALAI_KS_PASS",
    "--key-pass", "env:LOCALAI_KEY_PASS",
    "--out", out_apk,
    aligned_apk,
]

res = subprocess.run(cmd, env=env)
if res.returncode != 0:
    sys.exit(res.returncode)

ver = subprocess.run(
    ["java", "-jar", apksigner_jar, "verify", "-v", "--print-certs", out_apk]
)
if ver.returncode != 0:
    sys.exit(ver.returncode)
EOF
echo "Build complete: $APK"
