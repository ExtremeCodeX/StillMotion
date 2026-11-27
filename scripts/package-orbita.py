#!/usr/bin/env python3
"""Package the six StillMotion designs without embedding their art in the APK."""
import argparse
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / "temas" / "fontes" / "orbita"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    output = parser.parse_args().output
    entries = {"orbita.tsv": ART / "catalog.tsv"}
    for row in entries["orbita.tsv"].read_text(encoding="utf-8").splitlines()[1:]:
        theme = row.split("\t", 1)[0]
        for stage, source in (
            ("wake", "ligar_start"), ("lock", "ligar"), ("home", "desbloquear")
        ):
            entries[f"{theme}-{stage}.webp"] = ART / f"{theme}_{source}.webp"
    if any(not source.is_file() for source in entries.values()):
        parser.error("One or more source posters are missing.")
    with ZipFile(output, "x", ZIP_STORED) as archive:
        for name, source in entries.items():
            archive.writestr(ZipInfo(name), source.read_bytes())
    print(output)


if __name__ == "__main__":
    main()
