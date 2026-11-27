#!/usr/bin/env python3
"""Package clean, local captures of the Flyme wallpaper surface for StillMotion."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

from prepare_flyme_scene_tools import read_catalog

MAX_VIDEO_BYTES = 32 * 1024 * 1024
MAX_POSTER_BYTES = 4 * 1024 * 1024


def ffmpeg(*arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", *arguments], check=True)


def encode(item, work):
    key, source, start, end, crop_top = item
    video = work / f"{key}.mp4"
    poster = work / f"{key}.webp"
    # Files must show only the wallpaper surface before conversion.
    filters = ["fps=30"]
    if crop_top:
        filters.append(f"crop=iw:ih-{crop_top}:0:{crop_top}")
    filters.extend(("scale=360:780:force_original_aspect_ratio=increase", "crop=360:780", "setsar=1"))
    if key.startswith("bubble_"):
        filters.extend((f"trim=duration={end - start:.6f}", "reverse"))
    ffmpeg("-threads", "1", "-filter_threads", "1", "-ss", str(start), "-i", str(source),
           "-t", str(end - start), "-map", "0:v:0",
           "-an", "-map_metadata", "-1", "-map_chapters", "-1", "-vf", ",".join(filters),
           "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "fast", "-crf", "16",
           "-threads", "1", "-movflags", "+faststart", str(video))
    ffmpeg("-threads", "1", "-sseof", "-0.034", "-i", str(video), "-map", "0:v:0", "-frames:v", "1",
           "-pix_fmt", "rgb24", "-c:v", "libwebp", "-lossless", "1", str(poster))
    for file, limit in ((video, MAX_VIDEO_BYTES), (poster, MAX_POSTER_BYTES)):
        if not file.is_file() or not 0 < file.stat().st_size <= limit:
            raise ValueError(f"Arquivo ausente ou fora do limite: {file.name}")
    return video, poster


def build(input_directory, cuts_file, output, family):
    catalog = read_catalog()
    cuts = json.loads(cuts_file.read_text()) if cuts_file else {}
    unknown = set(cuts) - set(catalog)
    if unknown:
        raise ValueError(f"Temas desconhecidos nos cortes: {', '.join(sorted(unknown))}")
    if family:
        catalog = {name: info for name, info in catalog.items() if name.startswith(family + "_")}
    missing = [name for name in catalog if not (input_directory / f"{name}.mp4").is_file()]
    if missing:
        raise ValueError(f"Faltam {len(missing)} capturas: {', '.join(missing)}")
    items = []
    for name in catalog:
        source = input_directory / f"{name}.mp4"
        duration = float(subprocess.check_output([
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1", str(source)], text=True).strip())
        trim = cuts.get(name, [0, duration])
        start, end = trim[:2]
        crop_top = trim[2] if len(trim) == 3 else 0
        if not 0 <= start < end <= duration or not .25 <= end - start <= 14:
            raise ValueError(f"{name}: recorte inválido ou fora do intervalo de 0,25–14 s")
        if len(trim) not in (2, 3) or type(crop_top) is not int or not 0 <= crop_top <= 80 or crop_top % 2:
            raise ValueError(f"{name}: corte superior inválido")
        items.append((name, source, start, end, crop_top))
    if output.exists():
        raise ValueError(f"O destino já existe: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        stage = Path(directory)
        files = [encode(item, stage) for item in items]
        staged_zip = stage / "scene-pack.zip"
        with zipfile.ZipFile(staged_zip, "w", zipfile.ZIP_STORED) as archive:
            if family == "bubble":
                archive.writestr("bubble-unlock-v2", b"")
            for pair in files:
                for file in pair:
                    archive.write(file, file.name)
        staged_zip.rename(output)
    with output.open("rb") as reader:
        print(f"{hashlib.file_digest(reader, 'sha256').hexdigest()}  {output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cuts", type=Path, help="JSON [início, fim, corte superior opcional em pixels] por tema")
    parser.add_argument("--family", choices=("bubble", "cosmic", "phoenix"),
                        help="Gera apenas o pacote completo daquela família")
    options = parser.parse_args()
    try:
        build(options.captures, options.cuts, options.output, options.family)
    except ValueError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
