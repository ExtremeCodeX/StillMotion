#!/usr/bin/env python3
"""Read local APKs as bounded archives and report media metadata without executing their code."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import subprocess
import tempfile
import zipfile

from PIL import Image

MAX_APK = 512 * 1024**2
MAX_MEMBER = 256 * 1024**2
MAX_TOTAL = 1024**3
MAX_ENTRIES = 40_000
IMAGE_EXTENSIONS = {".png", ".webp", ".jpg", ".jpeg", ".gif", ".avif", ".bmp"}


def check_archive(entries):
    if len(entries) > MAX_ENTRIES or sum(e.file_size for e in entries) > MAX_TOTAL:
        raise ValueError("Limite total do ZIP excedido")
    names = set()
    for entry in entries:
        name = PurePosixPath(entry.filename)
        if (name.is_absolute() or ".." in name.parts or "\\" in entry.filename
                or entry.filename in names or entry.file_size > MAX_MEMBER
                or stat.S_ISLNK(entry.external_attr >> 16)):
            raise ValueError(f"Entrada ZIP inválida: {entry.filename!r}")
        names.add(entry.filename)


def image_metadata(archive, entry):
    with archive.open(entry) as stream, Image.open(stream, formats=[
            "PNG", "WEBP", "JPEG", "GIF", "AVIF", "BMP"]) as image:
        return {
            "format": image.format,
            "width": image.width,
            "height": image.height,
            "mode": image.mode,
            "alpha": "A" in image.getbands() or "transparency" in image.info,
            "icc_profile_present": bool(image.info.get("icc_profile")),
            "animated": getattr(image, "is_animated", False),
            "estimated_rgba_bytes": image.width * image.height * 4,
        }


def video_metadata(archive, entry):
    # Never pass an untrusted archive path to the filesystem or to FFprobe.
    with tempfile.NamedTemporaryFile(suffix=".mp4") as local:
        with archive.open(entry) as stream:
            while chunk := stream.read(1024 * 1024):
                local.write(chunk)
        local.flush()
        metadata = json.loads(subprocess.run(
            ["ffprobe", "-v", "error", "-protocol_whitelist", "file",
             "-f", "mov", "-show_entries",
             "format=duration:stream=codec_type,codec_name,profile,width,height,pix_fmt,"
             "color_space,color_range,color_transfer,color_primaries,avg_frame_rate,duration,nb_frames",
             "-of", "json", local.name],
            capture_output=True, check=True, timeout=30, text=True,
        ).stdout)
    for track in metadata.get("streams", []):
        if track.get("codec_type") == "video":
            track["estimated_rgba_frame_bytes"] = track["width"] * track["height"] * 4
            track["alpha"] = track.get("pix_fmt", "").startswith(("yuva", "rgba", "bgra"))
    return metadata


def inspect(path, metadata_enabled=True):
    if path.stat().st_size > MAX_APK:
        raise ValueError("APK ultrapassa o limite de tamanho")
    with path.open("rb") as binary:
        digest = hashlib.file_digest(binary, "sha256").hexdigest()
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        check_archive(entries)
        formats = {}
        media = []
        for entry in sorted(entries, key=lambda candidate: candidate.filename):
            if entry.is_dir():
                continue
            extension = PurePosixPath(entry.filename).suffix.lower()
            aggregate = formats.setdefault(extension or "[sem extensão]",
                                           Counter(entries=0, compressed_bytes=0, unpacked_bytes=0))
            aggregate.update(entries=1, compressed_bytes=entry.compress_size,
                             unpacked_bytes=entry.file_size)
            if metadata_enabled and (extension in IMAGE_EXTENSIONS or extension == ".mp4"):
                record = {"member": entry.filename, "compressed_bytes": entry.compress_size,
                          "unpacked_bytes": entry.file_size}
                try:
                    if extension == ".mp4":
                        record.update(video_metadata(archive, entry))
                    else:
                        record.update(image_metadata(archive, entry))
                except (OSError, KeyError, ValueError, Image.DecompressionBombError,
                        subprocess.SubprocessError) as failed:
                    record["metadata_error"] = type(failed).__name__
                media.append(record)
        return {"file": path.name, "sha256": digest, "apk_bytes": path.stat().st_size,
                "entries": len(entries), "formats": formats, "media": media,
                "native_libs": [e.filename for e in entries if e.filename.startswith("lib/")],
                "asset_license": "Autorização de redistribuição não fornecida"}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", nargs="+", type=Path)
    parser.add_argument("--directory", type=Path, default=Path("analysis/inventory"))
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    if not args.validate_only:
        args.directory.mkdir(parents=True, exist_ok=True)
    for apk in args.apks:
        data = inspect(apk, metadata_enabled=not args.validate_only)
        if not args.validate_only:
            report = args.directory / (apk.stem + "-inventory.json")
            report.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
        print(f"{apk.name} {data['sha256']} {data['entries']} entradas")


if __name__ == "__main__":
    main()
