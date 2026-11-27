#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

variant=${1:-debug}
case "$variant" in
    debug|probe) ;;
    *) echo "Uso: $0 [debug|probe]" >&2; exit 2 ;;
esac

sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}
tools=$sdk/build-tools/37.0.0
android_jar=$sdk/platforms/android-35/android.jar
ndk=${ANDROID_NDK_HOME:-$sdk/ndk/27.3.13750724}
ndk_bin=$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin
out="$PWD/build/$variant"
signing="$PWD/build/signing"
mkdir -p "$out"/{generated,classes,dex,pack} "$signing"
mkdir -p "$out"/pack/{res/raw,res/drawable-nodpi}
cp -R app/src/main/res/. "$out/pack/res/"

rm -f "$out"/pack/res/raw/orbita_*.webp "$out"/pack/res/raw/orbita_*.mp4
rm -f "$out"/pack/res/raw/{dunes,orbit}_{poster.webp,motion.bin} "$out"/pack/res/drawable-nodpi/wallpaper_thumbnail.webp
cp assets/themes/stillmotion_icon.png "$out/pack/res/drawable-nodpi/"
cp assets/flyme-scenes.tsv "$out/pack/res/raw/flyme_scenes.tsv"

probe=false
dunes_poster=0
dunes_motion=0
orbit_poster=0
orbit_motion=0
if [[ "$variant" == probe ]]; then
    probe=true
    for theme in dunes orbit; do
        cp "assets/themes/$theme/poster.webp" "$out/pack/res/raw/${theme}_poster.webp"
        cp "assets/themes/$theme/motion.bin" "$out/pack/res/raw/${theme}_motion.bin"
    done
    dunes_poster=R.raw.dunes_poster
    dunes_motion=R.raw.dunes_motion
    orbit_poster=R.raw.orbit_poster
    orbit_motion=R.raw.orbit_motion
fi
cat > "$out/generated/BuildConfig.java" <<EOF_CONFIG
package dev.stillmotion.wallpaper;
final class BuildConfig {
    static final boolean DEBUG = true;
    static final boolean PROBE = $probe;
    static final int DUNES_POSTER = $dunes_poster;
    static final int DUNES_MOTION = $dunes_motion;
    static final int ORBIT_POSTER = $orbit_poster;
    static final int ORBIT_MOTION = $orbit_motion;
}
EOF_CONFIG

"$tools/aapt2" compile --dir "$out/pack/res" -o "$out/resources.zip"
"$tools/aapt2" link -I "$android_jar" --manifest app/src/main/AndroidManifest.xml \
    --java "$out/generated" --debug-mode \
    -0 webp -0 mp4 -o "$out/resources.apk" "$out/resources.zip"

mapfile -t java_sources < <(find app/src/main/java "$out/generated" -name '*.java' -type f | sort)
javac --release 8 -classpath "$android_jar" -d "$out/classes" "${java_sources[@]}"
jar cf "$out/classes.jar" -C "$out/classes" .
"$tools/d8" --debug --min-api 26 --lib "$android_jar" \
    --output "$out/dex" "$out/classes.jar"
cp "$out/resources.apk" "$out/unaligned.apk"
(cd "$out/dex"; zip -q -X "$out/unaligned.apk" classes.dex)

if [[ "$variant" == debug ]]; then
    rust_sysroot=()
    if [[ -n "${STILLMOTION_RUST_SYSROOT:-}" ]]; then
        rust_sysroot=(--sysroot "$STILLMOTION_RUST_SYSROOT")
    fi
    for abi in arm64-v8a x86_64; do
        if [[ "$abi" == arm64-v8a ]]; then
            arch=aarch64
        else
            arch=x86_64
        fi
        target=$arch-linux-android
        linker=$ndk_bin/${target}26-clang
        if [[ ! -x "$linker" ]]; then
            echo "NDK r27d ausente: $linker" >&2
            exit 2
        fi
        mkdir -p "$out/pack/lib/$abi"
        rustc --edition=2024 --crate-name stillmotion_core --crate-type cdylib \
            --target "$target" "${rust_sysroot[@]}" \
            -C linker="$linker" -C link-arg=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 \
            -C link-arg=-Wl,-soname,libstillmotion_core.so,-z,defs -C link-arg=-lc \
            -C opt-level="${STILLMOTION_RUST_OPT_LEVEL:-s}" -C lto -C codegen-units=1 \
            -C panic=abort -C strip=symbols native/src/lib.rs -o "$out/pack/lib/$abi/libstillmotion_core.so"
        "$ndk_bin/llvm-readelf" --dynamic "$out/pack/lib/$abi/libstillmotion_core.so" |
            grep -F '(NEEDED)' | grep -F '[libc.so]' > /dev/null
    done
    (cd "$out/pack"; zip -q -X -0 "$out/unaligned.apk" lib/*/libstillmotion_core.so)
fi

"$tools/zipalign" -f -P 16 4 "$out/unaligned.apk" "$out/aligned.apk"
# The same local development key allows replacing one debug variant with the other.
if [[ ! -f "$signing/debug.keystore" ]]; then
    keytool -genkeypair -storetype PKCS12 -keystore "$signing/debug.keystore" \
        -storepass android -alias androiddebugkey -keypass android \
        -dname 'CN=Android Debug,O=StillMotion,C=BR' -keyalg RSA -keysize 2048 -validity 3650
fi

apk="$out/StillMotion-$variant.apk"
"$tools/apksigner" sign --ks "$signing/debug.keystore" --ks-pass pass:android \
    --key-pass pass:android --out "$apk" "$out/aligned.apk"
"$tools/apksigner" verify --verbose "$apk"
"$tools/zipalign" -c -P 16 4 "$apk"
printf '%s\n' "$apk"
