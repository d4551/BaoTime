"""Controls for instrumentation failures that still return a zero adb exit code."""
import unittest

from audit_device import verify_census


def status(test, code, index=1, total=1, class_name="Example"):
    return (
        f"INSTRUMENTATION_STATUS: class={class_name}\n"
        f"INSTRUMENTATION_STATUS: test={test}\n"
        "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n"
        f"INSTRUMENTATION_STATUS: current={index}\n"
        f"INSTRUMENTATION_STATUS: numtests={total}\n"
        f"INSTRUMENTATION_STATUS_CODE: {code}\n"
    )


class DeviceAuditTest(unittest.TestCase):
    def setUp(self):
        self.start = status("opens", 1)
        self.success = status("opens", 0)
        self.summary = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        self.expected = {("Example", "opens")}

    def test_complete_success_is_accepted(self):
        verify_census(self.start + self.success + self.summary, 0, self.expected)

    def test_failures_skips_and_partial_runs_are_rejected_even_when_adb_exits_zero(self):
        for terminal in ["", status("opens", -2), status("opens", -3), status("opens", -4)]:
            with self.subTest(terminal=terminal):
                with self.assertRaises(ValueError):
                    verify_census(self.start + terminal + self.summary, 0, self.expected)

    def test_census_rejects_empty_wrong_class_or_duplicate_tests(self):
        repeated = "".join(status("opens", code, index, total=2) for index in (1, 2) for code in (1, 0))
        for output, expected in [(self.summary, self.expected),
                                 ("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n", set()),
                                 (self.start + self.success + self.summary, {("Different", "opens")}),
                                 (repeated + "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n",
                                  self.expected | {("Example", "closes")})]:
            with self.subTest(expected=expected):
                with self.assertRaises(ValueError):
                    verify_census(output, 0, expected)

    def test_same_class_cannot_substitute_another_method(self):
        substituted = status("substitute", 1) + status("substitute", 0) + self.summary
        with self.assertRaisesRegex(ValueError, "Unexpected test"):
            verify_census(substituted, 0, self.expected)

    def test_transport_error_or_missing_final_status_rejects_successful_test(self):
        with self.assertRaises(ValueError):
            verify_census(self.start + self.success + self.summary, 1, self.expected)
        with self.assertRaises(ValueError):
            verify_census(self.start + self.success + "OK (1 test)\n", 0, self.expected)

    def test_later_runner_error_or_dangling_status_rejects_prior_success(self):
        for extra in ("FAILURES!!!", "INSTRUMENTATION_FAILED: aborted", "INSTRUMENTATION_RESULT: shortMsg=crashed",
                      "INSTRUMENTATION_STATUS: test=never-finished"):
            with self.subTest(extra=extra):
                with self.assertRaises(ValueError):
                    verify_census(self.start + self.success + self.summary + extra, 0, self.expected)
