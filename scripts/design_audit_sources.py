"""Android resource, contrast, control-consumer and native-backdrop source checks.

These are bounded source hints. Disabled contrast is a project criterion;
actual blur, accessibility, focus and gestures still need device observations.
"""
import re

from design_audit_core import MAIN, ROOT, block_after, calls, contrast_role, read, report, require, resolved_color, resources


def check_contrast(palette, profile):
    pairs = [
        ("color_text_primary", "color_bg", "color_bg", 4.5, "body text"),
        ("color_text_primary", "color_surface", "color_bg", 4.5, "sheet/list titles"),
        ("color_text_primary", "color_surface_high", "color_bg", 4.5, "neutral controls"),
        ("color_text_secondary", "color_bg", "color_bg", 4.5, "body captions"),
        ("color_text_secondary", "color_surface", "color_bg", 4.5, "sheet captions"),
        ("color_text_secondary", "color_surface_high", "color_bg", 4.5, "monograms"),
        ("color_text_hint", "color_surface_high", "color_bg", 4.5, "actual input hints"),
        ("color_on_accent", "bao_green", "color_call_chrome", 4.5, "Incoming Accept icon"),
        ("color_on_accent", "color_danger", "color_call_chrome", 4.5, "Incoming Decline icon"),
        ("color_call_text", "color_call_chrome", "color_call_chrome", 4.5, "call title"),
        ("color_call_text_secondary", "color_call_chrome", "color_call_chrome", 4.5, "call status"),
        ("color_danger", "color_surface", "color_bg", 4.5, "form error text"),
        ("color_danger", "color_bg", "color_bg", 4.5, "server error status"),
        ("color_warning", "color_surface", "color_bg", 4.5, "Setup warning state"),
        ("color_control_disabled_text", "color_control_disabled_bg", "color_bg", 4.5, "disabled visibility (project criterion)"),
        ("color_control_focus", "color_surface", "color_bg", 3, "focus outline / sheet surround"),
        ("color_control_focus", "color_surface_high", "color_bg", 3, "focus outline / neutral fill"),
        ("color_control_focus", "bao_green", "color_bg", 3, "focus outline / primary fill"),
        ("color_control_focus", "color_danger", "color_call_chrome", 3, "focus outline / destructive fill"),
        ("color_control_focus", "bao_green_dark", "color_bg", 3, "focus outline / selected fill"),
        ("color_control_selected_outline", "color_surface_high", "color_bg", 3, "selected outline / neutral fill"),
        ("color_control_selected_outline", "bao_green_dark", "color_bg", 3, "selected outline / selected tab fill"),
    ]
    for foreground, background, backdrop, minimum, label in pairs:
        contrast_role(palette, profile, foreground, background, backdrop, minimum, label)
    contrast_role(palette, profile, "color_control_focus", "color_surface_high", "color_bg", 3,
                  "list-row direct focus outline (no outlineFor fallback)")
    contrast_role(palette, profile, "color_control_selected_outline", "color_surface_high", "color_bg", 3,
                  "list-row direct selected outline (no outlineFor fallback)")
    # Bound to reviewed native content surfaces. This does not certify arbitrary
    # artwork/video or the final blurred pixels; those require device evidence.
    for base in ("color_bg", "color_surface", "color_surface_high", "color_control_disabled_bg"):
        contrast_role(palette, profile, "color_text_primary", "color_glass_bar", base, 4.5,
                      f"navigation text above sampled {base}")
    for base in ("color_bg", "color_surface", "color_surface_high", "color_control_disabled_bg"):
        contrast_role(palette, profile, "bao_green", "color_glass_bar", base, 4.5,
                      f"actual selected tab text above sampled {base}")
        for outline in ("color_control_focus", "color_control_selected_outline"):
            contrast_role(palette, profile, outline, "color_glass_bar", base, 3,
                          f"glass interaction outline above sampled {base}")
    contrast_role(palette, profile, "bao_green", "color_chrome_fallback", "color_bg", 4.5,
                  "actual selected tab text over opaque fallback")
    contrast_role(palette, profile, "color_text_primary", "color_chrome_fallback", "color_bg", 4.5,
                  "opaque navigation fallback text")


def check_source_tokens(kotlin):
    raw_color = re.compile(r"#[0-9A-Fa-f]{6,8}\b|0x[0-9A-Fa-f]{6,8}\b|android\.graphics\.Color\.|setTextColor\(0x")
    raw_dimension = re.compile(r"\b\d+(?:\.\d+)?(?:dp|sp)\b")
    literal = re.compile(r'(?:\btext\s*=|\bsetText\(|\bhint\s*=)\s*"([^"\\]|\\.)+"')
    for path in kotlin:
        for number, line in enumerate(read(path).splitlines(), 1):
            location = f"{path.relative_to(ROOT)}:{number}"
            if raw_color.search(line):
                report("ERROR", "DRY", f"raw Kotlin color: {location}: {line.strip()}")
            if raw_dimension.search(line):
                report("ERROR", "DRY", f"raw Kotlin dimension: {location}: {line.strip()}")
            match = literal.search(line)
            if match and "R.string." not in line and "getString(" not in line:
                content = re.sub(r"\$\{[^}]*\}|\$\w+|\\.", "", match.group())
                if re.search(r"[A-Za-z]", content):
                    report("ERROR", "STRINGS", f"literal UI copy: {location}: {line.strip()}")


def check_references(kotlin, palettes, dimensions):
    strings = resources(MAIN / "res/values/strings.xml")
    names = {"string": set(strings), "dimen": set(dimensions)}
    for profile, palette in palettes.items():
        for path in kotlin:
            source = read(path)
            for kind, values in (*names.items(), ("color", set(palette))):
                for missing in set(re.findall(rf"R\.{kind}\.([A-Za-z0-9_]+)", source)) - values:
                    report("ERROR", "REFERENCES", f"{profile}: missing R.{kind}.{missing} used by {path.relative_to(ROOT)}")
    canonical_copy = {
        "action_clear_history": "Clear History",
        "action_create_link": "Create FaceTime Link",
        "action_join_link": "Join FaceTime Link",
        "link_label": "FaceTime Link",
        "new_link_label": "New FaceTime Link",
        "default_call_label": "FaceTime Call",
        "setup_save": "Save Connection",
        "setup_config_title": "Mac Connection",
        "input_label": "Phone, Email or FaceTime Link",
        "contacts_imessage_header": "iMessage Contacts",
        "contacts_device_header": "On This Device",
        "setup_incoming_configure": "Incoming Invitations",
        "action_cancel": "Cancel",
    }
    for name, value in canonical_copy.items():
        require(strings.get(name) == value, "STRINGS", f"canonical title-case copy for {name}: {value}")
    target = dimensions.get("min_touch_target", "")
    match = re.fullmatch(r"(\d+(?:\.\d+)?)dp", target)
    require(match is not None and float(match[1]) >= 48, "ANDROID", f"project minimum control target is at least48dp: {target}")


def check_incoming_controls(source):
    controls = calls(source, "callControlButton")
    require(len(controls) == 2, "CONSUMERS", "Incoming has exactly two shared native controls")
    for control in controls:
        label = "Decline" if "ic_call_end" in control else "Accept"
        fill = "colorDanger" if label == "Decline" else "colorBaoGreen"
        require(re.search(rf"bgColor\s*=\s*theme\.{fill}\b", control) is not None,
                "CONSUMERS", f"Incoming {label} contrast model matches actual {fill} consumer")
        for role, color in (("captionColor", "colorCallText"), ("iconTint", "colorOnAccent")):
            require(re.search(rf"{role}\s*=\s*theme\.{color}\b", control) is not None,
                    "CONSUMERS", f"Incoming {label} {role} uses {color} against its actual circle or call surface")
    require("ForegroundSurface.bind" in source, "CONSUMERS", "Incoming owns foreground focus and accessibility isolation")


def check_control_states(source):
    require(all(snippet in source for snippet in (
        "outlineFor(fillColor, colorFocus)", "outlineFor(fillColor, colorSelectedOutline)",
        "ColorUtils.compositeColors(fillColor, colorSurface)",
        "ColorUtils.calculateContrast(preferred, surface) >= 3.0", "preferred else colorOnAccent")),
        "STATES", "actual shared outlineFor consumer and contrast-aware fallback retained")
    for token in ("state_enabled", "state_focused", "state_selected", "RippleDrawable", "colorDisabledText",
                  "colorDisabledBackground", "colorFocus", "colorSelectedOutline"):
        require(token in source, "STATES", f"shared control owner retains {token}")


def check_edge_painter(source):
    """Trace the actual native material painter and consumer; optical output needs device proof."""
    painter = block_after(source, "private val edgePaint")
    for snippet in ("color = theme.colorGlassHighlight", "style = android.graphics.Paint.Style.STROKE", "strokeWidth = theme.hairline.toFloat()"):
        require(snippet in painter, "MATERIAL", f"actual glass edge painter binds canonical input: {snippet}")
    draw = block_after(source, "override fun draw")
    for snippet in ("val inset = theme.hairline / 2f", "edgeRect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)",
                    "canvas.drawRoundRect(edgeRect, radius - inset, radius - inset, edgePaint)", "if (!focused && radius > 0)"):
        require(snippet in draw, "MATERIAL", f"actual glass edge consumer retains bounds, radius, focus ownership and paint: {snippet}")


def check_glass(palettes, kotlin):
    glass_path = MAIN / "java/com/baotime/app/ui/components/GlassNavigation.kt"
    source = read(glass_path)
    root = read(MAIN / "java/com/baotime/app/ui/RootUi.kt")
    settings = read(MAIN / "java/com/baotime/app/config/AppearancePreferences.kt")
    check_edge_painter(source)
    for profile, palette in palettes.items():
        for name in ("color_glass_bar", "color_glass_sheet", "color_glass_call_bar", "color_glass_edge"):
            color = resolved_color(palette, name)
            require(color is not None and 0 < color[3] < 1, "MATERIAL", f"{profile} {name} is a valid nonzero translucent ARGB token")
        fallback = resolved_color(palette, "color_chrome_fallback")
        require(fallback is not None and fallback[3] == 1, "MATERIAL", f"{profile} navigation fallback is opaque")
    targets = re.findall(r"GlassNavigation\(theme,\s*scroll,\s*(\w+)\)", root)
    require(targets == ["this"],
            "MATERIAL", "tab material samples only the native ScrollView at its single canonical construction site")
    consumers = {"RootUi.kt", "ModalSheetUi.kt", "CallOverlayUi.kt"}
    for path in kotlin:
        text = read(path)
        if path != glass_path and "setRenderEffect(" in text:
            report("ERROR", "MATERIAL", f"RenderEffect outside canonical backdrop owner: {path.relative_to(ROOT)}")
        if path.name not in consumers and path != glass_path and "GlassNavigation(" in text:
            report("ERROR", "MATERIAL", f"unreviewed backdrop sampling consumer: {path.relative_to(ROOT)}")
    checks = (("source.draw(canvas)", "native scrolling content is the sampled draw source"),
              ("target.background = glass", "sampled material is the control's drawable background"),
              ("target.foreground = theme.glassControlForeground(radius)", "interaction ripple remains above the material"),
              ("target.clipToOutline = true", "native outline clips the floating surface"),
              ("BoxBlur.blur", "sampled pixels receive actual bitmap blur"),
              ("bitmap.setPixels", "blurred pixels return to the sampled bitmap"),
              ("Executors.newSingleThreadExecutor", "bitmap processing stays off the UI thread"),
              ("target.post", "completed blur returns to the target on the UI thread"),
              ("blurred && bitmap != null", "only completed blur reaches the material draw"),
              ("canvas.drawBitmap(bitmap", "blurred bitmap reaches actual background draw"),
              ("blurExecutor?.shutdownNow()", "detached material releases background work"),
              ("if (selected && radius > 0)", "selected tab uses a direct indicator without a second capsule"),
              ("canvas.drawColor(fallbackColor)", "unsupported material uses its canonical opaque fallback"),
              ("Build.VERSION.SDK_INT < 31", "unsupported API exits before recording blur"),
              ("!AppearancePreferences.backdropEnabled(theme.context)", "system appearance settings control fallback"),
              ("OnScrollChangedListener", "scrolling refreshes sampled material"),
              ("OnGlobalLayoutListener", "source layout refreshes sampled material"),
              ("removeOnScrollChangedListener(onScroll)", "detach releases scrolling observation"),
              ("removeOnGlobalLayoutListener(onLayout)", "detach releases layout observation"),
              ("target.removeOnLayoutChangeListener(onTargetLayout)", "detach releases target observation"),
              ("glass.clear()", "detach releases sampled pixels"))
    for snippet, label in checks:
        require(snippet in source, "MATERIAL", label)
    draw = block_after(source, "override fun draw")
    require("canvas.drawRoundRect(" in draw and "edgePaint)" in draw,
            "MATERIAL", "actual material draw invokes the canonical edge painter")
    require("addView(" not in source, "MATERIAL", "material drawable adds no nested view hierarchy")
    require("RenderNode" not in source and "setRenderEffect(" not in source and "renderscript" not in source,
            "MATERIAL", "control content stays sharp without view effects or deprecated blur runtimes")
    theme = read(MAIN / "java/com/baotime/app/ui/theme/BaoTimeTheme.kt")
    foreground = block_after(theme, "fun glassControlForeground")
    for state in ("state_enabled", "state_focused", "state_selected", "colorDisabledBackground", "colorFocus", "RippleDrawable"):
        require(state in foreground, "STATES", f"actual glass foreground owns {state}")
    require("colorSelectedOutline" not in foreground, "STATES",
            "selected glass tabs use text state without a nested capsule outline")
    styling = block_after(root, "fun styleTabs")
    require("setBackground" not in styling and "background =" not in styling and "button.isSelected" in styling,
            "STATES", "tab state updates retain installed glass background")
    for profile, palette in palettes.items():
        clear = resolved_color(palette, "color_control_clear")
        require(clear is not None and clear[3] == 0, "MATERIAL", f"{profile} foreground material fill remains transparent")
    require("root.draw(" not in source and "content.draw(" not in source and "WebView" not in source,
            "MATERIAL", "sampler excludes its chrome, controls and WebView")
    require(all(token in settings for token in ("SDK_INT < 31", "isPowerSaveMode", "contrast")),
            "MATERIAL", "configuration owner handles API, power-save and contrast fallback")
    modal = read(MAIN / "java/com/baotime/app/ui/ModalSheetUi.kt")
    require("GlassNavigation(theme, source, scroll" in modal and "background = theme.surfaceDrawable" not in modal,
            "MATERIAL", "visible sheet viewport owns direct glass without a second opaque card")
    call = read(MAIN / "java/com/baotime/app/ui/CallOverlayUi.kt")
    require("GlassNavigation(theme, web, toolbar" in call,
            "MATERIAL", "call toolbar samples the room directly")


def check_manifest_parity():
    manifest = read(MAIN / "AndroidManifest.xml")
    integration = read(MAIN / "java/com/baotime/app/system/SystemIntegration.kt")
    for package in re.findall(r'const val PACKAGE_[A-Z_]+ = "([^"]+)"', integration):
        require(f'android:name="{package}"' in manifest, "DRY", f"manifest package visibility includes canonical {package}")
