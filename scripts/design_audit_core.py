"""Canonical paths, findings, resource colors and bounded source parsing for the design gate."""
import hashlib
import re
import xml.etree.ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
FINDINGS = []
KOTLIN_NON_CODE = re.compile(
    r"""//[^\n]*|/\*|(?P<prefix>\${2,})?(?P<quote>"{3}|")|'(?:\\.|[^'\\\n])*'|`[^`\n]+`"""
)
KOTLIN_TEMPLATE_TOKEN = re.compile(KOTLIN_NON_CODE.pattern + r"|[{}]")
BLOCK_COMMENT_DELIMITER = re.compile(r"/\*|\*/")


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


def kotlin_code(source):
    """Mask Kotlin comments and literals without changing source offsets."""
    chunks = []
    cursor = 0
    while token := KOTLIN_NON_CODE.search(source, cursor):
        start = token.start()
        end = kotlin_non_code_end(source, token)
        chunks.append(source[cursor:start])
        chunks.append(re.sub(r"[^\n]", " ", source[start:end]))
        cursor = end
    chunks.append(source[cursor:])
    return "".join(chunks)


def kotlin_non_code_end(source, token):
    if token[0] == "/*":
        return block_comment_end(source, token.end())
    quote = token["quote"]
    if quote is None:
        return token.end()

    template_open = (token["prefix"] or "$") + "{"
    escape = r"\\[\s\S]|" if quote == '"' else ""
    markers = re.compile(escape + re.escape(quote) + "|" + re.escape(template_open))
    cursor = token.end()
    while marker := markers.search(source, cursor):
        if marker[0] == quote:
            return marker.end()
        if marker[0] == template_open:
            cursor = kotlin_template_end(source, marker.end())
        else:
            cursor = marker.end()
    return len(source)


def kotlin_template_end(source, start):
    depth = 1
    cursor = start
    while token := KOTLIN_TEMPLATE_TOKEN.search(source, cursor):
        if token[0] in ("{", "}"):
            depth += 1 if token[0] == "{" else -1
            cursor = token.end()
            if depth == 0:
                return cursor
        else:
            cursor = kotlin_non_code_end(source, token)
    return len(source)


def block_comment_end(source, start):
    depth = 1
    for delimiter in BLOCK_COMMENT_DELIMITER.finditer(source, start):
        depth += 1 if delimiter[0] == "/*" else -1
        if depth == 0:
            return delimiter.end()
    return len(source)


def calls(source, function):
    results = []
    for match in re.finditer(rf"\b{re.escape(function)}\s*\(", source):
        end = balanced_end(source, match.end() - 1, "()")
        if end is not None:
            results.append(source[match.start():end])
    return results


def balanced_end(source, opening, delimiters):
    left, right = delimiters
    depth = 1
    for index in range(opening + 1, len(source)):
        depth += (source[index] == left) - (source[index] == right)
        if depth == 0:
            return index + 1
    return None


def kotlin_block(source, marker, *, initializer=None):
    """Return the masked and original bodies of one named declaration."""
    code = kotlin_code(source)
    tokens = r"\s+".join(map(re.escape, marker.split()))
    declarations = tuple(re.finditer(rf"\b{tokens}\b", code))
    if len(declarations) != 1:
        return "", ""
    if initializer is None:
        arguments = r"\s*\("
        body = r"\s*(?::\s*[\w.]+\??\s*)?\{"
    else:
        constructor = rf"(?:\w+\s*\.\s*)*{re.escape(initializer)}"
        arguments = rf"\s*(?::\s*{constructor}\s*)?=\s*{constructor}\s*\("
        body = r"\s*\.\s*apply\s*\{"

    args_open = re.compile(arguments).match(code, declarations[0].end())
    if args_open is None:
        return "", ""
    args_end = balanced_end(code, args_open.end() - 1, "()")
    if args_end is None:
        return "", ""
    body_open = re.compile(body).match(code, args_end)
    if body_open is None:
        return "", ""
    opening = body_open.end() - 1
    end = balanced_end(code, opening, "{}")
    if end is None:
        return "", ""
    return code[opening:end], source[opening:end]
