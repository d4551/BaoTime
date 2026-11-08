#!/usr/bin/env python3
"""Render the canonical Icon Composer SVG and appearance fills using local ImageMagick.

Outputs composition previews, not Xcode's optical-material simulation. Tinted-light
is the luminance inverse of the canonical tinted-dark appearance. Apple catalog
PNGs stay unmasked; preview PNGs show a rounded-square silhouette.
"""
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ICON_DIR = ROOT / "assets/BaoTime.icon"
OUT_DIR = ROOT / "assets/icon-previews"
CATALOG_DIR = ROOT / "assets/AppIcon.appiconset"
CANVAS_SIZE = 1024


def run(command: list) -> None:
    subprocess.run(command, check=True)


def parse_srgb(value: str) -> str:
    channels = value.split(":", 1)[1].split(",")
    red, green, blue = (round(float(channel) * 255) for channel in channels[:3])
    return f"#{red:02X}{green:02X}{blue:02X}"


def fill_for(specs: list, appearance: str) -> dict:
    for spec in specs:
        if spec.get("appearance", "light") == appearance:
            return spec["value"]
    if appearance != "light":
        return fill_for(specs, "light")
    raise ValueError("A light fill specialization is required")


def background_command(fill: dict, size: int) -> list:
    if "solid" in fill:
        return ["-size", f"{size}x{size}", f"xc:{parse_srgb(fill['solid'])}"]
    stops = [parse_srgb(stop) for stop in fill["linear-gradient"]]
    return ["-size", f"{size}x{size}", f"gradient:{stops[0]}-{stops[1]}"]


def visible_layers(document: dict) -> list:
    return [layer for group in document["groups"] if not group.get("hidden", False)
            for layer in group["layers"] if not layer.get("hidden", False)]


def render_layer(layer: dict, appearance: str, destination: Path, work: Path) -> None:
    image = ICON_DIR / "Assets" / layer["image-name"]
    mask = work / f"{appearance}-{image.stem}-mask.png"
    position = layer["position"]
    if position["translation-in-points"] != [0, 0]:
        raise ValueError("This icon's authored layers must remain centered")
    run(["magick", "-background", "none", str(image), "-resize", f"{CANVAS_SIZE}x{CANVAS_SIZE}",
         "-alpha", "extract", "-resize", f"{position['scale'] * 100}%", "-gravity", "center",
         "-background", "black", "-extent", f"{CANVAS_SIZE}x{CANVAS_SIZE}", str(mask)])
    fill = fill_for(layer["fill-specializations"], appearance)
    run(["magick", *background_command(fill, CANVAS_SIZE), str(mask), "-alpha", "off",
         "-compose", "CopyOpacity", "-composite", "-channel", "A", "-evaluate", "Multiply",
         str(layer["opacity"]), "+channel", "-strip", "-depth", "8", str(destination)])


def render_composition(document: dict, appearance: str, destination: Path,
                       work: Path, foreground: bool = False) -> None:
    if foreground:
        command = ["magick", "-size", f"{CANVAS_SIZE}x{CANVAS_SIZE}", "xc:none"]
    else:
        command = ["magick", *background_command(fill_for(document["fill-specializations"], appearance), CANVAS_SIZE)]
    for index, layer in enumerate(visible_layers(document)):
        png = work / f"{appearance}-layer-{index}.png"
        render_layer(layer, appearance, png, work)
        command += [str(png), "-compose", "Over", "-composite"]
    run(command + ["-strip", "-depth", "8", str(destination)])


def legacy_mask(destination: Path, round_icon: bool) -> None:
    geometry = "circle 512,512 512,24" if round_icon else "roundrectangle 24,24 1000,1000 224,224"
    run(["magick", "-size", f"{CANVAS_SIZE}x{CANVAS_SIZE}", "xc:black", "-fill", "white",
         "-draw", geometry, "-strip", "-depth", "8", str(destination)])


def apply_mask(source: Path, mask: Path, destination: Path) -> None:
    run(["magick", str(source), str(mask), "-alpha", "off", "-compose", "CopyOpacity",
         "-composite", "-strip", "-depth", "8", str(destination)])


def main() -> None:
    document = json.loads((ICON_DIR / "icon.json").read_text())
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    CATALOG_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        mask = work / "rounded-square.png"
        legacy_mask(mask, False)
        variants = [("Light", "light", "BaoTime-1024.png"),
                    ("Dark", "dark", "BaoTime-Dark-1024.png"),
                    ("TintedLight", "tinted", "BaoTime-TintedLight-1024.png"),
                    ("TintedDark", "tinted", "BaoTime-TintedDark-1024.png")]
        previews = []
        for name, appearance, filename in variants:
            catalog = CATALOG_DIR / filename
            render_composition(document, appearance, catalog, work)
            if name == "TintedLight":
                run(["magick", str(catalog), "-negate", "-strip", "-depth", "8", str(catalog)])
            preview = OUT_DIR / f"BaoTime-{name}-1024.png"
            apply_mask(catalog, mask, preview)
            previews.append(preview)
        run(["magick", "montage", *map(str, previews), "-tile", "4x1", "-geometry", "256x256+12+12",
             "-background", "#E8ECE9", str(OUT_DIR / "BaoTime-Appearances.png")])
        cells = []
        for size in (48, 64, 96):
            for preview in previews:
                cell = work / f"{size}-{preview.stem}.png"
                run(["magick", str(preview), "-resize", f"{size}x{size}", "-gravity", "center",
                     "-background", "#E8ECE9", "-extent", "112x112", str(cell)])
                cells.append(cell)
        run(["magick", "montage", *map(str, cells), "-tile", "4x3", "-geometry", "+12+12",
             "-background", "#E8ECE9", str(OUT_DIR / "BaoTime-Launcher-Scale.png")])
    print("Rendered four canonical appearance compositions and launcher-scale previews.")


if __name__ == "__main__":
    main()
