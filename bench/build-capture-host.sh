#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
tools="$sdk/build-tools/37.0.0"
android="$sdk/platforms/android-35/android.jar"
out="$PWD/build/capture-host"
key="$PWD/build/signing/debug.keystore"
mkdir -p "$out"/{classes,dex}
"$tools/aapt2" link -I "$android" --manifest bench/capture-host/AndroidManifest.xml \
    -o "$out/unsigned.apk"
javac --release 8 -classpath "$android" -d "$out/classes" bench/capture-host/CaptureActivity.java
jar cf "$out/classes.jar" -C "$out/classes" .
"$tools/d8" --min-api 26 --lib "$android" --output "$out/dex" "$out/classes.jar"
(cd "$out/dex"; zip -q -X "$out/unsigned.apk" classes.dex)
"$tools/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
"$tools/apksigner" sign --ks "$key" --ks-pass pass:android --key-pass pass:android \
    --out "$out/CaptureHost.apk" "$out/aligned.apk"
"$tools/apksigner" verify "$out/CaptureHost.apk"
