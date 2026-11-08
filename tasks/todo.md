# Task list: BlueBubbles FaceTime behaviour → BaoTime

Companion to [`plan.md`](plan.md) and [`audit-resolution.md`](audit-resolution.md). **Planning only.** Check boxes when a future **implementation** session finishes the task. This documentation PR does not run app builds or tests.

Repository commands (for later implementation PRs, not this one):

- Build: `./gradlew :app:assembleDebug`
- JVM tests: `./gradlew :app:test`
- Instrumented (gated): see root README; not required for Tasks 1–9 unless noted

Do not overwrite this file if a different plan’s tasks are still unchecked. This file is the **BlueBubbles FaceTime ingest** plan, revised 2026-11-08 after Code Auditor REQUEST CHANGES on `29ff359`.

Task 2 (lifecycle) **precedes** Task 12 (OkHttp). Tasks 4/5/7 implement Task 2; they are not independent designs.

---

## Task 1: Capture live server contract fixtures

**Description:** Against a real BlueBubbles Server **v1.9.12** (or documented older), record redacted JSON for `GET /api/v1/ping` and `GET /api/v1/server/info` (helper on and off). Record **both** FaceTime event shapes: `ft-call-status-changed` (calling mode, `uuid` + `status_id`) and legacy `incoming-facetime` (JSON string `{ caller, timestamp }`, no uuid). Store under `app/src/test/resources/bluebubbles/` (no passwords, no personal numbers). Confirm `/server/info` still lacks `facetime_calling`.

**Acceptance criteria:**

- [ ] Fixtures exist for ping 200, info helper-up, info helper-down (or Private API error body)
- [ ] Fixture or comment for `ft-call-status-changed` incoming (`status_id` 4) and disconnected (`status_id` 6)
- [ ] Fixture or comment for legacy `incoming-facetime` without uuid
- [ ] Each info fixture lists `server_version` actually returned
- [ ] No server password or raw FaceTime key material in git

**Verification:**

- [ ] Tests pass: N/A (fixtures only) or a tiny parser smoke test if added
- [ ] Build succeeds: `./gradlew :app:assembleDebug` still succeeds with fixtures only (**implementation PR**, not this docs PR)
- [ ] Manual check: keys vs `GeneralInterface.getServerMetadata` / `PrivateApiFaceTimeStatusHandler` / BaoTime `BlueBubblesClient.checkServerStatus`

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

- [ ] One in-memory store per process; Activity is an observer, not the owner
- [ ] `configGeneration` change cancels in-flight answer/leave and drops ringing invites
- [ ] Late HTTP for a completed `inviteGeneration` cannot open a WebView or write a joined Recents row
- [ ] Decline/hangup while answer is pending cancels the request; if answer already succeeded, best-effort leave; WebView does not load
- [ ] `status_id=6` for uuid A does not affect uuid B
- [ ] Dedup key: uuid, else FaceTime URL, else weak legacy caller+time
- [ ] JVM tests cover the table in the plan (no device required)
- [ ] Socket reconnect is **not** treated as event replay (document limitation in code comment citing Socket.IO delivery guarantees)

**Verification:**

- [ ] Tests pass: `./gradlew :app:test` with lifecycle unit tests
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: N/A beyond tests until Tasks 4–7 wire transports

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

- [ ] Create is not attempted when helper or private_api is false
- [ ] User-visible banner uses existing or new **fixed** copy (no interpolated diagnostics)
- [ ] Version parse is defensive; unknown version ≠ crash

**Verification:**

- [ ] Tests pass: unit tests with fixtures from Task 1
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: with helper off, New Call shows failure without hanging 25s

**Dependencies:** Task 1; may proceed in parallel with Task 2

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/baotime/app/network/ServerCapabilityTest.kt`

**Estimated scope:** Small: 1-2 files (medium if strings + MainActivity banner wiring)

---

## Checkpoint: After Tasks 1-3 (Foundation)

- [ ] Lifecycle unit tests exist (Task 2)
- [ ] `./gradlew :app:test` and `./gradlew :app:assembleDebug` pass in the **implementation** PR
- [ ] Outgoing create-link still works on a helper-connected Mac
- [ ] **No OkHttp dependency yet**
- [ ] Review with human before Socket.IO

---

## Task 4: Socket.IO 4 connection Flow (process-wide)

**Description:** Add one `BlueBubblesSocket` using Socket.IO Java **2.1.2** (Socket.IO 4). Connect with query `guid=<password>`, transports websocket+polling, force-new engine (BB `socket_service.dart` @ `ff8bfd5`). Own it in the **process** (Application or equivalent), not `repeatOnLifecycle(DESTROYED)`. Collect UI from Activity `STARTED`; **do not** disconnect in `MainActivity.onDestroy`. On `configGeneration` change, disconnect and reconnect with the new connection. HTTP 401 on ping marks bad password. Backoff reconnect; **reconnect does not replay** missed FaceTime events (https://socket.io/docs/v4/delivery-guarantees/ ; server `emitMessage` is live emit only).

**Acceptance criteria:**

- [ ] One socket instance per process per `configGeneration`
- [ ] Auth failure distinct from offline
- [ ] Activity destroy does **not** drop the socket
- [ ] Config save drops the old socket and in-flight CallLifecycle jobs
- [ ] Code/comments state: missed events while disconnected are lost unless NLS/FCM delivered them
- [ ] No password in logs or crash reports

**Verification:**

- [ ] Tests pass: wrapper tests (connect state machine); do not require a live server
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Settings connected ⇒ socket connected; rotate/recreate Activity ⇒ still connected; wrong password ⇒ authFailed

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

- [ ] Calling-mode event shows Accept/Decline without Notification Listener
- [ ] Duplicate uuid (socket retried, NLS, later FCM) does not stack overlays
- [ ] Legacy event without uuid does **not** call `/facetime/answer`; fail-safe documented (UI-only or ignore)
- [ ] NLS URL path still functions if socket did not fire
- [ ] NLS URL-only invite **upgrades** if a later uuid event matches the same caller window only when a documented matcher exists; otherwise keep them separate (prefer uuid)

**Verification:**

- [ ] Tests pass: parser tests from Task 1 fixtures; dedup tests
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: incoming FaceTime to the Mac while BaoTime is foregrounded shows one overlay

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

- [ ] Accept with valid uuid and matching generations opens the returned link in the existing room overlay
- [ ] Non-room or empty link uses `ServerFailure.EmptyLink` / existing toast
- [ ] Private API / helper errors map to existing generic or new fixed string
- [ ] Link is recorded in Recents as incoming **only** if the invite was not already terminal
- [ ] Hangup/Decline during POST: no WebView; leave if uuid answered on Mac
- [ ] Late 200 after Decline/config: ignored

**Verification:**

- [ ] Tests pass: MockWebServer answer success/empty/401; **cancellation** test (do not apply late body)
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Accept joins Apple web room; camera/mic still via existing Continue path

**Dependencies:** Task 5, Task 3, Task 2

**Files likely touched:**

- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/call/CallLifecycle.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/test/java/com/baotime/app/network/FaceTimeAnswerTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 4-6 (Incoming)

- [ ] Foreground incoming via `ft-call-status-changed` works end-to-end
- [ ] Lifecycle cancellation/generation tests still pass
- [ ] Outgoing create-link still works
- [ ] NLS fallback still present
- [ ] Review with human before leave/availability/push/OkHttp

---

## Task 7: leave + ft-call-status-changed (including hangup-while-pending)

**Description:** Decline or missed: `POST /api/v1/facetime/leave/:call_uuid` best-effort when uuid present. Listen to `ft-call-status-changed` (`FaceTimeSessionStatus`: unknown=0, answered=1, outgoing=3, incoming=4, disconnected=6). Dismiss overlay on disconnected **only for the active uuid**. Closing the WebView does **not** need leave for outgoing minted links (Mac already left after `admitAndLeave`); only incoming uuids. This task **closes** the hangup-while-answer-pending path specified in Task 2.

**Acceptance criteria:**

- [ ] Decline records TYPE_MISSED and attempts leave when uuid present
- [ ] Disconnect event for the **active** uuid dismisses incoming UI
- [ ] Disconnect for another uuid is ignored
- [ ] Hangup while Answering: cancel HTTP + leave if needed + no WebView
- [ ] Leave failures do not crash; uuid may be logged (opaque)

**Verification:**

- [ ] Tests pass: status enum mapping; leave MockWebServer; wrong-uuid ignored
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Decline while caller still ringing; overlay gone; Recents unanswered

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

- [ ] Unavailable handle still allows `/facetime/session` unless a recorded human product rule says otherwise (default: allow)
- [ ] Raw FaceTime URL join path unchanged
- [ ] Create with empty/generic handle still works
- [ ] Comment cites `facetimeRouter.ts` @ `76d2b2e` and this advisory rule

**Verification:**

- [ ] Tests pass: MockWebServer true/false/500; create still invoked on false
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: known FaceTime-capable handle vs random email still mints a link

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

- [ ] JVM tests cover success, helper-missing, and **late/cancelled answer**
- [ ] `./gradlew :app:test` is the default CI-local command
- [ ] Logcat sample of a create/answer contains no password

**Verification:**

- [ ] Tests pass: `./gradlew :app:test`
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: logcat during one call; no `guid=`/`password=` values

**Dependencies:** Tasks 6–8

**Files likely touched:**

- `app/src/test/java/com/baotime/app/network/`
- `app/src/test/java/com/baotime/app/call/`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 7-9 (Hygiene)

- [ ] All JVM tests pass in the implementation PR
- [ ] Decline/leave behave on device
- [ ] Availability is advisory
- [ ] Human decides Task 10 / 12 / 13 / 14

---

## Task 10: Background incoming design (no code unless approved)

**Description:** Write a short addendum in `plan.md` choosing **foreground-only (default)** / **FCM** / **UnifiedPush** / **FGS**. Do **not** assume `remoteMessaging` or `dataSync` is eligible for a FaceTime invite socket. If FGS is chosen, the note must include: exact `foregroundServiceType`, manifest permission, how `startForeground` is entered, timeout (`dataSync` 6h/24h on API 35+; `shortService` ~3 min), full-screen intent (`USE_FULL_SCREEN_INTENT` / `canUseFullScreenIntent` / Play calling-app policy), and sideload vs Play. Official BB uses `remoteMessaging` for `SocketIOForegroundService` — cite it as **their** iMessage client, not BaoTime proof. Webhooks cannot hit a phone.

**Acceptance criteria:**

- [ ] Written decision with type/start/permission/timeout/FSI/distribution **or** an explicit foreground-only boundary
- [ ] No production code in this task
- [ ] Does not claim Play eligibility without a policy case

**Verification:**

- [ ] Tests pass: N/A
- [ ] Build succeeds: N/A
- [ ] Manual check: human approval recorded

**Dependencies:** Checkpoint after 7–9

**Files likely touched:**

- `tasks/plan.md` (decision note only)

**Estimated scope:** Small: 1-2 files

---

## Task 11: Optional FCM or UnifiedPush (only if Task 10 says so)

**Description:** If approved, register with server FCM endpoints **or** UnifiedPush, mapping the FaceTime push payload into Task 5 ingress. Do not copy `CreateIncomingFaceTimeNotification.kt`. Clean-room NotificationCompat call category using BaoTime resources.

**Process states to test and document separately:**

| State | Expected | Test must not claim |
| --- | --- | --- |
| Foreground | Payload → same CallLifecycle | — |
| Background | Delivered (priority/OEM caveats) | Universal OEM success |
| Swipe-away Recents | Often delivered; OEM may equal force-stop | Same as force-stop |
| OS process-kill | FCM may restart; socket will not replay | Socket catch-up |
| **Force-stop (Settings)** | **No FCM until the user reopens the app** | “Killed-app incoming works” |
| Reboot | After FCM registration; still fails if later force-stopped | Immortal delivery |

https://firebase.google.com/docs/cloud-messaging/flutter/receive — Android force-quit must be manually reopened.

**Acceptance criteria:**

- [ ] Same uuid answer path as Task 6
- [ ] Force-stop test **documents limitation + recovery** (open BaoTime once) and is **not** a pass criterion for delivery
- [ ] No Apache-2.0 copy of BB Kotlin without Task 14 + LICENSE

**Verification:**

- [ ] Tests pass: payload parser tests
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: background/swipe/reboot as available; force-stop recorded as **limitation**, not success

**Dependencies:** Task 10 approval, Task 6, Task 2

**Files likely touched:**

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/baotime/app/network/` (new push handler)
- `app/src/main/java/com/baotime/app/call/`

**Estimated scope:** Medium: 3-5 files (stop and split if larger)

---

## Task 12: OkHttp 5.5.1 behind BlueBubblesClient (after lifecycle)

**Description:** Replace `HttpURLConnection` in `BlueBubblesClient` with OkHttp **5.5.1** (stable). Keep the façade: `checkServerStatus`, `createFaceTimeSession`, `fetchContacts`, plus answer/leave from earlier tasks. Auth remains query `password`. Timeouts stay 6s connect / 25s read unless answer needs longer. Map OkHttp cancel to CallLifecycle cancellation (do not invent a second policy). **Blocked until Task 2 exists.**

**Acceptance criteria:**

- [ ] Catalog pin `okhttp = "5.5.1"`; no alpha OkHttp
- [ ] Existing create/info/answer/leave behaviour preserved
- [ ] Password never logged (interceptor redacts `password`/`guid`/`token`)
- [ ] Cancelled CallLifecycle jobs abort the OkHttp call

**Verification:**

- [ ] Tests pass: `./gradlew :app:test`
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Settings still shows Saved · Connected against the same Mac

**Dependencies:** Task 2 **required**; Task 7 preferred so answer/leave exist to migrate

**Files likely touched:**

- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/test/java/com/baotime/app/network/BlueBubblesClientTest.kt`

**Estimated scope:** Medium: 3-5 files

**Risks / gates:** Task 14 licence row for OkHttp in the same implementation PR.

---

## Task 13: WebView compatibility matrix (planned, not executed)

**Description:** Apple documents FaceTime-on-the-web on Android via **Chrome or Edge**, not WebView (https://support.apple.com/en-us/109364, published 2026-05-29). Keep BaoTime’s gated WebView as a product choice. Fill this matrix on **real devices** in a later session. This documentation PR does **not** run it.

| Device / OS / WebView | Admit to waiting room | Camera | Microphone | In-room navigation / leave | Network reconnect | PiP |
| --- | --- | --- | --- | --- | --- | --- |
| (TBD) | planned | planned | planned | planned | planned | planned |

**Acceptance criteria:**

- [ ] Matrix filled with pass/fail/notes per cell
- [ ] README Limitations still state Apple names Chrome/Edge
- [ ] Failure of a cell does not get explained as “Apple-supported WebView”

**Verification:**

- [ ] Tests pass: N/A (manual real-device)
- [ ] Build succeeds: N/A for the matrix itself
- [ ] Manual check: real devices only; emulators optional extra

**Dependencies:** None for planning; implementation of rooms already exists

**Files likely touched:**

- `tasks/plan.md` (results addendum)
- `README.md` (limitations only if results change the claim)

**Estimated scope:** Small: 1-2 files (plus device time)

---

## Task 14: Licence and NOTICE inventory before any copy

**Description:** Before copying any BlueBubbles file or landing OkHttp/Socket.IO/FCM in an implementation PR, record: SPDX of each artifact, whether a NOTICE exists (BB app/server: **none** at pins), Apache-2.0 attribution steps if BB source is copied, and transitive licences of new Gradle deps. Do **not** describe the work as a certified legal clean-room.

**Acceptance criteria:**

- [ ] Table in `tasks/plan.md` or a `NOTICE`/`LICENSES` file listing added deps
- [ ] If any BB source is copied: BaoTime `LICENSE` + attribution **in that same PR**
- [ ] README does not claim certified clean-room

**Verification:**

- [ ] Tests pass: N/A
- [ ] Build succeeds: N/A
- [ ] Manual check: diff review for Flutter/`MethodChannel` types

**Dependencies:** Before Tasks 4, 11, or 12 land libraries; before any BB copy

**Files likely touched:**

- `LICENSE` / `NOTICE` (only if required)
- `tasks/plan.md`

**Estimated scope:** Small: 1-2 files

---

## Checkpoint: Complete

- [ ] M2 incoming works without becoming an iMessage app
- [ ] No Flutter/Dart sources in the tree
- [ ] README FaceTime/incoming sections updated in a **later** docs task if behaviour changed (not solely this planning PR)
- [ ] Human reviewed licence, helper, FGS/push, WebView risk
- [ ] Ready for implementation PRs **separate** from this README/planning PR if desired

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
- Writing files to `/Users/brandon/Downloads/facetime_android` from this agent
- App implementation in this documentation PR
