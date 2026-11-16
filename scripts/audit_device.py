#!/usr/bin/env python3
"""Run the existing Android journeys and fail on incomplete instrumentation evidence."""
import argparse
import os
import re
import subprocess

from design_audit_core import ROOT, digest

PACKAGE = "com.baotime.app"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
APK_OUTPUTS = {
    PACKAGE: "debug/app-debug.apk",
    f"{PACKAGE}.test": "androidTest/debug/app-debug-androidTest.apk",
}
LANES = {
    "native": ("auditFixture", {
        "NativeJourneysTest": (
            "repeatedSheetsEmptySearchBackCloseAndDrag",
            "tabsDeniedContactsAndClearHistoryCancel",
            "setupKeyboardInvalidSaveScrollAndClose",
            "localSessionErrorReloadShareEndAndBack",
            "onlyTheVisibleRoomAutoEntersAfterRotationAndResize",
        ),
        "SurfaceInteractionsTest": (
            "keyboardBackAndScrimHaveSeparateLifecycles",
            "notificationRecipientIsPrivateAndTargetsTheSameActivity",
            "publicRoomWithWhitespaceTitleUsesTheDefaultLabel",
            "historyRejectsCoercionByAndroidJson",
            "setupValidSaveRetainsDraftAndSettingsReturn",
            "dragShortDistanceAndTextDropGestureStayRecoverable",
            "tallSettingsHandleOwnsDragAndClose",
            "foregroundOwnershipRestoresAfterCoveredSurfaceRemoval",
        ),
        "InvitationAndHistoryTest": (
            "incomingDuplicateBackDeclineAcceptAndReplacement",
            "recordCopyShareDeleteAndClearConfirmation",
            "unifiedPushIngressRoutesIntoCallLifecycle",
        ),
        "CredentialStoreTest": (
            "writesVersionedAuthenticatedCiphertextAndReadsWithoutMutation",
            "legacyEncryptedPasswordAuthenticatesWithoutRewriting",
            "failedAuthenticationNeverBecomesPlaintextOrReencryptsStoredBytes",
            "unknownEnvelopeAndPlaintextRequireExplicitReplacement",
            "absentPasswordIsDistinctFromUnavailableAndUrlWritesAdvanceRevision",
            "connectionSnapshotsKeepUrlCredentialAndRevisionTogether",
            "concurrentEncryptionsRetainOneReadableKeyOwner",
        ),
        "ContactDirectoryJourneysTest": ("completeAddressBookSearchFavoriteExpansionAndDial",),
        "NativeMaterialTest": (
            "callCirclesAndCaptionsFitAtNormalAndLargeNarrowSizes",
            "softwarePaintAndTabStatesPreserveTheInstalledGlassMaterial",
        ),
        "RoomOwnershipTest": (
            "roomUrlRejectsCredentialsPortsSchemesAndLookalikeHosts",
            "javaScriptBelongsOnlyToAnUndisposedTrustedRoom",
            "permissionCancellationAndDisposalCannotLeakAcrossRooms",
            "speakerSelectionNeverMutatesModeOrMicrophone",
            "repeatedSpeakerRequestsAreIdempotentAndCancelWithoutChangingMode",
            "releasingSpeakerRemovesTheNativeClientRequest",
            "shippedRoomHelperExistsAndRetiredRoomClassIsAbsent",
            "extractionKeepsExactlyOneControllerOwnerOnMainActivity",
            "recreationKeepsOneControllerOwnerAndSharedApplicationSingletons",
            "serverBannerAndPermissionConsentStayApplicationOwned",
            "mainActivityLeavesUrlClassificationToTheRouter",
            "viewIntentRoomLinkOpensExactlyOneOwnedRoom",
            "urlExtraRoomLinkOpensExactlyOneOwnedRoom",
            "facetimeSchemeIntentPrefillsDialerWithoutOpeningRoom",
            "telIntentPrefillsDialerWithoutOpeningRoom",
            "sharedTextAroundLinkPrefillsDialerInsteadOfOpeningRoom",
            "blankShareIntentOpensNeitherDialerNorRoom",
            "reopenedRoomAfterCloseIsAgainTheSoleOwnedWebView",
            "roomWebViewClientsBelongToTheHelperOwnerUntilClose",
            "navigationGateSeparatesMainFrameRoomsFromAppleMediaSubframes",
            "rendererDeathReleasesTheOwnedRoomAndHeldMedia",
            "permissionRequestsFromNonAppleOriginsOrWithoutMediaResourcesAreDenied",
            "mainFrameLoadErrorsFailTheRoomAndDenyHeldMediaWhileSubframeErrorsDoNot",
            "rejectedRoomUrlNeverCreatesOrDisplacesTheOwnedRoomWebView",
            "oneRoomWebViewSurvivesRotationAndIsReplacedNotStacked",
            "configurationRebuildKeepsTabAndSheetWiring",
            "themeRebuildKeepsSingleChromeAndCallLinkWiring",
        ),
        "NavigationInsetPaintTest": ("physicalNavigationEdgesStayOnTheirOwnedSideInBothDirections",),
        "VisualProfilesTest": (
            "lightDarkLargeTextAndReducedMotion",
            "rotationActualWindowResizeAndControlFocus",
            "populatedScrollSamplesOnlyBackdropAndKeepsControlsVisible",
            "callBarsUseAndRestoreOwnedAppearance",
            "installedLauncherIconAndOpenRoute",
        ),
    }),
    "launcher": ("installationCheck", {"InstalledLauncherTest": ("installedLogoOpensTheUpdatedApp",)}),
}


def verify_census(output, adb_exit, expected):
    if adb_exit:
        raise ValueError(f"adb exited {adb_exit}")
    if not expected:
        raise ValueError("The required test census must contain at least one test")
    total = len(expected)
    fields = {}
    running = None
    completed = set()
    terminal = []
    summaries = []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line.removeprefix("INSTRUMENTATION_STATUS_CODE: ")
            test = (fields.get("class"), fields.get("test"))
            require_status(total, completed, test, fields, terminal)
            if test not in expected:
                raise ValueError(f"Unexpected test in the instrumentation run: {test}")
            if code == "1" and running is None and test not in completed:
                running = test
            elif code == "0" and running == test:
                completed.add(test)
                running = None
            else:
                raise ValueError(f"Unexpected status {code} for {test}")
            fields.clear()
        elif line.startswith("INSTRUMENTATION_CODE: "):
            terminal.append(line)
        elif line == "FAILURES!!!" or line.startswith((
            "INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED",
            "INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_RESULT: longMsg=",
        )):
            raise ValueError(f"Runner failed: {line}")
        elif match := re.fullmatch(r"OK \((\d+) tests?\)", line):
            summaries.append(int(match[1]))
    if fields or running or terminal != ["INSTRUMENTATION_CODE: -1"] or summaries != [total]:
        raise ValueError("Instrumentation did not report exactly one complete successful run")
    if completed != expected:
        raise ValueError(f"Required tests did not report success: {sorted(expected - completed)}")


def require_status(total, completed, test, fields, terminal):
    if (not all(test) or fields.get("id") != "AndroidJUnitRunner"
            or fields.get("numtests") != str(total) or fields.get("current") != str(len(completed) + 1)
            or terminal):
        raise ValueError(f"Missing or inconsistent test identity for {test}")


def verified_apks(adb):
    def query(*arguments):
        return subprocess.check_output([*adb, "shell", *arguments], text=True, timeout=30).strip()

    receipts = []
    for package, relative in APK_OUTPUTS.items():
        package_path = re.fullmatch(r"package:(/[A-Za-z0-9_./~=-]+\.apk)", query("pm", "path", package))
        if package_path is None:
            raise ValueError(f"Expected a single installed APK for {package}")
        hash_output = query("sha256sum", package_path[1]).split(maxsplit=1)
        built_hash = digest(ROOT / "app/build/outputs/apk" / relative)
        if not hash_output or hash_output[0] != built_hash:
            raise ValueError(f"Installed {package} does not match the current build")
        receipts.append(f"{package} {built_hash}")
    return "\n".join(receipts) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("device")
    parser.add_argument("run_id")
    parser.add_argument("lane", choices=LANES)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9-]+", args.run_id):
        parser.error("Run ID must use lowercase letters, digits, or hyphens")
    fixture, cases = LANES[args.lane]
    expected = {(f"{PACKAGE}.{name}", test) for name, names in cases.items() for test in names}
    classes = ",".join(f"{PACKAGE}.{name}" for name in cases)
    reports = ROOT / "build/reports/device"
    reports.mkdir(parents=True, exist_ok=True)
    run = reports / args.run_id
    run.mkdir(mode=0o700)
    adb = [os.environ.get("ADB", "adb"), "-s", args.device]
    command = [
        *adb, "shell", "am", "instrument", "-w", "-r",
        "-e", fixture, "true", "-e", "evidenceRun", args.run_id, "-e", "class", classes, RUNNER,
    ]
    raw = run / "instrumentation.log"
    try:
        before = verified_apks(adb)
        (run / "apks-before.txt").write_text(before, encoding="utf-8")
        with raw.open("xb") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False, timeout=1200)
        (run / "adb-exit.txt").write_text(f"{result.returncode}\n", encoding="utf-8")
        after = verified_apks(adb)
        (run / "apks-after.txt").write_text(after, encoding="utf-8")
        if after != before:
            raise ValueError("APK identity changed during instrumentation")
        verify_census(raw.read_text(encoding="utf-8"), result.returncode, expected)
    except (OSError, subprocess.SubprocessError, ValueError) as failure:
        print(f"FAILED: {failure}. Reports: {run}")
        return 1
    print(f"PASSED: {len(expected)} tests. Raw output: {raw}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
