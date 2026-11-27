#!/usr/bin/env python3
"""Apply one Flyme service using the Android picker on a disposable emulator."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from prepare_flyme_scene_tools import read_catalog

PACKAGE = "com.flyme.alivewallpaper"
PICKER_ACTION = "android.service.wallpaper.CHANGE_LIVE_WALLPAPER"
COMPONENT_EXTRA = "android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT"
UI_DUMP = "/data/local/tmp/stillmotion-picker.xml"


def main():
    catalog = read_catalog()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("theme", choices=list(catalog))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use somente um emulador descartável.")

    def adb(*command, timeout=40):
        result = subprocess.run(["adb", "-s", args.serial, *command],
                                capture_output=True, text=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result.stdout

    def tap(labels, id_suffixes=()):
        adb("shell", "uiautomator", "dump", "--compressed", UI_DUMP)
        tree = ET.fromstring(adb("shell", "cat", UI_DUMP))
        for node in tree.iter("node"):
            values = (node.get("text", "").strip().casefold(), node.get("content-desc", "").strip().casefold())
            resource = node.get("resource-id", "").rsplit("/", 1)[-1]
            if resource not in id_suffixes and not any(value in labels for value in values):
                continue
            bounds = [int(value) for value in re.findall(r"\d+", node.get("bounds", ""))]
            if len(bounds) == 4:
                adb("shell", "input", "tap", str((bounds[0] + bounds[2]) // 2),
                    str((bounds[1] + bounds[3]) // 2))
                return True
        return False

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("ADB não reconheceu um dispositivo emulado.")
    if adb("shell", "settings", "get", "global", "airplane_mode_on").strip() != "1":
        parser.error("Ative o modo avião na imagem descartável.")
    service = PACKAGE + "/" + catalog[args.theme][2]

    def active():
        lines = adb("shell", "dumpsys", "wallpaper").splitlines()
        return any(service in line for line in lines
                   if "mWallpaperComponent" in line or "wallpaperComponent=" in line)

    if active():
        print(f"{args.theme} já selecionado.")
        return
    result = adb("shell", "am", "start", "-W", "-a", PICKER_ACTION, "--ecn", COMPONENT_EXTRA, service)
    if "Error:" in result or "Status: ok" not in result:
        raise RuntimeError(result)
    if not tap({"set wallpaper", "apply", "definir papel de parede", "aplicar"},
               {"set_wallpaper", "apply_button"}):
        raise RuntimeError("O seletor não mostrou uma opção reconhecível para aplicar.")
    time.sleep(1)
    if not active():
        tap({"home screen", "tela inicial", "home screen and lock screen", "tela inicial e de bloqueio"})
    for _ in range(4):
        if active():
            print(f"{args.theme} selecionado no emulador.")
            return
        time.sleep(1)
    raise RuntimeError(f"O sistema não confirmou o serviço ativo {service}.")


if __name__ == "__main__":
    main()
