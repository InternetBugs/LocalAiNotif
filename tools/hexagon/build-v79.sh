#!/usr/bin/env bash
set -euo pipefail

: "${MNN_DIR:?Set MNN_DIR to the MNN checkout pinned in release.yml}"
: "${HEXAGON_SDK_ROOT:?Set HEXAGON_SDK_ROOT to the Linux Hexagon SDK 6.4.0.2}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Linux Android NDK r28c}"
script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
ops="$MNN_DIR/source/backend/hexagon/htp-ops-lib"
export HEXAGON_TOOLS_ROOT="${HEXAGON_TOOLS_ROOT:-$HEXAGON_SDK_ROOT/tools/HEXAGON_Tools/19.0.04}"
build_dir="$project_dir/build/hexagon-ops-v79"
out="$project_dir/libs/hexagon-v79"
cmake -S "$ops" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$HEXAGON_SDK_ROOT/build/cmake/hexagon_toolchain.cmake" \
    -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" -DDSP_ARCH=v79 -DDSP_VERSION=v79 \
    -DOS_TYPE=DSP -DDSP_TYPE=cdsp -DPREBUILT_LIB_DIR=hexagon_ReleaseG_toolv19_v79 \
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
cmake --build "$build_dir" --target htp_ops_skel --parallel "${BUILD_JOBS:-8}"
mkdir -p "$out"
"$HEXAGON_TOOLS_ROOT/Tools/bin/hexagon-strip" --strip-debug -o "$out/libMNN_htpops_skelV79.so" \
    "$build_dir/libMNN_htpops_skel.so"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --target=aarch64-linux-android24 \
    -std=gnu11 -O2 -fPIC -shared -I"$build_dir" -I"$script_dir" \
    -I"$HEXAGON_SDK_ROOT/incs" -I"$HEXAGON_SDK_ROOT/incs/stddef" -I"$ops/include" -I"$ops/.." \
    "$build_dir/htp_ops_stub.c" "$ops/src/host/op_export.c" "$ops/src/host/session.c" \
    -L"$HEXAGON_SDK_ROOT/ipc/fastrpc/remote/ship/android_aarch64" -lcdsprpc -llog \
    -Wl,-z,max-page-size=16384,-z,defs,-soname,libMNN_htpops.so -o "$out/libMNN_htpops.so"
