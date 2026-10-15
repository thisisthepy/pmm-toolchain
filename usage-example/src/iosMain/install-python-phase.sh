#!/usr/bin/env bash
# Xcode Run Script phase for usage-example's iOS app (toolchain#22): put this app's python/ payload,
# CPython's standard library and Python.framework into the .app.
#
# The payload comes from this build: stagePythonBundleIosForXcode prints PYTHON_PAYLOAD_DIR=, which is
# passed to python-multiplatform's install-python.sh as PYTHON_PAYLOAD_DIR (that stays valid after
# python-multiplatform#90). install-python.sh is the ONLY phase that copies <app>/python/; do not also
# hand-wire a second phase that copies python/.
#
# INTERIM, until python-multiplatform#90 gives consumers the iOS wiring through its published plugin:
# a python-multiplatform checkout (PYTHON_MULTIPLATFORM_DIR, an Xcode build setting or environment
# variable) supplies install-python.sh, the stdlib staging it runs, and Python.xcframework.
set -euo pipefail

# TODO(python-multiplatform#90): no checkout once the plugin provides these to consumers.
: "${PYTHON_MULTIPLATFORM_DIR:?set PYTHON_MULTIPLATFORM_DIR to a python-multiplatform checkout (interim, python-multiplatform#90)}"

# 1. This app's payload, from usage-example's own build. Captured, parsed, then checked, so a failing
#    Gradle can never hand rsync an empty source.
cd "$SRCROOT/../../.."
out=$(./gradlew -q -p usage-example stagePythonBundleIosForXcode)
payload=$(printf '%s\n' "$out" | sed -n 's/^PYTHON_PAYLOAD_DIR=//p')
[ -n "$payload" ] && [ -d "$payload" ] || { echo "error: no Python payload dir from stagePythonBundleIosForXcode" >&2; exit 1; }
export PYTHON_PAYLOAD_DIR="$payload"

# 2. Python.framework (libpython) into <app>/Frameworks, for the simulator or the device slice.
# TODO(python-multiplatform#90): from the plugin's staged Python.xcframework, not the checkout's build/.
xcframework="$PYTHON_MULTIPLATFORM_DIR/python-multiplatform/build/python-standalone/extracted/${PYTHON_VERSION:-3.15.0}/ios/Python.xcframework"
case "${EFFECTIVE_PLATFORM_NAME:-}" in
    -iphonesimulator) slice="ios-arm64_x86_64-simulator" ;;
    -iphoneos) slice="ios-arm64" ;;
    *) echo "error: unknown EFFECTIVE_PLATFORM_NAME '${EFFECTIVE_PLATFORM_NAME:-}'" >&2; exit 1 ;;
esac
[ -d "$xcframework/$slice/Python.framework" ] || { echo "error: no $xcframework/$slice/Python.framework (run :python-multiplatform:downloadPython_ios in the checkout)" >&2; exit 1; }
: "${TARGET_BUILD_DIR:?Xcode must set TARGET_BUILD_DIR}" "${FRAMEWORKS_FOLDER_PATH:?Xcode must set FRAMEWORKS_FOLDER_PATH}"
mkdir -p "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
rsync -a --delete "$xcframework/$slice/Python.framework/" "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/Python.framework/"
if [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
    codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --timestamp=none "$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH/Python.framework"
fi

# 3. The stdlib prefix and the payload, by python-multiplatform's own phase script.
# TODO(python-multiplatform#90): run from this project's Gradle root, not the checkout.
cd "$PYTHON_MULTIPLATFORM_DIR"
/bin/bash python-multiplatform-gradle-plugin/src/main/resources/xcode/install-python.sh
