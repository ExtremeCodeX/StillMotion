#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# == 0 ]]; then
    echo "Uso: APKTOOL_JAR=/caminho/apktool.jar $0 apks/*.apk" >&2
    exit 2
fi
: "${APKTOOL_JAR:?Informe o JAR oficial do Apktool}"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
tools=$sdk/build-tools/37.0.0
analysis="$PWD/analysis"
mkdir -p "$analysis/apktool-framework"
python3 scripts/inventory-apk.py --validate-only "$@"
python3 scripts/inventory-apk.py --directory "$analysis/inventory" "$@"
for apk in "$@"; do
    name=$(basename "$apk" .apk)
    output=$(mktemp -d "$analysis/$name.XXXXXX")
    "$tools/aapt2" dump badging "$apk" > "$output/badging.txt"
    "$tools/aapt2" dump xmltree "$apk" --file AndroidManifest.xml > "$output/manifest.txt"
    "$tools/apksigner" verify --verbose --print-certs "$apk" > "$output/signature.txt"
    if ! jadx -j 3 -r -d "$output/jadx" "$apk" > "$output/jadx.log" 2>&1; then
        echo "JADX retornou diagnóstico. Consulte $output/jadx.log e o smali." >&2
    fi
    java -Xmx1g -jar "$APKTOOL_JAR" d -j 2 -p "$analysis/apktool-framework" \
        --no-assets -o "$output/apktool" "$apk" > "$output/apktool.log" 2>&1
    echo "$output"
done
