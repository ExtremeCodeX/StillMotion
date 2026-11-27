#!/usr/bin/env python3
"""Compile a local animation into a shareable StillMotion community pack."""
import argparse
import math
from pathlib import Path
import subprocess
import tempfile
import unicodedata
from zipfile import ZIP_STORED, ZipFile


def encode(source, video, start, duration, filters):
    subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-threads", "1", "-filter_threads", "1",
                    "-ss", str(start), "-i", str(source), "-t", str(duration),
                    "-map", "0:v:0", "-an", "-map_metadata", "-1", "-map_chapters", "-1",
                    "-vf", filters, "-c:v", "libx264", "-threads", "1", "-preset", "fast",
                    "-pix_fmt", "yuv420p", "-crf", "17", "-movflags", "+faststart", str(video)],
                   check=True)
    actual = float(subprocess.check_output([
        "ffprobe", "-v", "error", "-show_entries", "format=duration",
        "-of", "default=nw=1:nk=1", str(video)], text=True))
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Vídeo ou sequência animada aceita pelo FFmpeg.")
    parser.add_argument("output", type=Path, help="Novo arquivo .stillmotion.zip.")
    parser.add_argument("--title", required=True)
    parser.add_argument("--trigger", choices=("wake", "unlock"), default="unlock")
    parser.add_argument("--start", type=float, default=0)
    parser.add_argument("--duration", type=float, default=2, help="Duração em segundos, de 0,25 a 14.")
    parser.add_argument("--after", type=Path, help="Segundo vídeo, reproduzido após o desbloqueio.")
    parser.add_argument("--after-duration", type=float, default=5)
    parser.add_argument("--fps", type=int, default=30, choices=(15, 24, 30))
    parser.add_argument("--size", choices=(360, 720, 1080), type=int, default=720)
    args = parser.parse_args()
    title = args.title.strip()
    if (not title or len(title.encode("utf-16-le")) > 120
            or any(unicodedata.category(c) in {"Cc", "Cf", "Zl", "Zp"} for c in title)):
        parser.error("Use um nome de até 60 caracteres sem caracteres de controle.")
    if not math.isfinite(args.start) or args.start < 0 or not .25 <= args.duration <= 14:
        parser.error("Recorte inválido.")
    if args.output.exists() or not args.source.is_file():
        parser.error("Use uma entrada existente e um destino novo.")
    if args.after and (args.trigger != "unlock" or not args.after.is_file()
                       or not .25 <= args.after_duration <= 14):
        parser.error("O segundo vídeo exige --trigger unlock e uma duração válida.")
    width = args.size
    height = width * 13 // 6
    filters = (f"fps={args.fps},scale={width}:{height}:force_original_aspect_ratio=increase"
               f":flags=lanczos,crop={width}:{height},setsar=1")
    with tempfile.TemporaryDirectory() as work:
        video = Path(work) / "animation.mp4"
        actual = encode(args.source, video, args.start, args.duration, filters)
        after = Path(work) / "after.mp4"
        if args.after:
            tail = encode(args.after, after, 0, args.after_duration, filters)
            if tail < .25 or actual + tail > 14.1 or video.stat().st_size + after.stat().st_size > 32 * 1024 * 1024:
                parser.error("Os clipes excedem os limites combinados de duração ou tamanho.")
        if not .25 <= actual <= 14.1 or video.stat().st_size > 32 * 1024 * 1024:
            parser.error("A animação compilada saiu dos limites de duração ou tamanho.")
        with ZipFile(args.output, "x", ZIP_STORED) as archive:
            version = 2 if args.after else 1
            archive.writestr("theme.txt", f"stillmotion-v{version}\n{args.trigger}\n{title}\n")
            archive.write(video, "animation.mp4")
            if args.after:
                archive.write(after, "after.mp4")
    print(args.output)


if __name__ == "__main__":
    main()
