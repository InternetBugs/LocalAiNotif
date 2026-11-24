#!/usr/bin/env bash
set -euo pipefail

: "${MNN_DIR:?Set MNN_DIR to the MNN checkout pinned in release.yml}"
: "${HEXAGON_SDK_ROOT:?Set HEXAGON_SDK_ROOT to the Linux Hexagon SDK}"
script_dir=$(cd "$(dirname "$0")" && pwd)
project_dir=$(cd "$script_dir/../.." && pwd)
ops="$MNN_DIR/source/backend/hexagon/htp-ops-lib"
if [[ -z "${HEXAGON_TOOLS_ROOT:-}" ]]; then
    tools_packages=("$HEXAGON_SDK_ROOT"/tools/HEXAGON_Tools/*)
    if [[ ${#tools_packages[@]} != 1 || ! -x "${tools_packages[0]}/Tools/bin/hexagon-strip" ]]; then
        echo "Set HEXAGON_TOOLS_ROOT to the selected Hexagon compiler package" >&2
        exit 1
    fi
    HEXAGON_TOOLS_ROOT="${tools_packages[0]}"
fi
export HEXAGON_TOOLS_ROOT
tools_version=$(basename "$HEXAGON_TOOLS_ROOT")
build_root="${HEXAGON_BUILD_DIR:-$project_dir/build/hexagon-ops}"
out="$project_dir/libs/hexagon"
mkdir -p "$out"
if [ "$#" -eq 0 ]; then
    for target in v73 v75 v79 v81 v83 v85; do
        if [[ -d "$HEXAGON_SDK_ROOT/rtos/qurt/compute$target" ]]; then set -- "$@" "$target"; fi
    done
    if [[ "$#" -eq 0 ]]; then echo "No supported Hexagon targets in $HEXAGON_SDK_ROOT" >&2; exit 1; fi
fi
for arch in "$@"; do
    [[ "$arch" =~ ^v[0-9]{2}$ ]] && [[ -d "$HEXAGON_SDK_ROOT/rtos/qurt/compute$arch" ]] || {
        echo "Missing Hexagon SDK compute target: $arch" >&2
        exit 1
    }
    build_dir="$build_root/$arch"
    cmake -S "$ops" -B "$build_dir" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$HEXAGON_SDK_ROOT/build/cmake/hexagon_toolchain.cmake" \
        -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" -DDSP_ARCH="$arch" -DDSP_VERSION="$arch" \
        -DOS_TYPE=DSP -DDSP_TYPE=cdsp "-DPREBUILT_LIB_DIR=hexagon_ReleaseG_toolv${tools_version%%.*}_$arch" \
        -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
    cmake --build "$build_dir" --target htp_ops_skel --parallel "${BUILD_JOBS:-8}"
    "$HEXAGON_TOOLS_ROOT/Tools/bin/hexagon-strip" --strip-debug \
        -o "$out/libMNN_htpops_skel${arch^^}.so" "$build_dir/libMNN_htpops_skel.so"
done
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" --target=aarch64-linux-android24 \
        -std=gnu11 -O2 -fPIC -shared -I"$build_dir" -I"$script_dir" \
        -I"$HEXAGON_SDK_ROOT/incs" -I"$HEXAGON_SDK_ROOT/incs/stddef" -I"$ops/include" -I"$ops/.." \
        "$build_dir/htp_ops_stub.c" "$ops/src/host/op_export.c" "$ops/src/host/session.c" "$script_dir/device.c" \
        -L"$HEXAGON_SDK_ROOT/ipc/fastrpc/remote/ship/android_aarch64" -lcdsprpc -llog \
        -Wl,-z,max-page-size=16384,-z,defs,-soname,libMNN_htpops.so -o "$out/libMNN_htpops.so"
fi
