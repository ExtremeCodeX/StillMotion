#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_LINT:?Informe o executável lint do Android SDK Command-line Tools}"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
out="$PWD/build/lint"
mkdir -p "$out"/{src,res} "$PWD/build/lint-user"
cp app/src/main/AndroidManifest.xml "$out/"
cp -R app/src/main/java/. "$out/src/"
cp -R build/debug/pack/res/. "$out/res/"
cp build/debug/generated/BuildConfig.java "$out/src/"
export ANDROID_USER_HOME="$PWD/build/lint-user"
export LINT_OPTS="${LINT_OPTS:-} -Duser.home=\"$PWD/build/lint-user\""
"$ANDROID_LINT" --sdk-home "$sdk" \
    --compile-sdk-version 35 --classpath "$PWD/build/debug/classes" --exitcode \
    --text "$out/report.txt" --xml "$out/report.xml" "$out"
cat "$out/report.txt"
