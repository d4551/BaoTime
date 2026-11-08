# Task list: BlueBubbles FaceTime behaviour → BaoTime

Companion to [`plan.md`](plan.md). Planning only. Check boxes when a future implementation session finishes the task.

Repository commands:

- Build: `./gradlew :app:assembleDebug`
- JVM tests: `./gradlew :app:test`
- Instrumented (gated): see README; not required for Tasks 1–9 unless noted

Do not overwrite this file if a different plan’s tasks are still unchecked. This file is the **BlueBubbles FaceTime ingest** plan created 2026-11-08.

---

## Task 1: Capture live server contract fixtures

**Description:** Against a real BlueBubbles Server **v1.9.12** (or documented older), record JSON for `GET /api/v1/ping` and `GET /api/v1/server/info` (helper on and off), plus one `incoming-facetime` socket payload if a call can be placed. Store redacted fixtures under `app/src/test/resources/bluebubbles/` (no passwords, no personal numbers). Confirms field names before client work.

**Acceptance criteria:**
- [ ] Fixture files exist for ping 200, info helper-up, info helper-down (or 5xx/error body from Private API middleware)
- [ ] Each fixture lists server version string actually returned
- [ ] No server password or raw FaceTime key material in git

**Verification:**
- [ ] Tests pass: N/A (fixtures only) or a tiny parser smoke test if added
- [ ] Build succeeds: `./gradlew :app:assembleDebug` still succeeds with fixtures only
- [ ] Manual check: compare keys to `ServerRouter.getInfo` / BaoTime `BlueBubblesClient.checkServerStatus`

**Dependencies:** None

**Files likely touched:**
- `app/src/test/resources/bluebubbles/ping.json`
- `app/src/test/resources/bluebubbles/server-info-helper-on.json`

**Estimated scope:** Small: 1-2 files

---

## Task 2: OkHttp 5.5.1 behind BlueBubblesClient

**Description:** Replace `HttpURLConnection` in `BlueBubblesClient` with OkHttp **5.5.1** (stable, not 5.0 alpha). Keep the same façade: `checkServerStatus`, `createFaceTimeSession`, `fetchContacts`. Auth remains query `password` (and accept `guid` as alias if we add it). Timeouts stay 6s connect / 25s read unless fixtures show FaceTime answer needs longer (server answer waits on Mac). No UI change.

**Acceptance criteria:**
- [ ] Catalog pin `okhttp = "5.5.1"`; no alpha OkHttp
- [ ] Existing create-link and info behaviour preserved
- [ ] Password never logged (interceptor redacts `password`/`guid`/`token` query keys)

**Verification:**
- [ ] Tests pass: `./gradlew :app:test` (existing unit tests + MockWebServer info/create if added here)
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Settings still shows Saved · Connected against the same Mac

**Dependencies:** Task 1 preferred (fixtures); can start with MockWebServer from current client parsing

**Files likely touched:**
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/test/java/com/baotime/app/network/BlueBubblesClientTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 3: Gate FaceTime on helper, Private API, and server version

**Description:** Use `/server/info` fields BaoTime already parses (`privateApiEnabled`, `helperConnected`, `serverVersion`). Refuse `createFaceTimeSession` (and later answer) when helper/Private API is false, or version is below the negotiated minimum (default **1.9.0**, target **1.9.12**). Map helper-missing to existing typed `ServerFailure` / banner strings; add a string only if current copy is wrong. No Socket yet.

**Acceptance criteria:**
- [ ] Create is not attempted when helper or private_api is false
- [ ] User-visible banner uses existing or new **fixed** copy (no interpolated diagnostics)
- [ ] Version parse is defensive (semver-ish); unknown version ≠ crash

**Verification:**
- [ ] Tests pass: unit tests with fixtures from Task 1
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: with helper off, New Call shows failure without hanging 25s

**Dependencies:** Task 2

**Files likely touched:**
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/baotime/app/network/ServerCapabilityTest.kt`

**Estimated scope:** Small: 1-2 files (medium if strings + MainActivity banner wiring)

---

## Checkpoint: After Tasks 1-3 (Foundation)

- [ ] `./gradlew :app:test` and `./gradlew :app:assembleDebug` pass
- [ ] Outgoing create-link still works on a helper-connected Mac
- [ ] Review with human before Socket.IO dependency

---

## Task 4: Socket.IO 4 connection Flow

**Description:** Add a single `BlueBubblesSocket` using a Socket.IO **4**-compatible Java/Kotlin client. Connect to the configured origin with query `guid=<password>`, transports websocket+polling, force-new engine (BB client learned reuse pins stale auth — see `socket_service.dart`). Expose `StateFlow` of connection state (idle/connecting/connected/authFailed/offline). Collect with lifecycle, not a leaked singleton thread. HTTP 401 on ping marks bad password (BB pattern). Unlimited reconnect with backoff; do not invent a second retry loop.

**Acceptance criteria:**
- [ ] One socket instance per process; password from `AppConfig.connection()` only
- [ ] Auth failure distinct from offline
- [ ] Disconnects when Activity destroyed / config revision changes
- [ ] No password in logs or crash reports

**Verification:**
- [ ] Tests pass: fake/engine unit test if the library allows; otherwise a thin wrapper test
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Settings connected ⇒ socket connected; wrong password ⇒ authFailed without spinning forever

**Dependencies:** Task 2, Task 3

**Files likely touched:**
- `gradle/libs.versions.toml`
- `app/src/main/java/com/baotime/app/network/BlueBubblesSocket.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt` (start/stop only)

**Estimated scope:** Medium: 3-5 files

---

## Task 5: incoming-facetime → existing incoming UI

**Description:** Subscribe to socket event `incoming-facetime`. Parse `call_uuid` and caller (field names from Task 1 fixture / BB notification extras `callUuid`/`caller`). Drive `IncomingCallController.show` with a new routed action that **carries `callUuid`**, not a FaceTime URL yet. Do not open WebView. Keep NLS scrape path for URL-only notifications.

**Acceptance criteria:**
- [ ] Socket event shows Accept/Decline without Notification Listener
- [ ] Duplicate `call_uuid` does not stack two overlays
- [ ] NLS URL path still functions if socket did not fire

**Verification:**
- [ ] Tests pass: parser unit test for sample payload
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: incoming FaceTime to the Mac while BaoTime is foregrounded shows overlay

**Dependencies:** Task 4

**Files likely touched:**
- `app/src/main/java/com/baotime/app/routing/UrlRouter.kt` (or a small `IncomingFaceTimeEvent` type)
- `app/src/main/java/com/baotime/app/call/IncomingCallController.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/test/java/com/baotime/app/network/IncomingFaceTimePayloadTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 6: POST /api/v1/facetime/answer/:call_uuid then WebView

**Description:** On Accept, `POST /api/v1/facetime/answer/{call_uuid}` with `{}` body (same as Flutter `FaceTimeApi.answer`). Expect `{ data: { link } }` HTTPS FaceTime URL. Feed existing `openCallSession` / WebView gate. Timeouts may need to exceed 25s (Mac answer + 4s Sonoma delay + generateLink + admit). Do not open Chrome. Do not call Apple private APIs.

**Acceptance criteria:**
- [ ] Accept with valid uuid opens the returned link in the existing room overlay
- [ ] Non-room or empty link uses `ServerFailure.EmptyLink` / existing toast
- [ ] Private API / helper errors map to existing generic or new fixed string
- [ ] Link is recorded in Recents as incoming

**Verification:**
- [ ] Tests pass: MockWebServer answer success/empty/401
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Accept joins Apple web room; camera/mic still via existing Continue path

**Dependencies:** Task 5, Task 3

**Files likely touched:**
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/test/java/com/baotime/app/network/FaceTimeAnswerTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 4-6 (Incoming)

- [ ] Foreground incoming via socket works end-to-end
- [ ] Outgoing create-link still works
- [ ] NLS fallback still present
- [ ] Review with human before leave/availability/push

---

## Task 7: leave + ft-call-status-changed

**Description:** Decline or missed: `POST /api/v1/facetime/leave/:call_uuid` best-effort. Listen to `ft-call-status-changed` (`FaceTimeSessionStatus`: unknown=0, answered=1, outgoing=3, incoming=4, disconnected=6). Dismiss overlay on disconnected if still showing. Closing the WebView does **not** need leave for outgoing minted links (Mac already left after admitAndLeave); only incoming uuids.

**Acceptance criteria:**
- [ ] Decline records TYPE_MISSED and attempts leave
- [ ] Disconnect event dismisses incoming UI
- [ ] Leave failures do not crash; logged without uuid PII if possible (uuid is opaque — ok to log)

**Verification:**
- [ ] Tests pass: status enum mapping unit test; leave MockWebServer
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: Decline while caller still ringing; overlay gone; Recents unanswered

**Dependencies:** Task 6

**Files likely touched:**
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/network/BlueBubblesSocket.kt`
- `app/src/main/java/com/baotime/app/call/IncomingCallController.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 8: FaceTime availability on New Call

**Description:** Before `POST /facetime/session` when the user typed a handle (not a raw FaceTime URL), call `GET /api/v1/handle/availability/facetime?address=`. If the server says unavailable, show a fixed string and do not mint a link. If the route errors because helper is down, fall back to Task 3 gate. Confirm on a real v1.9.12 whether this route needs helper despite no extra PrivateApiMiddleware in `httpRoutes.ts`.

**Acceptance criteria:**
- [ ] Unavailable handle does not call `/facetime/session`
- [ ] Raw FaceTime URL join path unchanged
- [ ] Document actual helper requirement in a comment citing the probe

**Verification:**
- [ ] Tests pass: MockWebServer true/false/500
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: known FaceTime-capable handle vs random email

**Dependencies:** Task 3

**Files likely touched:**
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt`
- `app/src/main/java/com/baotime/app/MainActivity.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/baotime/app/network/FaceTimeAvailabilityTest.kt`

**Estimated scope:** Medium: 3-5 files

---

## Task 9: Tests, logs, no secret leakage

**Description:** MockWebServer coverage for info/create/answer/leave/availability. Socket payload parsers. Grep-level guarantee: interceptors redact auth query keys. Do not add instrumented Keystore tests unless `auditFixture` harness is used. Optional: simple `Trace`/`Log` tags `BaoTime/Http` `BaoTime/Socket`.

**Acceptance criteria:**
- [ ] JVM tests cover success and helper-missing for answer
- [ ] `./gradlew :app:test` is the default CI-local command
- [ ] Logcat sample of a create/answer contains no password

**Verification:**
- [ ] Tests pass: `./gradlew :app:test`
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: logcat during one call; no `guid=`/`password=` values

**Dependencies:** Tasks 6–8

**Files likely touched:**
- `app/src/test/java/com/baotime/app/network/`
- `app/src/main/java/com/baotime/app/network/BlueBubblesClient.kt` (redacting interceptor if not in Task 2)

**Estimated scope:** Medium: 3-5 files

---

## Checkpoint: After Tasks 7-9 (Hygiene)

- [ ] All JVM tests pass
- [ ] Decline/leave and availability behave on device
- [ ] Human decides whether M4 push is required

---

## Task 10: Background incoming design (no code unless approved)

**Description:** Write a short addendum (comment on this task or a paragraph in plan.md) choosing **none** / **FCM** / **UnifiedPush** / **foreground socket service**. Constraints: Android 14+ FGS types; Play Services not universal; BB server Firebase is optional; webhooks cannot hit a phone. If “none”, document that incoming requires BaoTime process or NLS scrape.

**Acceptance criteria:**
- [ ] Written decision with threat/UX tradeoff
- [ ] No production code in this task

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

**Description:** If approved, register with server FCM endpoints (see BB `FcmApi` / `FcmRouter`) **or** UnifiedPush, mapping the FaceTime push payload into the same incoming UI as Task 5. Do not copy `CreateIncomingFaceTimeNotification.kt` (Flutter `MethodCall`). Clean-room NotificationCompat call category using BaoTime resources.

**Acceptance criteria:**
- [ ] Killed-app incoming works on a Play-Services device **or** documented UnifiedPush distributor
- [ ] Same `call_uuid` answer path as Task 6
- [ ] No Apache-2.0 copy of BB Kotlin without adding LICENSE

**Verification:**
- [ ] Tests pass: payload parser tests
- [ ] Build succeeds: `./gradlew :app:assembleDebug`
- [ ] Manual check: force-stop BaoTime, place FaceTime to Mac, notification appears

**Dependencies:** Task 10 approval, Task 6

**Files likely touched:**
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/baotime/app/network/` (new push handler)
- `app/src/main/java/com/baotime/app/call/`

**Estimated scope:** Medium: 3-5 files (stop and split if larger)

---

## Checkpoint: Complete

- [ ] M2 incoming works without becoming an iMessage app
- [ ] No Flutter/Dart sources in the tree
- [ ] README FaceTime/incoming sections updated in a **later** docs task if behaviour changed (not this planning PR)
- [ ] Human reviewed licence and helper requirements
- [ ] Ready for implementation PRs **separate** from the README PR if desired

---

## Explicitly not tasks

- Porting `lib/` Dart or GetX/ObjectBox
- Embedding Flutter engine
- Chrome Custom Tabs as the room (BaoTime WebView stays)
- Apple FaceTime native / WebRTC reverse engineering
- iMessage send/react/typing/Find My
- EncryptedSharedPreferences migration
- DataStore-tink (alpha)
- Lifecycle 2.12.0-alpha05
- OkHttp 5.0.0-alpha.*
- Writing files to `/Users/brandon/Downloads/facetime_android` from this agent
)
