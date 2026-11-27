#!/usr/bin/env python3
"""Prepare an on-device video pack from a local Flyme reference APK."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

SHA256 = "d54b54e3a93d73363bfa4d043fa89799c0fcedd2c507bff611bd698c4738ea36"


def ffmpeg(*arguments):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", *arguments], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    with args.apk.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != SHA256:
            parser.error("O APK é diferente da amostra Flyme analisada.")
    if args.output.exists():
        parser.error("Use um arquivo de saída novo.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as directory:
        work = Path(directory)
        pack = work / "pack.zip"
        with zipfile.ZipFile(args.apk) as source, zipfile.ZipFile(pack, "w", zipfile.ZIP_STORED) as output:
            for index in range(1, 5):
                name = f"flyme_{index}"
                raw = work / "source.mp4"
                with source.open(f"assets/video/mz_video_wallpaper_m2461_v{index}.mp4") as clip, raw.open("wb") as file:
                    shutil.copyfileobj(clip, file)
                video = work / f"{name}.mp4"
                poster = work / f"{name}.webp"
                timeline = ("[0:v]split=2[main][tail];"
                            "[main]trim=start=2.1:end=14,setpts=PTS-STARTPTS[a];"
                            "[tail]trim=start=0:end=2.1,setpts=PTS-STARTPTS[b];"
                            "[a][b]concat=n=2:v=1:a=0,fps=30,scale=720:1560:flags=lanczos[v]")
                ffmpeg("-i", str(raw), "-filter_complex_threads", "2", "-filter_complex", timeline,
                       "-map", "[v]", "-an", "-map_metadata", "-1", "-map_chapters", "-1",
                       "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "fast", "-crf", "16",
                       "-threads", "4", "-movflags", "+faststart", str(video))
                ffmpeg("-sseof", "-0.034", "-i", str(video), "-map", "0:v:0", "-frames:v", "1",
                       "-pix_fmt", "rgb24", "-c:v", "libwebp", "-lossless", "1", str(poster))
                for path in (video, poster):
                    if not path.is_file() or not path.stat().st_size:
                        raise ValueError(f"Saída vazia: {path.name}")
                    output.write(path, path.name)
                print(name, flush=True)
        pack.rename(args.output)


if __name__ == "__main__":
    main()
