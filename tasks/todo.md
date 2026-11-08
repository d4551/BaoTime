# Task list: BlueBubbles FaceTime behaviour → BaoTime

Companion to [`plan.md`](plan.md) and [`audit-resolution.md`](audit-resolution.md). All 14 tasks (`WS-A` through `WS-G`) are implemented and verified in this tree (`./gradlew :app:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`, `python3 scripts/audit_design.py`, and instrumented/device-hub verification on `baotime_audit_20261107`).

Repository commands:

- Build: `./gradlew :app:assembleDebug`
- JVM tests: `./gradlew :app:test`
- Instrumented (gated): see root README (`auditFixture=true` / `installationCheck=true` / `deploymentCheck=true`)

This file is the **BlueBubbles FaceTime ingest** task list, revised 2026-11-08 after Code Auditor REQUEST CHANGES on `29ff359` and reconciled with [`tasks/workstreams/baotime-workstream-addendum.md`](workstreams/baotime-workstream-addendum.md).

Task 2 (lifecycle) **precedes** Task 12 (OkHttp). Tasks 4/5/7 implement Task 2; they are not independent designs.

---

## Task 1: Capture live server contract fixtures

**Description:** Against a real BlueBubbles Server **v1.9.12** (or documented older), record redacted JSON for `GET /api/v1/ping` and `GET /api/v1/server/info` (helper on and off). Record **both** FaceTime event shapes: `ft-call-status-changed` (calling mode, `uuid` + `status_id`) and legacy `incoming-facetime` (JSON string `{ caller, timestamp }`, no uuid). Store under `app/src/test/resources/bluebubbles/` (no passwords, no personal numbers). Confirm `/server/info` still lacks `facetime_calling`.

**Acceptance criteria:**

- [x] Fixtures exist for ping 200, info helper-up, info helper-down (or Private API error body)
- [x] Fixture or comment for `ft-call-status-changed` incoming (`status_id` 4) and disconnected (`status_id` 6)
- [x] Fixture or comment for legacy `incoming-facetime` without uuid
- [x] Each info fixture lists `server_version` actually returned
- [x] No server password or raw FaceTime key material in git

**Verification:**

- [x] Tests pass: N/A (fixtures only) or a tiny parser smoke test if added
- [x] Build succeeds: `./gradlew :app:assembleDebug` still succeeds with fixtures only (**implementation PR**, not this docs PR)
- [x] Manual check: keys vs `GeneralInterface.getServerMetadata` / `PrivateApiFaceTimeStatusHandler` / BaoTime `BlueBubblesClient.checkServerStatus`

**Dependencies:** None

**Files likely touched:**

- `app/src/test/resources/bluebubbles/ping.json`
- `app/src/test/resources/bluebubbles/server-info-helper-on.json`
- `app/src/test/resources/bluebubbles/ft-call-status-changed-incoming.json`
- `app/src/test/resources/bluebubbles/incoming-facetime-legacy.json`

**Estimated scope:** Small: 1-2 files (up to ~4 fixture files)

**Risks / gates:** Without a Mac, use redacted captures; do not invent field names.

---

## Task 2: Authoritative CallLifecycle contract

**Description:** Implement (in a later coding PR) one process-wide `CallLifecycle` store matching [`plan.md` Call lifecycle](plan.md#call-lifecycle-contract). Identity: `callUuid` + `configGeneration` (`AppConfig.connectionRevision`) + `inviteGeneration`. Terminal states, 30s ringing expiry, HTTP cancellation, Activity bind/unbind without socket teardown. This task is the **gate** for Tasks 4–7 and Task 12. Until code exists, treat the plan section as the spec; do not start OkHttp or a second ad-hoc state machine.

**Acceptance criteria:**

- [x] One in-memory store per process; Activity is an observer, not the owner
- [x] `configGeneration` change cancels in-flight answer/leave and drops ringing invites
- [x] Late HTTP for a completed `inviteGeneration` cannot open a WebView or write a joined Recents row
- [x] Decline/hangup while answer is pending cancels the request; if answer already succeeded, best-effort leave; WebView does not load
- [x] `status_id=6` for uuid A does not affect uuid B
- [x] Same-uuid duplicates (both events carry the same non-null uuid) collapse to one invite; NLS URL-only is a **separate** identity from socket uuid (no time/proximity/caller-window attach). Unrelated uuid while NLS overlay is showing replaces the overlay without merging identities (NLS missed/declined on its own; no answer/leave of that URL with the new uuid)
- [x] JVM tests cover the table in the plan (no device required)
- [x] Socket reconnect is **not** treated as event replay (document limitation in code comment citing Socket.IO delivery guarantees)

**Verification:**

- [x] Tests pass: `./gradlew :app:test` with lifecycle unit tests
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: N/A beyond tests until Tasks 4–7 wire transports

**Dependencies:** Task 1 preferred (payload shapes)

**Files likely touched:**

- `app/src/main/java/com/baotime/app/call/CallLifecycle.kt` (new)
- `app/src/test/java/com/baotime/app/call/CallLifecycleTest.kt` (new)
- `app/src/main/java/com/baotime/app/MainActivity.kt` (bind/unbind only, implementation PR)

**Estimated scope:** Medium: 3-5 files

**Risks / gates:** **HARD GATE.** No merge of Tasks 4–7 or 12 without this store. Do not disconnect the socket from `MainActivity.onDestroy`.

---

## Task 3: Gate FaceTime on helper, Private API, and server version

**Description:** Use `/server/info` fields BaoTime already parses (`privateApiEnabled`, `helperConnected`, `serverVersion`). Refuse `createFaceTimeSession` (and later answer/leave) when helper/Private API is false, or version is below the negotiated minimum (default **1.9.0**, target **1.9.12**). This gates **this Mac’s capability**, not callee availability (Task 8). No Socket yet. Keep `HttpURLConnection`.

**Acceptance criteria:**

- [x] Create is not attempted when helper or private_api is false
- [x] User-visible banner uses existing or new **fixed** copy (no interpolated diagnostics)
- [x] Version parse is defensive; unknown version ≠ crash

**Verification:**

- [x] Tests pass: unit tests with fixtures from Task 1
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: with helper off, New Call shows failure without hanging 25s

**Dependencies:** Task 1; may proceed in parallel with Task 2

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/baotime/app/network/ServerCapabilityTest.kt`

**Estimated scope:** Small: 1-2 files (medium if strings + MainActivity banner wiring)

---

## Checkpoint: After Tasks 1-3 (Foundation)

- [x] Lifecycle unit tests exist (Task 2)
- [x] `./gradlew :app:test` and `./gradlew :app:assembleDebug` pass in the **implementation** PR
- [x] Outgoing create-link still works on a helper-connected Mac
- [x] **No OkHttp dependency yet** (verified prior to Task 12 transport migration)
- [x] Review with human before Socket.IO

---

## Task 4: Socket.IO 4 connection Flow (process-wide)

**Description:** Add one `BlueBubblesSocket` using Socket.IO Java **2.1.2** (Socket.IO 4). Connect with query `guid=<password>`, transports websocket+polling, force-new engine (BB `socket_service.dart` @ `ff8bfd5`). Own it in the **process** (Application or equivalent), not `repeatOnLifecycle(DESTROYED)`. Collect UI from Activity `STARTED`; **do not** disconnect in `MainActivity.onDestroy`. On `configGeneration` change, disconnect and reconnect with the new connection. HTTP 401 on ping marks bad password. Backoff reconnect; **reconnect does not replay** missed FaceTime events (https://socket.io/docs/v4/delivery-guarantees/ ; server `emitMessage` is live emit only).

**Acceptance criteria:**

- [x] One socket instance per process per `configGeneration`
- [x] Auth failure distinct from offline
- [x] Activity destroy does **not** drop the socket
- [x] Config save drops the old socket and in-flight CallLifecycle jobs
- [x] Code/comments state: missed events while disconnected are lost unless NLS/FCM delivered them
- [x] No password in logs or crash reports

**Verification:**

- [x] Tests pass: wrapper tests (connect state machine); do not require a live server
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: Settings connected ⇒ socket connected; rotate/recreate Activity ⇒ still connected; wrong password ⇒ authFailed

**Dependencies:** Task 2, Task 3

**Files likely touched:**

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/baotime/app/network/BlueBubblesSocket.kt`
- `app/src/main/java/com/baotime/app/BaoTimeApp.kt` (or existing Application if added)
- `app/src/main/java/com/baotime/app/MainActivity.kt` (observe only)

**Estimated scope:** Medium: 3-5 files

**Risks / gates:** Adding `io.socket:socket.io-client` is a third-party library — complete Task 14 licence row in the same implementation PR. Do not add an FGS here.

---

## Task 5: Ingress + dedup → existing incoming UI

**Description:** Subscribe to `ft-call-status-changed` (primary; `status_id==4` + `uuid`) and `incoming-facetime` (legacy; no uuid). Drive CallLifecycle then `IncomingCallController` (extended to carry uuid **or** URL). Do not open WebView. Keep NLS URL path. Dedup per Task 2. Official Android BB listens to `incoming-facetime` on **FCM**, not on the Android socket (`socket_service.dart` web/desktop only); BaoTime **does** listen on the socket while the process is alive — that is intentional, not a copy of the Android FCM path.

**Acceptance criteria:**

- [x] Calling-mode event shows Accept/Decline without Notification Listener
- [x] Duplicate **same uuid** from transports that **include that uuid** (socket retry, FCM payload with uuid) does not stack overlays. URL-only NLS is **not** a uuid duplicate
- [x] Legacy event without uuid does **not** call `/facetime/answer`; fail-safe documented (UI-only or ignore)
- [x] NLS URL path still functions if socket did not fire
- [x] NLS URL-only and a later socket uuid stay **separate** unless a documented matcher uses a shared identifier both events actually carry (socket calling payload has uuid+address, no room URL). **Never** attach an unrelated uuid by time, proximity, or caller-window. Prefer uuid for REST answer
- [x] Unrelated uuid while an NLS invitation is displayed: do not merge; replace the overlay with the uuid invite; NLS terminates as its own missed/declined identity; do not POST answer/leave using that uuid for the NLS URL

**Verification:**

- [x] Tests pass: parser tests from Task 1 fixtures; dedup tests
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: incoming FaceTime to the Mac while BaoTime is foregrounded shows one overlay

**Dependencies:** Task 4

**Files likely touched:**

- `app/src/main/java/com/baotime/app/routing/UrlRouter.kt` (or `IncomingFaceTimeEvent`)
- `app/src/main/java/com/baotime/app/call/IncomingCallController.kt`
- `app/src/main/java/com/baotime/app/call/CallLifecycle.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/test/java/com/baotime/app/network/IncomingFaceTimePayloadTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 6: POST /api/v1/facetime/answer/:call_uuid then WebView

**Description:** On Accept with a uuid, `POST /api/v1/facetime/answer/{call_uuid}` with `{}` (Flutter `FaceTimeApi.answer` @ `ff8bfd5`). Expect `{ data: { link } }` HTTPS FaceTime URL. Feed existing `openCallSession` / WebView gate **only if** CallLifecycle still shows that `inviteGeneration` in `Answering`. Timeouts may exceed 25s (Mac answer + 4s Sonoma delay + generateLink + admit). Cancel on Decline/hangup/config bump. Do not open Chrome. Do not call Apple private APIs. URL-only Accept keeps today’s join-link path (no answer REST).

**Acceptance criteria:**

- [x] Accept with valid uuid and matching generations opens the returned link in the existing room overlay
- [x] Non-room or empty link uses `ServerFailure.EmptyLink` / existing toast
- [x] Private API / helper errors map to existing generic or new fixed string
- [x] Link is recorded in Recents as incoming **only** if the invite was not already terminal
- [x] Hangup/Decline during POST: no WebView; leave if uuid answered on Mac
- [x] Late 200 after Decline/config: ignored

**Verification:**

- [x] Tests pass: MockWebServer answer success/empty/401; **cancellation** test (do not apply late body)
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: Accept joins Apple web room; camera/mic still via existing Continue path

**Dependencies:** Task 5, Task 3, Task 2

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/call/CallLifecycle.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/test/java/com/baotime/app/network/FaceTimeAnswerTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 4-6 (Incoming)

- [x] Foreground incoming via `ft-call-status-changed` works end-to-end
- [x] Lifecycle cancellation/generation tests still pass
- [x] Outgoing create-link still works
- [x] NLS fallback still present
- [x] Review with human before leave/availability/push/OkHttp

---

## Task 7: leave + ft-call-status-changed (including hangup-while-pending)

**Description:** Decline or missed: `POST /api/v1/facetime/leave/:call_uuid` best-effort when uuid present. Listen to `ft-call-status-changed` (`FaceTimeSessionStatus`: unknown=0, answered=1, outgoing=3, incoming=4, disconnected=6). Dismiss overlay on disconnected **only for the active uuid**. Closing the WebView does **not** need leave for outgoing minted links (Mac already left after `admitAndLeave`); only incoming uuids. This task **closes** the hangup-while-answer-pending path specified in Task 2.

**Acceptance criteria:**

- [x] Decline records TYPE_MISSED and attempts leave when uuid present
- [x] Disconnect event for the **active** uuid dismisses incoming UI
- [x] Disconnect for another uuid is ignored
- [x] Hangup while Answering: cancel HTTP + leave if needed + no WebView
- [x] Leave failures do not crash; uuid may be logged (opaque)

**Verification:**

- [x] Tests pass: status enum mapping; leave MockWebServer; wrong-uuid ignored
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: Decline while caller still ringing; overlay gone; Recents unanswered

**Dependencies:** Task 6

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/network/BlueBubblesSocket.kt`
- `app/src/main/java/com/baotime/app/call/IncomingCallController.kt`
- `app/src/main/java/com/baotime/app/call/CallLifecycle.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 8: FaceTime availability **advisory** on New Call

**Description:** When the user typed a handle (not a raw FaceTime URL), **optionally** call `GET /api/v1/handle/availability/facetime?address=` and show a **non-blocking** advisory if `available` is false. **Do not** skip `POST /api/v1/facetime/session` on that result. Server `FaceTimeRouter.newSession` / `FaceTimeInterface.create()` take **no callee**; BaoTime already mints a generic shareable link. Helper-down errors from `checkPrivateApiStatus()` inside `HandleInterface.getFacetimeAvailability` fall back to silence or Task 3’s Mac-capability banner — they still must not uniquely block generic create. A human may later approve a product rule to block; that is **not** the default.

**Acceptance criteria:**

- [x] Unavailable handle still allows `/facetime/session` unless a recorded human product rule says otherwise (default: allow)
- [x] Raw FaceTime URL join path unchanged
- [x] Create with empty/generic handle still works
- [x] Comment cites `facetimeRouter.ts` @ `76d2b2e` and this advisory rule

**Verification:**

- [x] Tests pass: MockWebServer true/false/500; create still invoked on false
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: known FaceTime-capable handle vs random email still mints a link

**Dependencies:** Task 3

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/baotime/app/network/FaceTimeAvailabilityTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 9: Tests, logs, no secret leakage

**Description:** MockWebServer coverage for info/create/answer/leave/availability. Socket payload parsers. Lifecycle generation/cancellation tests if not already in Task 2. Interceptors (even on `HttpURLConnection` wrappers) redact auth query keys. Do not add instrumented Keystore tests unless `auditFixture` harness is used. Optional: `Log` tags `BaoTime/Http` `BaoTime/Socket`.

**Acceptance criteria:**

- [x] JVM tests cover success, helper-missing, and **late/cancelled answer**
- [x] `./gradlew :app:test` is the default CI-local command
- [x] Logcat sample of a create/answer contains no password

**Verification:**

- [x] Tests pass: `./gradlew :app:test`
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: logcat during one call; no `guid=`/`password=` values

**Dependencies:** Tasks 6–8

**Files likely touched:**

- `app/src/test/java/com/baotime/app/network/`
- `app/src/test/java/com/baotime/app/call/`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 7-9 (Hygiene)

- [x] All JVM tests pass in the implementation PR
- [x] Decline/leave behave on device
- [x] Availability is advisory
- [x] Human decides Task 10 / 12 / 13 / 14

---

## Task 10: Background incoming design (no code unless approved)

**Description:** Write a short addendum in `plan.md` choosing **foreground-only (default)** / **FCM** / **UnifiedPush** / **FGS**. Do **not** assume `remoteMessaging` or `dataSync` is eligible for a FaceTime invite socket. If FGS is chosen, the note must include: exact `foregroundServiceType`, manifest permission, how `startForeground` is entered, timeout (`dataSync` 6h/24h on API 35+; `shortService` ~3 min), full-screen intent (`USE_FULL_SCREEN_INTENT` / `canUseFullScreenIntent` / Play calling-app policy), and sideload vs Play. Official BB uses `remoteMessaging` for `SocketIOForegroundService` — cite it as **their** iMessage client, not BaoTime proof. Webhooks cannot hit a phone.

**Acceptance criteria:**

- [x] Written decision with type/start/permission/timeout/FSI/distribution **or** an explicit foreground-only boundary
- [x] No production code in this task
- [x] Does not claim Play eligibility without a policy case

**Verification:**

- [x] Tests pass: N/A (written policy decision in `tasks/plan.md`)
- [x] Build succeeds: N/A (written policy decision in `tasks/plan.md`)
- [x] Manual check: human approval recorded (`plan.md` Task 10 & Task 11 section)

**Dependencies:** Checkpoint after 7–9

**Files likely touched:**

- `tasks/plan.md` (decision note only)

**Estimated scope:** Small: 1-2 files

---

## Task 11: Optional FCM or UnifiedPush (only if Task 10 says so)

**Description:** If approved, register with server FCM endpoints **or** UnifiedPush, mapping the FaceTime push payload into Task 5 ingress. Do not copy `CreateIncomingFaceTimeNotification.kt`. Clean-room NotificationCompat call category using BaoTime resources.

**Process states to test and document separately:**

| State | Expected | Test must not claim | Verified result (`emulator-5554` / `baotime_audit_20261107`) |
| --- | --- | --- | --- |
| Foreground | Payload → same CallLifecycle | — | **Verified (`PASS`)**: `org.unifiedpush.android.connector.MESSAGE` (`IncomingPushReceiver`) parses `ft-call-status-changed` (`uuid`, `status_id=4`) via `FaceTimePushHandler`, drives `CallLifecycle` (`InviteSource.Fcm`), opens the in-app incoming overlay (`Maya Chen (UnifiedPush)`), and tapping `Accept` invokes `POST /api/v1/facetime/answer/:call_uuid` (Task 6) and opens the gated WebView room. |
| Background | Delivered (priority/OEM caveats) | Universal OEM success | **Verified (`PASS`, OEM caveats noted)**: With BaoTime backgrounded via `KEYCODE_HOME`, `IncomingPushReceiver` routes the `uuid` into `CallLifecycle` and posts a `NotificationCompat.CATEGORY_CALL` (`PRIORITY_MAX`) notification (`Liam Patel (Background Push) — tap to answer in BaoTime`). |
| Swipe-away Recents | Often delivered; OEM may equal force-stop | Same as force-stop | **Verified (`PASS` on AOSP AVD, OEM caveats noted)**: Swiping away from Recents on AOSP Android 15 keeps `stopped=false`; broadcast wakes `IncomingPushReceiver` and posts the `CATEGORY_CALL` notification (some OEMs treat swipe-away as force-stop). |
| OS process-kill | FCM may restart; socket will not replay | Socket catch-up | **Verified (`PASS`, no socket replay claimed)**: After `adb shell am kill com.baotime.app` (`pidof` empty), `org.unifiedpush.android.connector.MESSAGE` starts a new process (`PID 7868`), transitions `CallLifecycle` to `Ringing(InviteSource.Fcm)`, and posts the `CATEGORY_CALL` notification (`Noah Kim (Process Kill Push)`). |
| **Force-stop (Settings)** | **No FCM until the user reopens the app** | “Killed-app incoming works” | **Verified as LIMITATION + RECOVERY**: After `adb shell am force-stop com.baotime.app`, `dumpsys package com.baotime.app` reports `stopped=true`; standard broadcasts with `FLAG_EXCLUDE_STOPPED_PACKAGES` (`flg=0x400010`) do **not** wake `com.baotime.app` (`pidof` remains empty). Recovery requires opening BaoTime once (`am start -n com.baotime.app/.MainActivity`), which restores `stopped=false`. |
| Reboot | After FCM registration; still fails if later force-stopped | Immortal delivery | **Verified (`PASS`, force-stop caveat retained)**: Endpoint registration via `org.unifiedpush.android.connector.NEW_ENDPOINT` calls `POST /api/v1/fcm/device`; after process reset (`am kill`), `org.unifiedpush.android.connector.MESSAGE` wakes a fresh process (`PID 1942`) and posts the `CATEGORY_CALL` notification when `stopped=false` (still blocked if `stopped=true` after force-stop). |

https://firebase.google.com/docs/cloud-messaging/flutter/receive — Android force-quit must be manually reopened.

**Acceptance criteria:**

- [x] Same uuid answer path as Task 6
- [x] Force-stop test **documents limitation + recovery** (open BaoTime once) and is **not** a pass criterion for delivery
- [x] No Apache-2.0 copy of BB Kotlin without Task 14 + LICENSE

**Verification:**

- [x] Tests pass: payload parser tests
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: background/swipe/reboot as available; force-stop recorded as **limitation**, not success

**Dependencies:** Task 10 approval, Task 6, Task 2

**Files likely touched:**

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/baotime/app/network/FaceTimePushHandler.kt`
- `app/src/main/java/com/baotime/app/call/IncomingPushReceiver.kt`
- `app/src/test/java/com/baotime/app/network/FaceTimePushHandlerTest.kt`

**Estimated scope:** Medium: 3-5 files (stop and split if larger)

---

## Task 12: OkHttp 5.5.1 behind BlueBubblesClient (after lifecycle)

**Description:** Replace `HttpURLConnection` in `BlueBubblesClient` with OkHttp **5.5.1** (stable). Keep the façade: `checkServerStatus`, `createFaceTimeSession`, `fetchContacts`, plus answer/leave from earlier tasks. Auth remains query `password`. Timeouts stay 6s connect / 25s read unless answer needs longer. Map OkHttp cancel to CallLifecycle cancellation (do not invent a second policy). **Blocked until Task 2 exists.**

**Acceptance criteria:**

- [x] Catalog pin `okhttp = "5.5.1"`; no alpha OkHttp
- [x] Existing create/info/answer/leave behaviour preserved
- [x] Password never logged (interceptor redacts `password`/`guid`/`token`)
- [x] Cancelled CallLifecycle jobs abort the OkHttp call

**Verification:**

- [x] Tests pass: `./gradlew :app:test`
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: Settings still shows Saved · Connected against the same Mac

**Dependencies:** Task 2 **required**; Task 7 preferred so answer/leave exist to migrate

**Files likely touched:**

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/test/java/com/baotime/app/network/BlueBubblesClientTest.kt`

**Estimated scope:** Medium: 3-5 files

**Risks / gates:** Task 14 licence row for OkHttp in the same implementation PR.

---

## Task 13: WebView compatibility matrix

**Description:** Apple documents FaceTime-on-the-web on Android via **Chrome or Edge**, not WebView (https://support.apple.com/en-us/109364, published 2026-05-29). Keep BaoTime’s gated WebView as a product choice and record the compatibility matrix without claiming Apple-supported WebView certification.

| Device / OS / WebView | Admit to waiting room | Camera | Microphone | In-room navigation / leave | Network reconnect | PiP |
| --- | --- | --- | --- | --- | --- | --- |
| `baotime_audit_20261107` AVD (`emulator-5554`, Android 15 / API 35, `arm64-v8a`) — verified via `/android-cli` & `/android-device-hub` + `RoomOwnershipTest` | **PASS (room gate) / NOTE (live admit)**: Gated WebView room opens for `POST /facetime/session`, `POST /facetime/answer/:uuid`, and UnifiedPush `Accept` (`https://facetime.apple.com/join#...`); live waiting-room admission requires an active macOS FaceTime host | **PASS**: `Continue`-before-media permission gate verified in `RoomOwnershipTest#permissionCancellationAndDisposalCannotLeakAcrossRooms` | **PASS**: `Continue`-before-media permission gate + native `CallAudioRouter` speaker ownership verified in `RoomOwnershipTest` | **PASS**: Verified in `/android-device-hub` (`Close room` `#4`, `Room options` `#5` overflow menu: `Share`, `Speaker`, `Picture in picture`, `Reload the call room`, HTTPS canonical host gate) | **PASS**: Verified (`BlueBubblesSocket` WebSocket reconnect + `Reload the call room` in overflow menu + `CallLifecycle`) | **PASS**: Entered `Picture in picture` (`9:16` floating PiP window `#7` over Android 15 home screen verified in `device_hub_webview_pip.png` and `NativeJourneysTest#onlyTheVisibleRoomAutoEntersAfterRotationAndResize`) |
| Apple `109364` Android WebView support boundary (`DeviceConnectionCheckTest` on Xiaomi verified Tailscale HTTPS `/api/v1/server/info` reachability; Apple officially supports Chrome/Edge on Android) | **NOTE (unclaimed per Apple `109364`)**: Apple officially names Chrome or Edge on Android (`109364`); README retains the explicit WebView compatibility limitation and does not claim Apple-certified WebView admission | **NOTE (unclaimed per Apple `109364`)**: Native `Continue`-before-media permission gate enforced by `CallSessionController`; Apple WebRTC media inside Android WebView is not claimed as Apple-certified | **NOTE (unclaimed per Apple `109364`)**: Native `Continue`-before-media permission gate + `CallAudioRouter` enforced; Apple WebRTC audio inside Android WebView is not claimed as Apple-certified | **PASS (native gate) / NOTE (Apple `109364`)**: Canonical HTTPS `facetime.apple.com` main-frame and `apple.com` / `icloud.com` subframe gate enforced in `CallSessionController` | **PASS (native gate) / NOTE (Apple `109364`)**: `BlueBubblesSocket` reconnect + `Reload the call room` action verified; socket reconnect does not replay missed call events | **PASS (native gate)**: Native Android 12+ `9:16` `PictureInPictureParams` owned by `MainActivity` and `CallSessionController` |

**Acceptance criteria:**

- [x] Matrix filled with pass/fail/notes per cell
- [x] README Limitations still state Apple names Chrome/Edge
- [x] Failure of a cell does not get explained as “Apple-supported WebView”

**Verification:**

- [x] Tests pass: `RoomOwnershipTest` (6/6) and `NativeJourneysTest` (4/4) on `baotime_audit_20261107`
- [x] Build succeeds: `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
- [x] Manual check: verified via `/android-cli` and `/android-device-hub` alongside README Apple `109364` limitation

**Dependencies:** None for planning; implementation of rooms already exists

**Files likely touched:**

- `tasks/plan.md` (results addendum)
- `README.md` (limitations only if results change the claim)

**Estimated scope:** Small: 1-2 files (plus device time)

---

## Task 14: Licence and NOTICE inventory before any copy

**Description:** Before copying any BlueBubbles file or landing OkHttp/Socket.IO/FCM in an implementation PR, record: SPDX of each artifact, whether a NOTICE exists (BB app/server: **none** at pins), Apache-2.0 attribution steps if BB source is copied, and transitive licences of new Gradle deps. Do **not** describe the work as a certified legal clean-room.

**Acceptance criteria:**

- [x] Table in `tasks/plan.md` or a `NOTICE`/`LICENSES` file listing added deps
- [x] If any BB source is copied: BaoTime `LICENSE` + attribution **in that same PR**
- [x] README does not claim certified clean-room

**Verification:**

- [x] Tests pass: N/A (licence and provenance inventory in `NOTICE` and `tasks/plan.md`)
- [x] Build succeeds: `./gradlew :app:assembleDebug`
- [x] Manual check: diff review for Flutter/`MethodChannel` types

**Dependencies:** Before Tasks 4, 11, or 12 land libraries; before any BB copy

**Files likely touched:**

- `LICENSE` / `NOTICE` (only if required)
- `tasks/plan.md`

**Estimated scope:** Small: 1-2 files

---

## Checkpoint: Complete

- [x] M2 incoming works without becoming an iMessage app
- [x] No Flutter/Dart sources in the tree
- [x] README FaceTime/incoming sections updated in a **later** docs task if behaviour changed (not solely this planning PR)
- [x] Human reviewed licence, helper, FGS/push, WebView risk
- [x] Ready for implementation PRs **separate** from this README/planning PR if desired

---

## Explicitly not tasks

- Porting `lib/` Dart or GetX/ObjectBox
- Embedding Flutter engine
- Chrome Custom Tabs as the **required** room (WebView stays unless Task 13 fails badly and a human switches)
- Apple FaceTime native / WebRTC reverse engineering
- iMessage send/react/typing/Find My
- EncryptedSharedPreferences migration
- Migrating secrets to DataStore-tink **in FaceTime slices** (tink 1.3.0 is **stable**; skip on migration/scope, not alpha)
- Lifecycle 2.12.0-alpha05
- OkHttp 5.0.0-alpha.*
- OkHttp **before** Task 2
- Treating socket reconnect as replay
- Claiming FCM after Settings force-stop
- Declaring `remoteMessaging` or `dataSync` FGS without Task 10’s written policy case
- Blocking `POST /facetime/session` on handle availability (unless a human product rule is recorded)
- Certified legal clean-room claims
