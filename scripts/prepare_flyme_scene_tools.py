"""Local manifest shared by the capture and pack tools."""
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[1] / "assets" / "flyme-scenes.tsv"


def read_catalog():
    catalog = {}
    for row in CATALOG.read_text().splitlines():
        if row and not row.startswith("#"):
            key, family, label, service = row.split("\t")
            if key in catalog:
                raise ValueError(f"Tema duplicado: {key}")
            catalog[key] = (family, label, service)
    return catalog
