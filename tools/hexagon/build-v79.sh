#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to the Linux Android NDK r28c}"
exec bash "$(dirname "$0")/build-ops.sh" v79
