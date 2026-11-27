#!/usr/bin/env python3
"""Run StillMotion selection checks on a disposable, already booted AOSP emulator."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "dev.stillmotion.wallpaper"
SETTINGS = PACKAGE + "/.SettingsActivity"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("output", type=Path)
    parser.add_argument("--variant", choices=["debug", "probe"], default="debug")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use um emulador descartável com serial emulator-…")

    def adb(*command, raw=False, timeout=60):
        return subprocess.run(
            ["adb", "-s", args.serial, *command],
            capture_output=True, text=not raw, check=True, timeout=timeout,
        ).stdout

    def click(labels):
        remote = "/data/local/tmp/stillmotion-ui.xml"
        adb("shell", "uiautomator", "dump", remote)
        tree = ET.fromstring(adb("shell", "cat", remote))
        for node in tree.iter("node"):
            labels_seen = (node.get("text", "").casefold(), node.get("content-desc", "").casefold())
            if not any(label.casefold() in labels_seen for label in labels):
                continue
            points = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
            if len(points) == 4:
                adb("shell", "input", "tap", str((points[0] + points[2]) // 2),
                    str((points[1] + points[3]) // 2))
                return
        raise RuntimeError(f"UI did not show any of {labels}")

    def wait_log(pattern, timeout=40):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            logs = adb("logcat", "-d", "-v", "monotonic", "-s", "StillMotion:D")
            if re.search(pattern, logs):
                return logs
            time.sleep(1)
        raise RuntimeError(f"Missing wallpaper event: {pattern}")

    if adb("shell", "getprop", "sys.boot_completed").strip() != "1":
        raise RuntimeError("O emulador ainda não concluiu o boot.")
    args.output.mkdir(parents=True, exist_ok=True)
    apk = ROOT / "build" / args.variant / f"StillMotion-{args.variant}.apk"
    summary = {"source": "emulator-functional", "variant": args.variant}
    try:
        summary["api"] = adb("shell", "getprop", "ro.build.version.sdk").strip()
        packages = adb("shell", "pm", "list", "packages")
        summary["google_wallpapers_present"] = "package:com.google.android.apps.wallpaper\n" in packages
        adb("install", "--no-incremental", "-r", str(apk))
        summary["installed"] = True
        adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        adb("shell", "wm", "dismiss-keyguard")
        started = adb("shell", "am", "start", "-W", "-n", SETTINGS)
        if "Status: ok" not in started:
            raise RuntimeError(started)
        adb("logcat", "-c")
        summary["activity_opened"] = True
        click(["Selecionar papel de parede"])
        wait_log(r"attached preview=true")
        wait_log(r"\bposter\s")
        summary["preview_poster"] = True
        (args.output / f"{args.variant}-preview.png").write_bytes(adb("exec-out", "screencap", "-p", raw=True))
        click(["Set wallpaper", "Definir papel de parede", "Apply"])
        time.sleep(2)
        logs = adb("logcat", "-d", "-v", "monotonic", "-s", "StillMotion:D")
        if "attached preview=false" not in logs:
            click(["Home screen", "Tela inicial", "Home screen and lock screen"])
        wait_log(r"attached preview=false")
        summary["applied_public_picker"] = True
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(2)
        (args.output / f"{args.variant}-home.png").write_bytes(adb("exec-out", "screencap", "-p", raw=True))
    finally:
        (args.output / f"{args.variant}-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        (args.output / f"{args.variant}-callbacks.txt").write_text(
            adb("logcat", "-d", "-v", "monotonic", "-s", "StillMotion:D"))


if __name__ == "__main__":
    main()
