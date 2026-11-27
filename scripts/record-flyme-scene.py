#!/usr/bin/env python3
"""Capture one already-applied Flyme theme in an offline, disposable emulator."""
import argparse
from pathlib import Path
import re
import subprocess
import time

from prepare_flyme_scene_tools import read_catalog

FLYME = "com.flyme.alivewallpaper"
HOST = "dev.stillmotion.capturehost/.CaptureActivity"


def main():
    catalog = read_catalog()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("theme", choices=list(catalog))
    parser.add_argument("output", type=Path)
    options = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", options.serial):
        parser.error("Use somente um emulador descartável.")
    if options.output.exists():
        parser.error("Use um destino novo.")

    def adb(*args, timeout=40):
        return subprocess.run(["adb", "-s", options.serial, *args], check=True,
                              capture_output=True, text=True, timeout=timeout).stdout

    if (adb("shell", "getprop", "ro.kernel.qemu").strip() != "1"
            or adb("shell", "getprop", "sys.boot_completed").strip() != "1"):
        parser.error("O Android emulado precisa estar pronto.")
    if adb("shell", "settings", "get", "global", "airplane_mode_on").strip() != "1":
        parser.error("Ative o modo avião no emulador e mantenha Wi-Fi e dados desligados.")
    service = catalog[options.theme][2]
    wallpaper = adb("shell", "dumpsys", "wallpaper")
    active = [line for line in wallpaper.splitlines()
              if "mWallpaperComponent" in line or "wallpaperComponent=" in line]
    if not any(FLYME + "/" + service in line for line in active):
        parser.error(f"Selecione primeiro o wallpaper original {service} no emulador.")
    result = adb("shell", "am", "start", "-W", "-n", HOST)
    if "Status: ok" not in result:
        raise RuntimeError(result)
    remote = "/data/local/tmp/stillmotion-" + options.theme + ".mp4"
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
    with subprocess.Popen(["adb", "-s", options.serial, "shell", "screenrecord",
                           "--time-limit", "12", "--bit-rate", "5000000", remote]) as recording:
        try:
            time.sleep(1)
            adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb("shell", "wm", "dismiss-keyguard")
            if recording.wait(timeout=35):
                raise RuntimeError("O emulador não concluiu screenrecord")
        finally:
            if recording.poll() is None:
                recording.terminate()
    options.output.parent.mkdir(parents=True, exist_ok=True)
    adb("pull", remote, str(options.output))
    adb("shell", "rm", remote)
    print(f"Captura bruta: {options.output}. Confira o recorte e a fluidez antes de gerar o pacote.")


if __name__ == "__main__":
    main()
