#!/usr/bin/env python3
"""Generate the two StillMotion themes from original, deterministic artwork."""
import hashlib
import json
import math
from pathlib import Path
import random
import struct

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "assets" / "themes"
WIDTH, HEIGHT = 864, 1920


def gradient(top, bottom):
    image = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        t = y / (HEIGHT - 1)
        rgb = tuple(round(a + t * (b - a)) for a, b in zip(top, bottom))
        draw.line((0, y, WIDTH, y), fill=rgb)
    return image


def stars(draw, seed, count, bottom):
    randomizer = random.Random(seed)
    for _ in range(count):
        x = randomizer.randrange(WIDTH)
        y = randomizer.randrange(bottom)
        intensity = randomizer.randint(45, 159)
        radius = randomizer.choice([0, 0, 0, 1])
        draw.ellipse((x, y, x + radius, y + radius),
                     fill=(intensity, min(255, intensity + 13), min(255, intensity + 20)))


def dunes():
    image = gradient((22, 30, 56), (168, 100, 91))
    draw = ImageDraw.Draw(image)
    stars(draw, 21, 100, 800)
    for radius in range(124, 0, -1):
        scale = radius / 124
        color = (244, round(190 - 34 * scale), round(133 - 15 * scale))
        draw.ellipse((572 - radius, 834 - radius, 572 + radius, 834 + radius), fill=color)
    for layer, color in enumerate([(145, 82, 82), (111, 66, 83), (83, 56, 73),
                                   (58, 45, 61), (40, 37, 55), (25, 30, 48)]):
        points = []
        for x in range(0, WIDTH + 1, 3):
            t = x / WIDTH
            y = 1040 + 127 * layer + 118 * math.sin(t * 3.6 + layer * 1.11)
            y += (26 + layer * 6) * math.sin(t * 7.3 - layer * 0.41)
            points.append((x, round(y)))
        draw.polygon(points + [(WIDTH, HEIGHT), (0, HEIGHT)], fill=color)
    return image


def orbit():
    image = gradient((11, 19, 38), (27, 54, 76))
    draw = ImageDraw.Draw(image)
    stars(draw, 918, 320, HEIGHT)
    planet = Image.new("RGBA", (WIDTH, HEIGHT))
    pixels = planet.load()
    cx, cy, radius = 447, 959, 280
    for y in range(cy - radius - 4, cy + radius + 5):
        for x in range(cx - radius - 4, cx + radius + 5):
            nx, ny = (x - cx) / radius, (y - cy) / radius
            rr = nx * nx + ny * ny
            alpha = min(1.0, max(0.0, (1.006 - rr) * 135))
            if not alpha:
                continue
            nz = math.sqrt(max(0, 1 - rr))
            light = max(0, -0.49 * nx - 0.44 * ny + 0.70 * nz)
            band = math.sin((ny + 0.36 * nx + 0.25 * nz) * 14 + math.sin(nx * 5) * 0.9)
            rim = (1 - nz) ** 7 * max(0.0, light + 0.15)
            pixels[x, y] = (round(16 + 49 * light + 18 * rim),
                            round(28 + (91 + 13 * band) * light + 46 * rim),
                            round(54 + (122 + 17 * band) * light + 57 * rim),
                            round(alpha * 255))
    image.paste(planet, mask=planet.getchannel("A"))
    return image


def write_theme(name, poster, duration, fps, zoom):
    directory = OUTPUT / name
    directory.mkdir(parents=True, exist_ok=True)
    image_file = directory / "poster.webp"
    descriptor = directory / "motion.bin"
    poster.save(image_file, format="WEBP", quality=92, method=6, exact=True)
    descriptor.write_bytes(struct.pack(">cBBB HBB", b"S", 1, 1, fps, duration, zoom, 0))
    metadata = {
        "version": 1,
        "backend": "canvas-zoom",
        "source": "Arte procedural original gerada por scripts/generate-themes.py",
        "size_px": list(poster.size),
        "duration_ms": duration,
        "fps_cap": fps,
        "initial_zoom_percent": 100 + zoom,
        "initial_image": "poster.webp",
        "final_image": "poster.webp",
        "sha256": {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                   for file in (image_file, descriptor)},
    }
    (directory / "theme.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")


def main():
    landscape = dunes()
    write_theme("dunes", landscape, 1200, 24, 15)
    write_theme("orbit", orbit(), 1400, 24, 16)
    landscape.resize((216, 480), Image.Resampling.LANCZOS).save(
        OUTPUT / "wallpaper_thumbnail.webp", quality=86)
    icon = Image.new("RGBA", (108, 108))
    draw = ImageDraw.Draw(icon)
    draw.ellipse((7, 7, 101, 101), fill="#192840")
    draw.ellipse((24, 26, 84, 86), fill="#91d1d6")
    draw.ellipse((37, 21, 89, 73), fill="#192840")
    icon.save(OUTPUT / "stillmotion_icon.png")


if __name__ == "__main__":
    main()
