#!/usr/bin/env python3
"""Verify the signed build, least-privilege manifest, and native package layout."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/opt/android-sdk")
TOOLS = SDK / "build-tools" / "37.0.0"


def sdk_tool(name, *args):
    return subprocess.run([TOOLS / name, *args], check=True, capture_output=True, text=True).stdout


def validate_library(data, machine):
    header = struct.Struct("<16sHHIQQQIHHHHHH")
    ident, elf_type, arch, _, _, phoff, _, _, _, entry_size, count, *_ = header.unpack_from(data)
    assert ident[:6] == b"\x7fELF\x02\x01"
    assert elf_type == 3 and arch == machine
    load_segments = []
    relro_end = None
    for entry in range(count):
        kind, flags, offset, address, _, _, length, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + entry_size * entry)
        if kind == 1:
            load_segments.append((flags, address, address + length))
            assert alignment >= 16384 and offset % alignment == address % alignment
        elif kind == 0x6474e552:
            relro_end = address + length
    assert load_segments and relro_end is not None
    if any(flags & 2 and start < relro_end < end for flags, start, end in load_segments):
        assert relro_end % 16384 == 0
    for suffix in (b"reduce", b"parseTheme"):
        assert b"Java_dev_stillmotion_wallpaper_NativePolicy_" + suffix + b"\x00" in data


def verify(variant):
    apk = ROOT / "build" / variant / f"StillMotion-{variant}.apk"
    signature = sdk_tool("apksigner", "verify", "--verbose", apk)
    assert "Verified using v2 scheme (APK Signature Scheme v2): true" in signature
    sdk_tool("zipalign", "-c", "-P", "16", "4", apk)
    manifest = sdk_tool("aapt2", "dump", "xmltree", apk, "--file", "AndroidManifest.xml")
    assert not re.search(r"(?m)^\s*E: uses-permission", manifest)
    assert len(re.findall(r"(?m)^\s*E: service ", manifest)) == 2
    assert 'dev.stillmotion.wallpaper.FlymeVideoService' in manifest or '".FlymeVideoService"' in manifest
    assert "android.permission.BIND_WALLPAPER" in manifest
    assert "android.service.wallpaper.WallpaperService" in manifest
    assert 'package="dev.stillmotion.wallpaper"' in manifest
    assert 'minSdkVersion(0x0101020c)=26' in manifest
    assert 'targetSdkVersion(0x01010270)=35' in manifest
    assert re.search(r'extractNativeLibs\(0x[0-9a-f]+\)=false', manifest)
    assert not re.search(r"(?m)^\s*E: (receiver|provider|uses-library) ", manifest)
    libraries = {}
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        members = archive.namelist()
        assert "classes.dex" in members
        assert b"com.google.android.apps.wallpaper" not in archive.read("classes.dex")
        expected = {"arm64-v8a": 183, "x86_64": 62} if variant == "debug" else {}
        assert {member for member in members if member.startswith("lib/")} == {
            f"lib/{abi}/libstillmotion_core.so" for abi in expected}
        for abi, machine in expected.items():
            info = archive.getinfo(f"lib/{abi}/libstillmotion_core.so")
            assert info.compress_type == zipfile.ZIP_STORED
            data = archive.read(info)
            validate_library(data, machine)
            libraries[abi] = len(data)
    with apk.open("rb") as file:
        digest = hashlib.file_digest(file, "sha256").hexdigest()
    return {"file": str(apk.relative_to(ROOT)), "bytes": apk.stat().st_size,
            "sha256": digest, "verified": True, "native_library_bytes": libraries}


if __name__ == "__main__":
    print(json.dumps({"source": "host-build", "apk": [verify("probe"), verify("debug")]}, indent=2))
