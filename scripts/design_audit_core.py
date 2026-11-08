"""Canonical paths, findings, resource colors and bounded source parsing for the design gate."""
import hashlib
import re
import xml.etree.ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
FINDINGS = []


def report(severity, category, message):
    FINDINGS.append((severity, category, message))


def require(condition, category, message):
    report("INFO" if condition else "ERROR", category, message)


def read(path):
    return path.read_text(encoding="utf-8")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resources(path):
    values = {}
    for node in xml.etree.ElementTree.parse(path).getroot():
        name = node.attrib["name"]
        require(name not in values, "DRY", f"resource name is unique: {path.relative_to(ROOT)}:{name}")
        values[name] = node.text.strip()
    return values


def resolved_color(palette, name, chain=()):
    if name not in palette or name in chain:
        report("ERROR", "COLOR", f"missing/cyclic color resource: {' -> '.join((*chain, name))}")
        return None
    value = palette[name]
    if value.startswith("@color/"):
        return resolved_color(palette, value.removeprefix("@color/"), (*chain, name))
    if not re.fullmatch(r"#[0-9a-fA-F]{8}", value):
        report("ERROR", "COLOR", f"{name} must resolve to an eight-digit Android ARGB color: {value}")
        return None
    alpha, red, green, blue = (int(value[index:index + 2], 16) / 255 for index in (1, 3, 5, 7))
    return red, green, blue, alpha


def composite(foreground, background):
    alpha = foreground[3] + background[3] * (1 - foreground[3])
    if alpha == 0:
        return 0, 0, 0, 0
    channels = tuple((foreground[i] * foreground[3] + background[i] * background[3] *
                      (1 - foreground[3])) / alpha for i in range(3))
    return *channels, alpha


def luminance(color):
    channels = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
                for value in color[:3]]
    return sum(value * weight for value, weight in zip(channels, (0.2126, 0.7152, 0.0722)))


def contrast(foreground, background):
    brighter, darker = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (brighter + 0.05) / (darker + 0.05)


def contrast_role(palette, profile, foreground, background, backdrop, minimum, label):
    colors = [resolved_color(palette, name) for name in (foreground, background, backdrop)]
    if any(color is None for color in colors):
        return
    text, fill, base = colors
    if base[3] != 1:
        report("ERROR", "COLOR", f"{profile} {label}: backdrop {backdrop} is not opaque")
        return
    surface = composite(fill, base)
    if label.startswith(("focus outline /", "selected outline /")):
        if contrast(composite(text, surface), surface) < 3:
            replacement = resolved_color(palette, "color_on_accent")
            if replacement is None:
                return
            text = replacement
            foreground = "color_on_accent (shared outlineFor fallback)"
    ratio = contrast(composite(text, surface), surface)
    require(ratio >= minimum, "CONTRAST", f"{profile}: {label}: {ratio:.3f}:1 >= {minimum}:1 "
            f"({foreground} over {background} over {backdrop})")


def calls(source, function):
    results = []
    for match in re.finditer(rf"\b{re.escape(function)}\s*\(", source):
        start = match.start()
        depth = 1
        index = match.end()
        while index < len(source) and depth:
            depth += (source[index] == "(") - (source[index] == ")")
            index += 1
        results.append(source[start:index])
    return results


def block_after(source, marker):
    start = source.find(marker)
    opening = source.find("{", start) if start >= 0 else -1
    if opening < 0:
        return ""
    depth = 1
    end = opening + 1
    while end < len(source) and depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[opening:end]
