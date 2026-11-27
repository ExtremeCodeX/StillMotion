#!/usr/bin/env python3
"""Render a Cosmic wallpaper variant from the reference APK as a video + poster.

The APK renders these themes with the shaders under assets/shader/cosmic and the
keyguard -> unlock transition described in t1.b/t1.c.  This tool replays that
transition on a desktop GLES context, then keeps the unlock scene running for a
few seconds so the finite clip still ends on a representative frame.
"""
from __future__ import annotations

import argparse
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from render_common import (  # noqa: E402
    ASSET_ROOT,
    accelerate_decelerate,
    animated,
    bezier_interpolator,
    color_to_vec,
    column_major,
    lerp_color,
    load_shader,
    look_at,
    ortho,
    parse_color_int,
    parse_obj,
    read_json,
    rotation_y,
)

# t1.b defaults and t1.d JSON defaults.
DEFAULT_SCENES = {
    "aod": dict(pos=(0.0, 0.325), scale=0.22, rotate=1.0, noise=1.0, start=(0.0, 0.0), end=(0.5, 1.0)),
    "keyguard": dict(pos=(0.764, -0.206), scale=1.181, rotate=0.8, noise=0.8, start=(0.0, 0.0), end=(0.5, 1.0)),
    "unlock": dict(pos=(0.0, 0.0), scale=1.55, rotate=0.6, noise=1.0, start=(0.0, 0.0), end=(0.5, 1.0)),
}
ROTATE_KEYS = ("rotate_0", "rotate_90", "rotate_180", "rotate_270")

# t1.b.K (keyguard -> unlock) timing for the cosmic family.
COLOR_EASING = bezier_interpolator(0.15, 0.46, 0.42, 1.0)
MOVE_EASING = bezier_interpolator(0.15, 0.06, 0.21, 1.0)
SCALE_EASING = bezier_interpolator(0.48, 0.05, 0.21, 1.0)
OFFSET_EASING = bezier_interpolator(0.33, 0.0, 0.67, 1.0)
COLOR_MS = 1617.0
MOVE_MS = 1200.0
SCALE_MS = 1200.0
OFFSET_MS = 700.0


def scene_from_json(data: dict, name: str) -> dict:
    scene = dict(DEFAULT_SCENES[name])
    source = data[name]
    scene["pos"] = tuple(scene["pos"])
    scene["colors"] = {
        "base": parse_color_int(source["baseColor"]),
        "stripe1": parse_color_int(source["stripeColor1"]),
        "stripe2": parse_color_int(source["stripeColor2"]),
        "stripe3": parse_color_int(source["stripeColor3"]),
        "divergent": float(source["stripeDivergent"]),
        "thickness": float(source["stripeThickness"]),
        "highlight_thickness": float(source["highlightThickness"]),
        "highlight_strength": float(source["highlightStrength"]),
        "highlight": parse_color_int(source["highlightColor"]),
        "alpha": float(source["alpha"]),
        "rotate_start": [parse_color_int(source[key]["startColor"]) for key in ROTATE_KEYS],
        "rotate_end": [parse_color_int(source[key]["endColor"]) for key in ROTATE_KEYS],
    }
    return scene


def mix_scene(first: dict, second: dict, fraction: float) -> dict:
    result = {
        "pos": (
            first["pos"][0] + (second["pos"][0] - first["pos"][0]) * fraction,
            first["pos"][1] + (second["pos"][1] - first["pos"][1]) * fraction,
        ),
        "scale": first["scale"] + (second["scale"] - first["scale"]) * fraction,
        "rotate": first["rotate"] + (second["rotate"] - first["rotate"]) * fraction,
        "noise": first["noise"] + (second["noise"] - first["noise"]) * fraction,
        "start": first["start"],
        "end": first["end"],
    }
    colors = {}
    for key in ("divergent", "thickness", "highlight_thickness", "highlight_strength", "alpha"):
        colors[key] = first["colors"][key] + (second["colors"][key] - first["colors"][key]) * fraction
    for key in ("base", "stripe1", "stripe2", "stripe3", "highlight"):
        colors[key] = lerp_color(first["colors"][key], second["colors"][key], fraction)
    colors["rotate_start"] = [
        lerp_color(a, b, fraction) for a, b in zip(first["colors"]["rotate_start"], second["colors"]["rotate_start"])
    ]
    colors["rotate_end"] = [
        lerp_color(a, b, fraction) for a, b in zip(first["colors"]["rotate_end"], second["colors"]["rotate_end"])
    ]
    result["colors"] = colors
    return result


def rotation_gradient(colors: dict, angle: float, end: bool) -> int:
    values = colors["rotate_end" if end else "rotate_start"]
    angle %= 360.0
    if angle < 90.0:
        return lerp_color(values[0], values[1], angle / 90.0)
    if angle < 180.0:
        return lerp_color(values[1], values[2], (angle - 90.0) / 90.0)
    if angle < 270.0:
        return lerp_color(values[2], values[3], (angle - 180.0) / 90.0)
    return lerp_color(values[3], values[0], (angle - 270.0) / 90.0)


def triangle_wave(seconds: float) -> float:
    value = seconds % 7200.0
    return value if value < 3600.0 else 7200.0 - value


def make_context(width: int, height: int):
    import moderngl

    ctx = moderngl.create_standalone_context(backend="egl")
    color = ctx.texture((width, height), 4)
    depth = ctx.depth_renderbuffer((width, height))
    fbo = ctx.framebuffer(color_attachments=[color], depth_attachment=depth)
    fbo.use()
    return ctx, fbo


def build_geometry(ctx, width: int, height: int):
    import moderngl

    sphere = parse_obj(ASSET_ROOT / "model" / "sphere.obj")
    positions = np.ascontiguousarray(sphere[:, 0:3])
    sphere_buffer = ctx.buffer(positions.tobytes())
    sphere_vao = ctx.vertex_array(
        ctx.program(
            vertex_shader=load_shader("shader/cosmic/cosmic_vertex.glsl"),
            fragment_shader=load_shader("shader/cosmic/cosmic_frag.glsl"),
        ),
        [(sphere_buffer, "3f", "a_position")],
    )
    # y1.b.c: position (3), normal (3), uv (2), two triangles.  q.e() builds the
    # background camera at (0, 0, 1) looking at the origin, so the quad is in the
    # XY plane and uses a separate projection from the sphere.
    left, right = -width / 2.0, width / 2.0
    bottom, top = -height / 2.0, height / 2.0
    quad = np.array(
        [
            [left, top, 0.0, 0.0, 1.0],
            [right, top, 0.0, 1.0, 1.0],
            [right, bottom, 0.0, 1.0, 0.0],
            [left, top, 0.0, 0.0, 1.0],
            [right, bottom, 0.0, 1.0, 0.0],
            [left, bottom, 0.0, 0.0, 0.0],
        ],
        dtype=np.float32,
    )
    quad_buffer = ctx.buffer(quad.tobytes())
    background = ctx.vertex_array(
        ctx.program(
            vertex_shader=load_shader("shader/cosmic/gradient_background_vertex.glsl"),
            fragment_shader=load_shader("shader/cosmic/gradient_background_frag.glsl"),
        ),
        [(quad_buffer, "3f 2f", "a_position", "a_texCoord0")],
    )
    return sphere_vao, background


def set_uniform(program, name: str, value) -> None:
    if name in program:
        program[name].value = value


def render_frame(ctx, fbo, sphere_vao, background, scene: dict, seconds: float,
                 rotate_degrees: float, width: int, height: int) -> bytes:
    ctx.clear(0.0, 0.0, 0.0, 1.0, depth=1.0)
    ctx.disable(0x0B71)  # GL_DEPTH_TEST
    background_pv = (
        ortho(-width / 2.0, width / 2.0, -height / 2.0, height / 2.0, 0.0, 2.0)
        @ look_at((0.0, 0.0, 1.0), (0.0, 0.0, 0.0), (0.0, 1.0, 0.0))
    )
    background.program["u_projectionViewMatrix"].write(column_major(background_pv))
    set_uniform(background.program, "u_start_pos", scene["start"])
    set_uniform(background.program, "u_end_pos", scene["end"])
    background.program["u_start_color"].value = color_to_vec(
        rotation_gradient(scene["colors"], rotate_degrees, False))
    background.program["u_end_color"].value = color_to_vec(
        rotation_gradient(scene["colors"], rotate_degrees, True))
    background.render()

    ctx.enable(0x0B71)  # GL_DEPTH_TEST
    ctx.enable(0x0B44)  # GL_CULL_FACE
    ctx.enable(0x0BE2)  # GL_BLEND
    ctx.blend_func = (0x0302, 0x0303)  # GL_ONE, GL_ONE_MINUS_SRC_ALPHA
    ctx.enable(0x809D)  # GL_MULTISAMPLE (no-op without samples)

    program = sphere_vao.program
    model = rotation_y(rotate_degrees)
    eye = model @ np.array([1000.0, 0.0, 0.0, 1.0], dtype=np.float32)
    projection = ortho(-width / 2.0, width / 2.0, -height / 2.0, height / 2.0, 0.0, height * 8.0)
    view = look_at((height * 4.0, 0.0, 0.0), (0.0, 0.0, 0.0), (0.0, 1.0, 0.0))
    colors = scene["colors"]
    program["u_projectionViewMatrix"].write(column_major(projection @ view))
    program["u_modelMatrix"].write(column_major(model))
    set_uniform(program, "u_resolution", (float(width), float(height)))
    set_uniform(program, "u_time", triangle_wave(seconds))
    program["u_translation"].value = (
        scene["pos"][0] * width,
        scene["pos"][1] * height,
        0.0,
    )
    set_uniform(program, "u_scale", scene["scale"] * width * 0.85)
    program["u_eye_position"].value = (float(eye[0]), float(eye[1]), float(eye[2]))
    program["u_base_color"].value = color_to_vec(colors["base"])
    set_uniform(program, "u_noise_amplitude", scene["noise"])
    program["u_stripe_color_1"].value = color_to_vec(colors["stripe1"])
    program["u_stripe_color_2"].value = color_to_vec(colors["stripe2"])
    program["u_stripe_color_3"].value = color_to_vec(colors["stripe3"])
    set_uniform(program, "u_stripe_divergent", colors["divergent"])
    set_uniform(program, "u_stripe_thickness", colors["thickness"])
    set_uniform(program, "u_highlight_thickness", colors["highlight_thickness"])
    set_uniform(program, "u_highlight_strength", colors["highlight_strength"])
    program["u_highlight_color"].value = color_to_vec(colors["highlight"])
    set_uniform(program, "u_alpha", colors["alpha"])
    sphere_vao.render()

    ctx.disable(0x0BE2)
    ctx.disable(0x0B44)
    ctx.disable(0x0B71)
    ctx.finish()
    data = fbo.read(components=3, alignment=1)
    return np.frombuffer(data, dtype=np.uint8).reshape(height, width, 3)[::-1].tobytes()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("theme", help="variant name, e.g. cosmic_v1")
    parser.add_argument("--json", type=Path, help="scene JSON (defaults to the extracted APK asset)")
    parser.add_argument("--output", type=Path, required=True, help="output .mp4")
    parser.add_argument("--poster", type=Path, help="output .webp poster")
    parser.add_argument("--width", type=int, default=720)
    parser.add_argument("--height", type=int, default=1560)
    parser.add_argument("--fps", type=int, default=30)
    parser.add_argument("--duration", type=float, default=8.0)
    parser.add_argument("--crf", type=int, default=16)
    parser.add_argument("--preview", type=Path, help="write a contact-sheet PNG")
    args = parser.parse_args()

    source = args.json or (ASSET_ROOT / "shader" / "cosmic" / f"{args.theme}.json")
    data = read_json(source)
    keyguard = scene_from_json(data, "keyguard")
    unlock = scene_from_json(data, "unlock")

    ctx, fbo = make_context(args.width, args.height)
    sphere_vao, background = build_geometry(ctx, args.width, args.height)
    ctx.enable(0x0B71)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{args.width}x{args.height}",
        "-r", str(args.fps), "-i", "-", "-an", "-map_metadata", "-1", "-map_chapters", "-1",
        "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "fast", "-crf", str(args.crf),
        "-threads", "4", "-movflags", "+faststart", str(args.output),
    ]
    encoder = subprocess.Popen(command, stdin=subprocess.PIPE)
    frames = int(round(args.duration * args.fps))
    samples = []
    rotate = 0.0
    previous = 0.0
    try:
        for index in range(frames):
            seconds = index / args.fps
            fraction = min(1.0, seconds / (COLOR_MS / 1000.0))
            colors = mix_scene(keyguard, unlock, COLOR_EASING(fraction))
            move = min(1.0, seconds / (MOVE_MS / 1000.0))
            move = MOVE_EASING(move)
            scale = min(1.0, seconds / (SCALE_MS / 1000.0))
            scale = SCALE_EASING(scale)
            offset = min(1.0, seconds / (OFFSET_MS / 1000.0))
            offset = OFFSET_EASING(offset)
            scene = {
                "pos": (
                    keyguard["pos"][0] + (unlock["pos"][0] - keyguard["pos"][0]) * move,
                    keyguard["pos"][1] + (unlock["pos"][1] - keyguard["pos"][1]) * move,
                ),
                "scale": keyguard["scale"] + (unlock["scale"] - keyguard["scale"]) * scale,
                "rotate": keyguard["rotate"] + (unlock["rotate"] - keyguard["rotate"])
                * accelerate_decelerate(min(1.0, seconds / 1.0)),
                "noise": keyguard["noise"] + (unlock["noise"] - keyguard["noise"])
                * accelerate_decelerate(min(1.0, seconds / 1.0)),
                "start": keyguard["start"],
                "end": unlock["end"],
                "colors": colors["colors"],
            }
            rotate += (seconds - previous) * 30.0 * scene["rotate"]
            previous = seconds
            frame = render_frame(ctx, fbo, sphere_vao, background, scene, seconds, rotate,
                                 args.width, args.height)
            encoder.stdin.write(frame)
            if index in (0, frames // 4, frames // 2, (frames * 3) // 4, frames - 1):
                samples.append(np.frombuffer(frame, dtype=np.uint8).reshape(args.height, args.width, 3))
    finally:
        encoder.stdin.close()
        status = encoder.wait()
        if status != 0:
            raise SystemExit(f"ffmpeg exited with {status}")

    if args.poster:
        from PIL import Image

        args.poster.parent.mkdir(parents=True, exist_ok=True)
        Image.fromarray(samples[-1]).save(args.poster, "WEBP", lossless=True, quality=100, method=6)
    if args.preview and samples:
        from PIL import Image

        thumbs = [Image.fromarray(frame).resize((args.width // 4, args.height // 4)) for frame in samples]
        sheet = Image.new("RGB", (thumbs[0].width * len(thumbs), thumbs[0].height))
        for index, thumb in enumerate(thumbs):
            sheet.paste(thumb, (index * thumb.width, 0))
        args.preview.parent.mkdir(parents=True, exist_ok=True)
        sheet.save(args.preview)
    print(f"{args.theme}: {frames} frames -> {args.output}")


if __name__ == "__main__":
    main()
