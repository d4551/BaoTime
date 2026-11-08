#!/usr/bin/env python3
"""Run the narrow Android source/projection gate; no Bao, Apple HIG or runtime certification.

The source validators own resource/consumer/material checks; icon validators own
native export provenance and reproduced projections. Findings and parsing/color
primitives have one canonical owner. Every reported failure remains fatal.
"""
import sys

from design_audit_core import FINDINGS, MAIN, read, require, resources
from design_audit_icons import check_icons
from design_audit_sources import (
    check_contrast,
    check_control_states,
    check_glass,
    check_incoming_controls,
    check_manifest_parity,
    check_references,
    check_source_tokens,
)


def main():
    kotlin = sorted((MAIN / "java").rglob("*.kt"))
    light = resources(MAIN / "res/values/colors.xml")
    dark = {**light, **resources(MAIN / "res/values-night/colors.xml")}
    palettes = {"light": light, "dark": dark}
    require(bool(kotlin), "COVERAGE", "Kotlin source inventory is nonempty (token/source hints only)")
    check_source_tokens(kotlin)
    check_references(kotlin, palettes, resources(MAIN / "res/values/dimens.xml"))
    for profile, palette in palettes.items():
        check_contrast(palette, profile)
    check_control_states(read(MAIN / "java/com/baotime/app/ui/theme/BaoTimeTheme.kt"))
    check_incoming_controls(read(MAIN / "java/com/baotime/app/ui/IncomingCallUi.kt"))
    check_glass(palettes, kotlin)
    check_icons()
    check_manifest_parity()
    for severity, category, message in FINDINGS:
        print(f"[{severity}] {category}: {message}")
    errors = sum(severity == "ERROR" for severity, _, _ in FINDINGS)
    print(f"\n{len(FINDINGS)} checks reported, {errors} error(s). Narrow source checks only; runtime/Apple/Bao certification not supplied.")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
