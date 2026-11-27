"""Shared helpers for offline GLES reconstruction of the reference APK.

The reference application renders its programmatic wallpapers with OpenGL ES
shaders.  This module reproduces the small subset of Android helpers that the
renderers use (shader include expansion, path interpolators, matrix helpers and
OBJ parsing) so the original shaders can run unmodified on a desktop GPU.
"""
from __future__ import annotations

import math
import re
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
ASSET_ROOT = ROOT / "analysis" / "apk-assets" / "assets"

INCLUDE = re.compile(r"#include\s+(?:<([^>]+)>|\"([^\"]+)\")")


def resolve_includes(source: str, root: Path = ASSET_ROOT, seen: set[str] | None = None) -> str:
    """Expand the ``#include <path>`` directives used by the APK shaders."""
    seen = set() if seen is None else seen

    def replace(match: re.Match[str]) -> str:
        name = match.group(1) or match.group(2)
        if name in seen:
            return ""
        seen.add(name)
        return resolve_includes((root / name).read_text(encoding="utf-8"), root, seen)

    return INCLUDE.sub(replace, source)


def load_shader(name: str, root: Path = ASSET_ROOT) -> str:
    return resolve_includes((root / name).read_text(encoding="utf-8"), root)


def parse_color(value: str) -> tuple[float, float, float, float]:
    value = value.lstrip("#")
    if len(value) == 6:
        value += "ff"
    if len(value) != 8:
        raise ValueError(f"unsupported color {value!r}")
    return tuple(int(value[index:index + 2], 16) / 255.0 for index in (0, 2, 4, 6))


def parse_color_int(value: str) -> int:
    """Android Color.parseColor for the #RRGGBB / #AARRGGBB forms in the JSON."""
    value = value.lstrip("#")
    if len(value) == 6:
        value = "ff" + value
    return int(value, 16)


def lerp_color(first: int, second: int, fraction: float) -> int:
    """g2.q.z: per-channel interpolation with integer truncation."""
    inverse = 1.0 - fraction
    channels = []
    for shift in (24, 16, 8, 0):
        a = (first >> shift) & 0xFF
        b = (second >> shift) & 0xFF
        channels.append(int(a * inverse + b * fraction))
    return (channels[0] << 24) | (channels[1] << 16) | (channels[2] << 8) | channels[3]


def color_to_vec(value: int) -> tuple[float, float, float, float]:
    return tuple(((value >> shift) & 0xFF) / 255.0 for shift in (16, 8, 0, 24))


def bezier_interpolator(x1: float, y1: float, x2: float, y2: float):
    """Android PathInterpolator: cubic bezier with fixed endpoints (0,0)-(1,1)."""

    def solve(fraction: float) -> float:
        if fraction <= 0.0:
            return 0.0
        if fraction >= 1.0:
            return 1.0
        low, high = 0.0, 1.0
        for _ in range(24):
            mid = (low + high) * 0.5
            x = 3.0 * (1.0 - mid) ** 2 * mid * x1 + 3.0 * (1.0 - mid) * mid ** 2 * x2 + mid ** 3
            if x < fraction:
                low = mid
            else:
                high = mid
        t = (low + high) * 0.5
        return 3.0 * (1.0 - t) ** 2 * t * y1 + 3.0 * (1.0 - t) * t ** 2 * y2 + t ** 3

    return solve


def accelerate_decelerate(fraction: float) -> float:
    return (1.0 - math.cos(math.pi * max(0.0, min(1.0, fraction)))) * 0.5


def animated(start: float, end: float, seconds: float, duration: float, easing) -> float:
    if duration <= 0.0:
        return end
    fraction = max(0.0, min(1.0, seconds / duration))
    return start + (end - start) * easing(fraction)


def ortho(left: float, right: float, bottom: float, top: float, near: float, far: float) -> np.ndarray:
    return np.array(
        [
            [2.0 / (right - left), 0.0, 0.0, -(right + left) / (right - left)],
            [0.0, 2.0 / (top - bottom), 0.0, -(top + bottom) / (top - bottom)],
            [0.0, 0.0, -2.0 / (far - near), -(far + near) / (far - near)],
            [0.0, 0.0, 0.0, 1.0],
        ],
        dtype=np.float32,
    )


def look_at(eye, target, up) -> np.ndarray:
    eye = np.asarray(eye, dtype=np.float32)
    target = np.asarray(target, dtype=np.float32)
    up = np.asarray(up, dtype=np.float32)
    forward = target - eye
    forward /= np.linalg.norm(forward)
    right = np.cross(forward, up)
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    matrix = np.identity(4, dtype=np.float32)
    matrix[0, :3] = right
    matrix[1, :3] = up
    matrix[2, :3] = -forward
    matrix[0, 3] = -np.dot(right, eye)
    matrix[1, 3] = -np.dot(up, eye)
    matrix[2, 3] = np.dot(forward, eye)
    return matrix


def rotation_y(degrees: float) -> np.ndarray:
    radians = math.radians(degrees)
    c, s = math.cos(radians), math.sin(radians)
    return np.array(
        [[c, 0.0, s, 0.0], [0.0, 1.0, 0.0, 0.0], [-s, 0.0, c, 0.0], [0.0, 0.0, 0.0, 1.0]],
        dtype=np.float32,
    )


def column_major(matrix: np.ndarray) -> bytes:
    return np.ascontiguousarray(matrix.T, dtype=np.float32).tobytes()


def parse_obj(path: Path) -> np.ndarray:
    """Triangulate an OBJ into interleaved position/normal/texcoord vertices."""
    positions: list[tuple[float, float, float]] = []
    normals: list[tuple[float, float, float]] = []
    texcoords: list[tuple[float, float]] = []
    triangles: list[list[tuple[int, int, int]]] = []

    def index(token: str | None, count: int) -> int:
        if not token:
            return 0
        value = int(token)
        return value - 1 if value > 0 else count + value

    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "v":
            positions.append(tuple(float(v) for v in parts[1:4]))
        elif parts[0] == "vn":
            normals.append(tuple(float(v) for v in parts[1:4]))
        elif parts[0] == "vt":
            texcoords.append(tuple(float(v) for v in parts[1:3]))
        elif parts[0] == "f":
            corners = []
            for token in parts[1:]:
                fields = token.split("/")
                corners.append(
                    (
                        index(fields[0], len(positions)),
                        index(fields[1], len(texcoords)) if len(fields) > 1 and fields[1] else -1,
                        index(fields[2], len(normals)) if len(fields) > 2 and fields[2] else -1,
                    )
                )
            for i in range(1, len(corners) - 1):
                triangles.append([corners[0], corners[i], corners[i + 1]])

    data = np.empty((len(triangles) * 3, 8), dtype=np.float32)
    for row, triangle in enumerate(triangles):
        for column, (pi, ti, ni) in enumerate(triangle):
            target = row * 3 + column
            data[target, 0:3] = positions[pi]
            data[target, 3:6] = normals[ni] if ni >= 0 else (0.0, 0.0, 1.0)
            data[target, 6:8] = texcoords[ti] if ti >= 0 else (0.0, 0.0)
    return data


def read_json(path: Path) -> dict:
    import json

    return json.loads(path.read_text(encoding="utf-8"))
