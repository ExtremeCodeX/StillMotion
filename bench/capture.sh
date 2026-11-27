#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 2 ]]; then
    echo "Uso: $0 SERIAL_ADB DIRETORIO_LOCAL_NOVO" >&2
    exit 2
fi
serial=$1
output=$2
package=dev.stillmotion.wallpaper
adb_cmd=(adb -s "$serial")
"${adb_cmd[@]}" get-state
mkdir "$output"
{
    for property in ro.product.model ro.build.version.release ro.build.version.sdk \
        ro.build.version.oneui ro.product.cpu.abilist; do
        printf '%s=' "$property"
        "${adb_cmd[@]}" shell getprop "$property"
    done
} > "$output/device.txt"
"${adb_cmd[@]}" shell dumpsys meminfo "$package" > "$output/meminfo.txt"
"${adb_cmd[@]}" shell dumpsys wallpaper > "$output/wallpaper.txt"
"${adb_cmd[@]}" shell dumpsys batterystats "$package" > "$output/batterystats.txt"
"${adb_cmd[@]}" logcat -d -v monotonic -s StillMotion:D > "$output/stillmotion-logcat.txt"
