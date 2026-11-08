"""Verify launcher pixels, native-export provenance and reproducible Android projections."""
import json
import math
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree
from collections import deque
from pathlib import Path

from design_audit_core import ANDROID, MAIN, ROOT, digest, read, report, require
from render_android_icons import ADAPTIVE_CANVAS_DP, DENSITIES, SAFE_DIAMETER_DP


def png_dimensions(path):
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        report("ERROR", "ICONS", f"not a valid PNG header: {path.relative_to(ROOT)}")
        return None
    return struct.unpack(">II", data[16:24])


def rgba_pixels(path):
    result = subprocess.run(["magick", str(path), "-depth", "8", "rgba:-"], check=True, stdout=subprocess.PIPE)
    return result.stdout


def aperture(alpha, width, height):
    transparent = {index for index, value in enumerate(alpha) if value == 0}
    edges = {index for index in transparent if index < width or index >= width * (height - 1) or index % width in (0, width - 1)}
    queue = deque(edges)
    while queue:
        index = queue.popleft()
        for adjacent in (index - width, index + width, index - 1, index + 1):
            if adjacent in transparent and adjacent not in edges and abs(adjacent % width - index % width) <= 1:
                edges.add(adjacent)
                queue.append(adjacent)
    return transparent - edges


def check_adaptive_pixels(path, expected, monochrome=False):
    pixels = rgba_pixels(path)
    require(len(pixels) == expected * expected * 4, "ICONS", f"RGBA extent: {path.relative_to(ROOT)}")
    alpha = pixels[3::4]
    occupied = [index for index, value in enumerate(alpha) if value]
    if not occupied:
        report("ERROR", "ICONS", f"empty adaptive art: {path.relative_to(ROOT)}")
        return
    center = expected / 2
    radius = max(math.hypot(index % expected + 0.5 - center, index // expected + 0.5 - center) for index in occupied)
    allowed = expected * SAFE_DIAMETER_DP / ADAPTIVE_CANVAS_DP / 2
    require(radius <= allowed, "ICONS", f"safe alpha radius {path.relative_to(ROOT)}: {radius:.3f}px <= {allowed:.3f}px")
    holes = aperture(alpha, expected, expected)
    center_pixel = (expected // 2) * expected + expected // 2
    require(center_pixel in holes, "ICONS", f"central camera aperture is enclosed and transparent: {path.relative_to(ROOT)}")
    if monochrome:
        require(all(pixels[index * 4:index * 4 + 3] == b"\xff\xff\xff" for index in occupied),
                "ICONS", f"monochrome pixels are a white alpha silhouette: {path.relative_to(ROOT)}")


def check_launcher_files():
    approved = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
                "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}
    require(DENSITIES == approved and ADAPTIVE_CANVAS_DP == 108 and SAFE_DIAMETER_DP == 66,
            "ICONS", "canonical generator retains approved five densities and66/108 Android safe-circle policy")
    expected_paths = set()
    for appearance in ("light", "dark"):
        for density, (legacy, adaptive) in approved.items():
            qualifier = density if appearance == "light" else f"night-{density}"
            directory = MAIN / f"res/mipmap-{qualifier}"
            projections = {"ic_launcher.png": legacy, "ic_launcher_round.png": legacy, "ic_launcher_foreground.png": adaptive}
            if appearance == "light":
                projections["ic_launcher_monochrome.png"] = adaptive
            for name, size in projections.items():
                path = directory / name
                expected_paths.add(str(path.relative_to(ROOT)))
                if not path.is_file():
                    report("ERROR", "ICONS", f"missing launcher projection: {path.relative_to(ROOT)}")
                    continue
                valid = png_dimensions(path) == (size, size)
                require(valid, "ICONS", f"{path.relative_to(ROOT)} dimensions are {size}x{size}")
                if valid and name in ("ic_launcher_foreground.png", "ic_launcher_monochrome.png"):
                    check_adaptive_pixels(path, size, name == "ic_launcher_monochrome.png")
            monochrome = directory / "ic_launcher_monochrome.png"
            foreground = directory / "ic_launcher_foreground.png"
            if appearance == "light" and monochrome.is_file() and foreground.is_file():
                require(rgba_pixels(monochrome)[3::4] == rgba_pixels(foreground)[3::4], "ICONS",
                        f"monochrome alpha exactly matches colored foreground: {density}")
            square = directory / "ic_launcher.png"
            circular = directory / "ic_launcher_round.png"
            if square.is_file() and circular.is_file():
                require(rgba_pixels(square)[3::4] != rgba_pixels(circular)[3::4], "ICONS",
                        f"rounded-square and circular legacy alpha masks differ: {qualifier}")
    return expected_paths


def canonical_icon_inputs(document):
    paths = {"assets/BaoTime.icon/icon.json"}
    for group in document["groups"]:
        for layer in group["layers"]:
            paths.add(f"assets/BaoTime.icon/Assets/{layer['image-name']}")
    return paths


def check_hash_records(records, label):
    names = [record["path"] for record in records]
    require(len(names) == len(set(names)), "PROVENANCE", f"{label} paths are unique")
    for record in records:
        path = ROOT / record["path"]
        safe = path.resolve().is_relative_to(ROOT.resolve())
        require(safe and path.is_file(), "PROVENANCE", f"{label} file is local and exists: {record['path']}")
        if safe and path.is_file():
            require(digest(path) == record["sha256"], "PROVENANCE", f"{label} current SHA256: {record['path']}")
    return set(names)


def check_native_receipt(inputs):
    path = ROOT / "assets/native-export-receipt.json"
    if not path.is_file():
        report("ERROR", "PROVENANCE", "native Icon Composer export receipt missing")
        return set()
    receipt = json.loads(read(path))
    bound = check_hash_records(receipt["inputs"], "native source")
    require(bound == inputs, "PROVENANCE", "native exports bind all and only canonical JSON/SVG inputs")
    exports = check_hash_records(receipt["exports"], "native export")
    expected = {f"assets/BaoTime Exports/BaoTime-iOS-{name}-1024@2x.png"
                for name in ("Default", "Dark", "TintedDark", "TintedLight", "ClearDark", "ClearLight")}
    require(exports == expected, "PROVENANCE", "exact six native appearance paths are retained")
    require("Icon Composer.app" in receipt["application"] and "Export" in receipt["method"],
            "PROVENANCE", "native receipt records its application and export method (not independent runtime certification)")
    for name in exports:
        if (ROOT / name).is_file():
            require(png_dimensions(ROOT / name) == (2048, 2048), "ICONS", f"native 1024pt at2x export extent: {name}")
    return exports | {str(path.relative_to(ROOT))}


def gradient_hex(value):
    channels = [float(channel) for channel in value.split(":", 1)[1].split(",")]
    if len(channels) != 4 or not all(math.isfinite(channel) and 0 <= channel <= 1 for channel in channels):
        report("ERROR", "ICONS", "Android gradient projection requires four bounded sRGB/alpha channels")
        return ""
    return "#" + "".join(f"{round(channel * 255):02X}" for channel in (channels[3], *channels[:3]))


def check_background_xml(document):
    for appearance, qualifier in (("light", "drawable"), ("dark", "drawable-night")):
        matches = [specialization["value"] for specialization in document["fill-specializations"]
                   if specialization.get("appearance", "light") == appearance]
        require(len(matches) == 1, "ICONS", f"canonical {appearance} background is unambiguous")
        path = MAIN / f"res/{qualifier}/ic_launcher_background.xml"
        if not path.is_file() or len(matches) != 1:
            report("ERROR", "ICONS", f"missing canonical {appearance} background projection")
            continue
        expected = [gradient_hex(stop) for stop in matches[0]["linear-gradient"]]
        tree = xml.etree.ElementTree.parse(path)
        actual = [item.get(ANDROID + "color", "") for item in tree.iter("item")]
        actual = ["#FF" + color[1:] if re.fullmatch(r"#[0-9A-Fa-f]{6}", color) else color for color in actual]
        require(actual == expected, "ICONS", f"{appearance} generated XML gradient equals icon.json, independent of UI accent")
        require("Generated by scripts/render_android_icons.py" in read(path), "PROVENANCE", f"background declares canonical writer: {qualifier}")


def check_icon_provenance(expected_outputs, inputs):
    path = ROOT / "assets/android-icon-export.json"
    if not path.is_file():
        report("ERROR", "PROVENANCE", "Android icon export manifest missing")
        return
    manifest = json.loads(read(path))
    actual_inputs = check_hash_records(manifest["inputs"], "Android export input")
    require(actual_inputs == inputs, "PROVENANCE", "Android export binds complete canonical/native input set")
    generator = manifest["generator"]
    require(generator["path"] == "scripts/render_android_icons.py", "PROVENANCE", "Android export names canonical generator")
    check_hash_records([generator], "Android generator")
    require(bool(manifest["imageMagick"]), "PROVENANCE", "Android export retains local ImageMagick identity")
    outputs = check_hash_records(manifest["outputs"], "Android projection")
    require(outputs == expected_outputs, "PROVENANCE", "Android export output inventory is complete and exact")
    rebuild_projections(manifest, inputs)
    for record in manifest["outputs"]:
        output = ROOT / record["path"]
        if output.suffix == ".png" and output.is_file():
            require(png_dimensions(output) == (record["width"], record["height"]), "PROVENANCE", f"manifest dimensions match: {record['path']}")


def rebuild_projections(manifest, inputs):
    names = inputs | {manifest["generator"]["path"]}
    if not all((ROOT / name).is_file() and (ROOT / name).resolve().is_relative_to(ROOT.resolve()) for name in names):
        report("ERROR", "PROVENANCE", "isolated canonical rebuild cannot run: input inventory incomplete")
        return
    with tempfile.TemporaryDirectory(prefix="baotime-icon-gate-") as directory:
        work = Path(directory)
        for name in names:
            target = work / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        result = subprocess.run([sys.executable, str(work / "scripts/render_android_icons.py")],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        require(result.returncode == 0, "PROVENANCE", f"isolated canonical icon rebuild exits0 (observed {result.returncode})")
        if result.returncode != 0:
            report("ERROR", "PROVENANCE", result.stdout)
            return
        for record in manifest["outputs"]:
            rebuilt = work / record["path"]
            current = ROOT / record["path"]
            require(rebuilt.is_file() and current.is_file() and digest(rebuilt) == digest(current),
                    "PROVENANCE", f"canonical rebuild reproduces exact projection bytes: {record['path']}")


def check_icons():
    path = ROOT / "assets/BaoTime.icon/icon.json"
    if not path.is_file():
        report("ERROR", "ICONS", "canonical icon.json missing")
        return
    document = json.loads(read(path))
    source_inputs = canonical_icon_inputs(document)
    native_inputs = check_native_receipt(source_inputs)
    check_background_xml(document)
    outputs = check_launcher_files()
    outputs.update(("app/src/main/res/drawable/ic_launcher_background.xml",
                    "app/src/main/res/drawable-night/ic_launcher_background.xml", "assets/baotime_icon_512.png"))
    check_icon_provenance(outputs, source_inputs | native_inputs | {"scripts/render_icon_previews.py"})
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        tree = xml.etree.ElementTree.parse(MAIN / "res/mipmap-anydpi" / name)
        expected = {"background": "@drawable/ic_launcher_background", "foreground": "@mipmap/ic_launcher_foreground",
                    "monochrome": "@mipmap/ic_launcher_monochrome"}
        actual = {child.tag: child.get(ANDROID + "drawable") for child in tree.getroot()}
        require(actual == expected, "ICONS", f"adaptive mask projection owns background/foreground/monochrome: {name}")
