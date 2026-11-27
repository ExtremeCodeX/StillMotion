#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cargo fmt --manifest-path native/Cargo.toml --check
cargo test --offline --locked --manifest-path native/Cargo.toml
cargo clippy --offline --locked --manifest-path native/Cargo.toml --all-targets -- -D warnings
cargo build --offline --locked --manifest-path native/Cargo.toml
mkdir -p build/host-jni
javac --release 8 -Xlint:all -d build/host-jni \
    app/src/main/java/dev/stillmotion/wallpaper/NativePolicy.java \
    app/src/main/java/dev/stillmotion/wallpaper/JavaPolicy.java \
    app/src/main/java/dev/stillmotion/wallpaper/Transition.java \
    app/src/main/java/dev/stillmotion/wallpaper/UnlockWindow.java \
    app/src/main/java/dev/stillmotion/wallpaper/DebugEvents.java \
    app/src/main/java/dev/stillmotion/wallpaper/OrbitaMotion.java \
    app/src/main/java/dev/stillmotion/wallpaper/CommunityArchive.java \
    app/src/main/java/dev/stillmotion/wallpaper/OrbitaArchive.java \
    app/src/test/java/dev/stillmotion/wallpaper/CommunityArchiveTest.java \
    app/src/test/java/dev/stillmotion/wallpaper/OrbitaArchiveTest.java \
    app/src/test/java/dev/stillmotion/wallpaper/OrbitaMotionTest.java \
    app/src/test/java/dev/stillmotion/wallpaper/HostJniTest.java
java -cp build/host-jni -Djava.library.path="$PWD/native/target/debug" \
    dev.stillmotion.wallpaper.HostJniTest
java -cp build/host-jni -Djava.library.path="$PWD/build/host-jni" \
    dev.stillmotion.wallpaper.HostJniTest fallback
java -cp build/host-jni dev.stillmotion.wallpaper.CommunityArchiveTest
java -cp build/host-jni dev.stillmotion.wallpaper.OrbitaArchiveTest
java -cp build/host-jni dev.stillmotion.wallpaper.OrbitaMotionTest
python3 - <<'PY'
import hashlib
import json
import struct
from pathlib import Path

from PIL import Image

for manifest in sorted(Path("assets/themes").glob("*/theme.json")):
    theme = json.loads(manifest.read_text())
    for name, expected in theme["sha256"].items():
        assert hashlib.sha256((manifest.parent / name).read_bytes()).hexdigest() == expected
    poster = manifest.parent / theme["final_image"]
    with Image.open(poster) as image:
        image.load()
        assert image.mode == "RGB" and list(image.size) == theme["size_px"]
        assert 0 < image.width * image.height <= 2_100_000
    descriptor = (manifest.parent / "motion.bin").read_bytes()
    assert struct.unpack(">cBBBHBB", descriptor) == (
        b"S", 1, 1, theme["fps_cap"], theme["duration_ms"], theme["initial_zoom_percent"] - 100, 0)
    print(f"Theme and poster: {manifest.parent.name} OK")
PY
