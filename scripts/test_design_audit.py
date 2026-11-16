"""Negative controls for the Android design source checks."""
import unittest
from unittest.mock import patch

import design_audit_sources as checks
from design_audit_core import FINDINGS, MAIN, read


class DesignAuditTest(unittest.TestCase):
    def assert_rejected(self, check, source, category, finding=None):
        FINDINGS.clear()
        self.addCleanup(FINDINGS.clear)
        check(source)
        errors = [message for severity, rule, message in FINDINGS if severity == "ERROR" and rule == category]
        if finding is None:
            self.assertTrue(errors, f"{check.__name__} accepted invalid {category} input")
        else:
            self.assertIn(finding, errors)

    def test_repeated_accept_is_not_a_decline(self):
        path = MAIN / "java/com/baotime/app/ui/IncomingCallUi.kt"
        source = read(path)
        for old, new in (("ic_call_end", "ic_call_accept"), ("action_decline", "action_accept"),
                         ("desc_decline_call", "desc_accept_call"), ("colorDanger", "colorBaoGreen")):
            source = source.replace(old, new)
        self.assert_rejected(checks.check_incoming_controls, source, "CONSUMERS")

    def test_commented_foreground_binding_does_not_count(self):
        path = MAIN / "java/com/baotime/app/ui/IncomingCallUi.kt"
        source = read(path).replace(
            "ForegroundSurface.bind(this, decline, caller, lightStatusBars = false, lightNavigationBars = false,\n"
            "                systemBarColor = theme.colorCallChrome)",
            "/* outer /* nested */ ForegroundSurface.bind(this, decline, caller) */",
        )
        self.assert_rejected(checks.check_incoming_controls, source, "CONSUMERS")

    def test_edge_paint_calls_in_comments_do_not_count(self):
        source = """
            private val edgePaint = Paint().apply {
                /* color = theme.colorGlassHighlight
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = theme.hairline.toFloat() */
            }
            override fun draw(canvas: Canvas) {
                // if (!focused && radius > 0)
                // val inset = theme.hairline / 2f
                // edgeRect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
                // canvas.drawRoundRect(edgeRect, radius - inset, radius - inset, edgePaint)
            }
        """
        self.assert_rejected(checks.check_edge_painter, source, "MATERIAL")

    def test_glass_tab_state_update_cannot_replace_the_background(self):
        path = MAIN / "java/com/baotime/app/ui/RootUi.kt"
        source = read(path)
        selection = "button.isSelected = index == selectedIndex"
        for assignment in ("button.background=null", "button.background /* assignment */ =\n                null"):
            with self.subTest(assignment=assignment):
                modified = source.replace(selection, f"{selection}\n            {assignment}", 1)
                self.assertNotEqual(modified, source)
                self.assert_rejected(lambda root: checks.check_glass_states(root, {}), modified, "STATES",
                                     "tab state updates retain installed glass background")

    def test_tab_setter_is_rejected_inside_the_actual_declaration(self):
        source = read(MAIN / "java/com/baotime/app/ui/RootUi.kt")
        declaration = "    fun styleTabs("
        selection = "button.isSelected = index == selectedIndex"
        decoys = (
            "    // fun styleTabs() {}\n",
            "    fun styleTabsBackup(button: Button) { button.isSelected = true }\n",
        )
        for decoy in decoys:
            with self.subTest(decoy=decoy):
                modified = source.replace(declaration, decoy + declaration, 1).replace(
                    selection, f"{selection}\n            button.setBackground(null)", 1,
                )
                self.assertNotEqual(modified, source)
                self.assert_rejected(lambda root: checks.check_glass_states(root, {}), modified, "STATES",
                                     "tab state updates retain installed glass background")

    def test_tab_setter_is_rejected_inside_string_interpolation(self):
        source = read(MAIN / "java/com/baotime/app/ui/RootUi.kt")
        selection = "button.isSelected = index == selectedIndex"
        setter = 'val result = "${button.setBackground(null)}"'
        modified = source.replace(selection, f"{selection}\n            {setter}", 1)
        self.assertNotEqual(modified, source)
        self.assert_rejected(lambda root: checks.check_glass_states(root, {}), modified, "STATES",
                             "tab state updates retain installed glass background")

    def test_nested_template_literal_cannot_hide_a_later_tab_setter(self):
        source = read(MAIN / "java/com/baotime/app/ui/RootUi.kt")
        selection = "button.isSelected = index == selectedIndex"
        nested = 'val template = "${listOf("}}").size}"'
        modified = source.replace(selection, f"{selection}\n            {nested}\n            button.setBackground(null)", 1)
        self.assertNotEqual(modified, source)
        self.assert_rejected(lambda root: checks.check_glass_states(root, {}), modified, "STATES",
                             "tab state updates retain installed glass background")

    def test_tab_declaration_cannot_borrow_a_later_function_body(self):
        source = read(MAIN / "java/com/baotime/app/ui/RootUi.kt")
        parameters = "(theme: BaoTimeTheme, tabButtons: List<Button>, selectedIndex: Int)"
        modified = source.replace(f"fun styleTabs{parameters} {{",
                                  f"fun styleTabs{parameters} = Unit\n\n    fun otherTabStyle{parameters} {{", 1)
        self.assertNotEqual(modified, source)
        self.assert_rejected(lambda root: checks.check_glass_states(root, {}), modified, "STATES",
                             "tab state updates retain installed glass background")

    def test_edge_painter_cannot_borrow_a_later_initializer(self):
        source = read(MAIN / "java/com/baotime/app/ui/components/GlassNavigation.kt")
        initializer = "Paint(Paint.ANTI_ALIAS_FLAG)"
        modified = source.replace(f"private val edgePaint = {initializer}.apply {{",
                                  f"private val edgePaint = {initializer}\n"
                                  f"    private val otherPaint = {initializer}.apply {{", 1)
        self.assertNotEqual(modified, source)
        self.assert_rejected(checks.check_edge_painter, modified, "MATERIAL",
                             "actual glass edge painter binds canonical input: color = theme.colorGlassHighlight")

    def test_glass_sheet_cannot_add_an_opaque_card(self):
        ui = MAIN / "java/com/baotime/app/ui"
        modal = ui / "ModalSheetUi.kt"
        source = read(modal)
        root = checks.kotlin_code(read(ui / "RootUi.kt"))
        glass = ui / "components/GlassNavigation.kt"
        card_position = "        card.setPadding("
        for assignment in (
            "card.background=theme.surfaceDrawable(theme.radiusControl, theme.colorSurface)",
            "card.background /* assignment */ = theme.\n            surfaceDrawable(theme.radiusControl, theme.colorSurface)",
        ):
            with self.subTest(assignment=assignment):
                modified = source.replace(card_position, f"        {assignment}\n{card_position}", 1)
                self.assertNotEqual(modified, source)
                with patch.object(checks, "read", side_effect=lambda path: modified if path == modal else read(path)):
                    self.assert_rejected(lambda _: checks.check_glass_consumers(glass, [modal], root), modified,
                                         "MATERIAL", "visible sheet viewport owns direct glass without a second opaque card")

    def test_manifest_must_reference_audited_icon_and_real_queries(self):
        manifest = MAIN / "AndroidManifest.xml"
        source = read(manifest)
        variations = (
            (source.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/ic_video_call"'), "ICONS"),
            (source.replace('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@mipmap/ic_launcher"'), "ICONS"),
            (source.replace('<package android:name="com.beeper.android" />',
                            '<!-- <package android:name="com.beeper.android" /> -->'), "DRY"),
        )
        for modified, category in variations:
            with self.subTest(category=category, changed=modified != source):
                self.assertNotEqual(modified, source)
                with patch.object(checks, "read", side_effect=lambda path: modified if path == manifest else read(path)):
                    self.assert_rejected(lambda _: checks.check_manifest_parity(), modified, category)
