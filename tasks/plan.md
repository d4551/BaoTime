# Implementation Plan: Ingest useful BlueBubbles FaceTime client behaviour into BaoTime

Retrieval date: **2026-11-08**. Planning only. No app code in this change.

## Overview

BaoTime is already a native Kotlin FaceTime-web client that talks to a personal BlueBubbles server (`GET /api/v1/server/info`, `POST /api/v1/facetime/session`, `GET /api/v1/contact`) and opens `https://facetime.apple.com` rooms in a gated WebView. Incoming calls today scrape BlueBubbles/Beeper **notification text**.

The official BlueBubbles **Android app is Flutter/Dart**, not a Kotlin app. Native Android is a Kotlin plugin layer around Flutter (FCM, notifications, foreground socket). It cannot be copied into BaoTime as-is. Useful FaceTime behaviour should be **reimplemented** against the documented server REST + Socket.IO APIs, using current Android/Kotlin patterns. Do **not** become an iMessage client. Do **not** add unsupported Apple FaceTime native APIs on Android (none exist). Do **not** copy or hook macOS Private API dylibs onto the phone.

This plan maps exact upstream pins, licence rules, public vs Private-API server surfaces, and vertical slices with acceptance and verification. Task checklist lives in [`tasks/todo.md`](todo.md).

## Upstream pins (verified 2026-11-08)

| Artifact | Pin | Evidence |
| --- | --- | --- |
| Client repo | https://github.com/BlueBubblesApp/bluebubbles-app Apache-2.0 | `gh api repos/BlueBubblesApp/bluebubbles-app` → `license: Apache-2.0`, language **Dart** |
| Client default HEAD | `master` @ `60113ebababbf23c9faf8fc6746c6691b30d6f69` (2026-11-08, “Don't trust LAN override after connection loss”) | GitHub commits API |
| Latest **Android** client release | **v2.1.5+android.95** published 2026-10-29, commit `ff8bfd5a57a8c6686c50d7b7b03d4ef8a5bded11` | GitHub releases + git ref `v2.1.5+android.95` |
| Latest GitHub “latest” client tag | Desktop **v2.1.6+desktop.96** (2026-11-03). Not the Android APK. | `gh release list` |
| Server repo | https://github.com/BlueBubblesApp/bluebubbles-server Apache-2.0 | GitHub API |
| Latest **stable** server | **v1.9.12** (2026-10-27), commit `76d2b2e786c04d7516de089f31be978a68da8083` | GitHub releases |
| Helper (macOS Private API) | https://github.com/BlueBubblesApp/bluebubbles-helper Apache-2.0; latest tag **0.0.21** (2024-11-19) | GitHub API |
| Socket.IO on server v1.9.12 | `"socket.io": "^4.7.5"` | `packages/server/package.json` @ tag v1.9.12 |
| REST docs | https://docs.bluebubbles.app/server/developer-guides/rest-api-and-webhooks | fetched 2026-11-08 (page footer also says “Last updated 3 years ago” — treat Postman + `httpRoutes.ts` as source of truth for paths) |
| Postman | https://documenter.getpostman.com/view/765844/UV5RnfwM | linked from official REST docs |
| Private API docs | https://docs.bluebubbles.app/private-api | helper capabilities; **macOS only** |
| Client architecture | https://docs.bluebubbles.app/client/build-yourself-contribution-guide/architecture | Flutter layers, Dart socket, isolates |
| Client “all clients are Flutter/Dart” | https://docs.bluebubbles.app/client | fetched 2026-11-08 |

**Stable vs alpha (do not mix casually):**

| Piece | Status 2026-11-08 | Use in BaoTime? |
| --- | --- | --- |
| BlueBubbles Server v1.9.12 | **Stable** latest | Target. Gate on `server_version` from `/api/v1/server/info`. |
| BlueBubbles Android v2.1.5 | **Stable** Android APK; desktop already at 2.1.6 | Behaviour reference only |
| BlueBubbles helper 0.0.21 | Last GitHub tag **2024**; server ships its own dylibs | Mac-side only |
| AndroidX Lifecycle | **Stable 2.11.0**; 2.12.0-alpha05 exists (docs updated 2026-10-21) | Stay on **2.11.0** (already in `gradle/libs.versions.toml`) |
| Jetpack DataStore | **Stable 1.3.0** | OK for non-secrets |
| `androidx.datastore:datastore-tink` | **1.3.0-alpha07** | Do **not** require for v1; keep existing Keystore AES/GCM |
| `androidx.security.crypto` EncryptedSharedPreferences | **Deprecated** 1.1.0, no further releases ([package summary](https://developer.android.com/reference/androidx/security/crypto/package-summary) last updated 2026-10-28) | Do **not** migrate onto it |
| OkHttp | **Stable 5.5.1** (Maven Central `lastUpdated` 2026-10-22); 5.0.x alphas are old | Prefer **5.5.1**, not 5.0.0-alpha |
| AGP | BaoTime already **9.6.0-alpha03** (alpha). BlueBubbles Android uses AGP **9.4.1** with `android.builtInKotlin=false` ([android/CLAUDE.md](https://github.com/BlueBubblesApp/bluebubbles-app/blob/master/android/CLAUDE.md)) | Do not “upgrade” further in FaceTime slices. Note alpha risk separately. |
| Socket.IO Java client | Must speak **Socket.IO 4** | `io.socket:socket.io-client` 2.1.x line (Engine.IO 4). Verify against 4.7.5 at impl time. |

## Licence obligations (Apache-2.0)

BlueBubbles app, server, and helper are **Apache License 2.0**. LICENSE blob on both app and server: path `LICENSE` (server blob sha `261eeb9e9f8b2b4b0d119366dda99c6fd7d35c64`). No `NOTICE` file on either repo (404).

Apache-2.0 **allows** reproduction and derivative works **if**:

1. Recipients get a copy of the licence.
2. Modified files carry change notices.
3. Attribution / NOTICE contents (if any appear later) are preserved.
4. Patent grant terms are honoured.

BaoTime currently has **no licence file**. That is not a grant to ignore Apache-2.0 if we **copy** BlueBubbles source.

### Reuse / copy / extraction vs clean reimplementation

| Approach | Allowed? | Recommendation |
| --- | --- | --- |
| Copy Flutter/Dart (`lib/…`) into BaoTime | Technically under Apache-2.0 with attribution; **not runnable** in this Kotlin/View app | **Reject.** Dart is not Kotlin-portable. Flutter engine is out of scope. |
| Copy Kotlin notification/FCM plugins from `android/app/src/main/kotlin/com/bluebubbles/messaging/` | Allowed with Apache-2.0 NOTICE + LICENSE in BaoTime | **Avoid.** Tied to Flutter `MethodChannel`, `MethodCallHandlerImpl`, BB package ids. |
| Extract API path strings / event names / JSON field names from public server + client | Facts, not copyrightable as a block; still cite sources | **Do this.** |
| Clean-room Kotlin: OkHttp + Socket.IO + existing BaoTime UI against documented REST/events | No BB source copied | **Default.** |
| Vendor helper dylib / IMCore hooks on Android | Meaningless and unsafe | **Forbidden.** |
| Apple FaceTime native SDK / undocumented WebRTC bypass | Does not exist for Android | **Forbidden.** Rooms stay `https://facetime.apple.com` in the existing WebView. |

If a later slice *does* copy a BB Kotlin file, stop and add `LICENSE` + attribution **before** merge of that slice.

## What the official Android client actually is

Verified:

- Docs: “All clients are coded in Flutter and Dart.” https://docs.bluebubbles.app/client
- GitHub language: Dart. `pubspec.yaml` `name: bluebubbles`, SDK `>=3.12.2 <4.0.0`, version `2.1.6+96` on master.
- FaceTime HTTP: Dart `FaceTimeApi` — **answer** and **leave** only; **no** `session` create in that file.  
  https://github.com/BlueBubblesApp/bluebubbles-app/blob/master/lib/services/network/api/facetime_api.dart
- Outgoing web-link create lives on the **server** `POST /api/v1/facetime/session` (BaoTime already calls this). Official Flutter client may still open generated links in **Chrome**, not WebView ([issue 2775](https://github.com/BlueBubblesApp/bluebubbles-app/issues/2775): Flutter WebView support called “terrible”; they force Chrome).
- Incoming: Socket event `incoming-facetime` → Dart `MessageHandlerSvc` → Kotlin `CreateIncomingFaceTimeNotification` (payload: `call_uuid`, `title`, `body`, `caller`, `caller_avatar`). Answer extra `answer=true` on `MainActivity`.  
  https://github.com/BlueBubblesApp/bluebubbles-app/blob/master/android/app/src/main/kotlin/com/bluebubbles/messaging/services/notifications/CreateIncomingFaceTimeNotification.kt
- Socket: `socket_service.dart` sets query `guid=<password>`, transports `websocket` + `polling`, `forceNew`, unlimited reconnect, custom cert adapter. Listens `incoming-facetime` (JSON-decoded) and `ft-call-status-changed`.  
  https://github.com/BlueBubblesApp/bluebubbles-app/blob/master/lib/services/network/socket_service.dart
- Native Android also: FCM, UnifiedPush, foreground service to keep socket alive, minSdk **26**, compile/target **36**, Java 21, Gradle 9.8, AGP 9.4.1 ([android/CLAUDE.md](https://github.com/BlueBubblesApp/bluebubbles-app/blob/master/android/CLAUDE.md)).

BaoTime is the opposite stack: minSdk **29**, compile/target **37**, Java 17 bytecode, View UI, in-app WebView, Keystore password, no Socket.IO, no FCM.

## BaoTime current source (this repo)

Inspected 2026-11-08 on branch with README; production code unchanged by this plan.

| Area | Today | Gap vs official FaceTime path |
| --- | --- | --- |
| HTTP | `HttpURLConnection`, password query param, 6s connect / 25s read | No OkHttp, no 401 vs helper-missing distinction, no answer/leave |
| Info | `GET /api/v1/server/info` requires `server_version`, `private_api`, `helper_connected`, `detected_imessage` | Does not **gate** FaceTime on helper/private_api before create |
| Create | `POST /api/v1/facetime/session` `{}` → `data.link` | Matches server `FaceTimeRouter.newSession` |
| Contacts | `GET /api/v1/contact` | No `POST /contact/query`; no avatars |
| Incoming | NotificationListenerService scrapes BB/Beeper text for FaceTime URLs | No `call_uuid`; cannot `answer`/`leave`; racey; needs NLS permission |
| Room | Gated WebView, JS only on FaceTime HTTPS rooms | Keep. This is **better** than BB’s Chrome tab for BaoTime’s product |
| Auth storage | `SecureStore` AES/GCM AndroidKeyStore `v1:` | Aligns with current Android guidance to use Keystore directly, not deprecated `MasterKey` |
| Prefs | `SharedPreferences` for URL + ciphertext + history | Fine for non-secrets; DataStore optional later |
| Tests | JVM router/policy/codec; instrumented tests gated | No socket/FCM tests |
| TLS | `usesCleartextTraffic=true` for LAN HTTP | Keep LAN HTTP; do not weaken HTTPS |
| Background | None (no FCM, no FG socket) | Incoming dies when BB notification not visible |

## Public REST vs Private API vs Apple

Auth on almost all REST: query `guid` **or** `password` **or** `token` = server password. Official: https://docs.bluebubbles.app/server/developer-guides/rest-api-and-webhooks  
BaoTime already uses `password=`. Keep one alias; document both.

`HttpRoutes.protected` = metrics/error/log + **AuthMiddleware** + body parser.  
`PrivateApiMiddleware` = `checkPrivateApiStatus()` or  iMessage-style error “complete Private API setup / helper connected”.  
https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/http/api/v1/middleware/privateApiMiddleware.ts

### FaceTime REST (all **Private API** + macOS Monterey+)

Source: https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/http/api/v1/httpRoutes.ts (FaceTime group)  
Router: https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/http/api/v1/routers/facetimeRouter.ts  
Interface: https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/interfaces/facetimeInterface.ts  
Session logic: https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/api/lib/facetime/FaceTimeSession.ts

| Method | Path | Auth | Private API | What it does | BaoTime |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/v1/facetime/session` | yes | **yes** | Generate outgoing link; Mac `admitAndLeave` (admit self, wait 15s, leave) | **Have** |
| POST | `/api/v1/facetime/answer/:call_uuid` | yes | **yes** | Answer incoming, generate link, admitAndLeave; returns `{ data: { link } }` | **Missing** |
| POST | `/api/v1/facetime/leave/:call_uuid` | yes | **yes** | Private API `leaveCall` | **Missing** |

macOS floor in interface: `isMinMonterey` (FaceTime create/answer/leave). Helper must be connected.

### Related REST useful to BaoTime

| Method | Path | Private API extra? | Use |
| --- | --- | --- | --- |
| GET | `/api/v1/ping` | no (auth) | Cheap liveness / 401 password check (BB socket uses HTTP 401 to flag bad password) |
| GET | `/api/v1/server/info` | no extra | Version, `private_api`, helper, detected handle — **have** |
| GET | `/api/v1/contact` | group auth only | Mac address book — **have** |
| POST | `/api/v1/contact/query` | group auth only | Targeted lookup |
| GET | `/api/v1/handle/availability/facetime?address=` | **no extra PrivateApiMiddleware** on this route (iMessage availability **does** add it) | Pre-flight callee; still may need helper in implementation — verify on a real server |
| GET | `/api/v1/handle/availability/imessage?address=` | **yes** | Out of BaoTime scope unless we show “can iMessage” |

### Socket events (server `events.ts`)

https://github.com/BlueBubblesApp/bluebubbles-server/blob/master/packages/server/src/server/events.ts

**In scope for BaoTime:**

| Event | Why |
| --- | --- |
| `incoming-facetime` | Primary incoming signal with `call_uuid` (client JSON-decodes) |
| `ft-call-status-changed` | answered / incoming / disconnected (`FaceTimeSessionStatus`) |

**Out of scope (iMessage product):** `new-message`, typing, participants, Find My, backups, scheduled messages, etc.

Handshake: query `guid=<server password>`, websocket+polling, Socket.IO 4.

### FCM / UnifiedPush / webhooks

- Server FCM service exists for device push when the app is killed.
- Webhooks (server ≥ 1.0.0) POST selected events to a URL — **not usable on a phone** without a public endpoint.
- UnifiedPush exists in BB Kotlin (`UnifiedPushHandler.kt`) for de-Googled devices.

BaoTime slice 1: Socket while process alive. Slice later: FCM **optional**. Keep NLS scrape as last-resort fallback, then retire it.

### Not proposed

- Native Apple FaceTime on Android.
- Joining FaceTime via undocumented Apple APIs / MITM.
- Shipping helper bundles.
- Full chat, reactions, typing, Find My, Mac lock, server update install from the phone.
- Copying Flutter UI / ObjectBox / GetX.

## Latest Android / Kotlin patterns (verified 2026-11-08)

Context7 quota was exhausted this run; used Android Developers + Maven Central + BB’s own AGP notes.

| Topic | Verified pattern | BaoTime action |
| --- | --- | --- |
| Networking | OkHttp **5.5.1** stable | Replace `HttpURLConnection` behind the existing `BlueBubblesClient` façade |
| TLS | HTTPS with system trust; user certs only if we add a settings toggle (BB has `WebsocketAdapter` for user certs) | Keep LAN HTTP explicit; no custom trust by default |
| Async | Kotlin coroutines; Lifecycle **2.11.0** `lifecycle-runtime-ktx` (`repeatOnLifecycle`) | Socket + HTTP as Flows; collect in Activity `STARTED`/`RESUMED`; no GetX |
| State | `StateFlow` / `SharedFlow` (not LiveData for new code) | Connection + incoming call as flows |
| Secrets | Keystore AES/GCM already; EncryptedSharedPreferences **deprecated** | Keep `SecureStore`; never log password; still query-param auth (server constraint) |
| Prefs | DataStore 1.3.0 stable for non-secrets | Optional; not blocking FaceTime slices |
| Background | Android 14+ FG service **types**; 15+ restrictions; FCM high-priority for calls | Socket FG service only if product requires killed-app incoming; type `remoteMessaging`/`dataSync` per current FGS docs at impl time |
| Observability | Structured logs, no PII | `Log` tags `BaoTime/Http`, `BaoTime/Socket`; never log `guid` query |
| Tests | OkHttp `MockWebServer` (JVM); instrumented for Keystore/socket optional | Extend `app/src/test` first |

BaoTime AGP 9.6.0-alpha03 is **alpha**. FaceTime work must not depend on newer alpha APIs. Flutter BB’s `builtInKotlin=false` does not apply: BaoTime already uses AGP built-in Kotlin without `org.jetbrains.kotlin.android`.

## Architecture decisions

1. **Clean-room Kotlin client.** Consume REST + Socket.IO. Do not import Flutter modules.
2. **Stay a FaceTime-web app.** Recents / Contacts / WebView remain. iMessage inbox is out.
3. **Incoming path:** Socket `incoming-facetime` → existing `IncomingCallController` → on Accept `POST .../facetime/answer/:call_uuid` → existing room WebView. Decline → `POST .../leave/:call_uuid` (best effort) + missed Recents row.
4. **Keep WebView room gate.** Do not switch to Chrome like BB.
5. **Version gate:** refuse FaceTime create/answer if `private_api` or `helper_connected` is false, or `server_version` &lt; 1.9.0 (conservative; session API is older, but we target 1.9.12). Surface existing banners.
6. **NLS scrape stays until socket+answer is proven**, then becomes fallback only.
7. **No FCM in the first milestone** (Play Services, server Firebase config). Document as optional M4.
8. **OkHttp + Socket.IO 4**, password still query `guid`/`password` because that **is** the public API.
9. **Secrets stay in Keystore.** Do not adopt EncryptedSharedPreferences. DataStore-tink is alpha — skip.
10. **Licence:** no BB source copied in these slices → no Apache file required yet. If copy happens, add LICENSE first.

## Feature priority (BaoTime-shaped)

| P | Feature | Why | Depends |
| --- | --- | --- | --- |
| P0 | Typed HTTP + ping/401 + version/helper gate | Fail fast; foundation | — |
| P0 | Socket connect/reconnect + `incoming-facetime` | Real `call_uuid`; replaces scrape | P0 HTTP |
| P0 | REST answer → WebView | Completes incoming | Socket |
| P1 | REST leave + `ft-call-status-changed` | Correct hangup / missed | Answer |
| P1 | FaceTime availability on New Call | Avoid doomed creates | HTTP |
| P2 | Contact query / refresh on revision | Already partly there | HTTP |
| P2 | Observability + MockWebServer tests | Regression | HTTP/socket |
| P3 | Optional FCM/UnifiedPush | Killed-app incoming | Server Firebase + user Play Services |
| P3 | Optional FG socket service | Process death | Product decision |
| Drop | iMessage chat, reactions, Find My, Chrome handoff | Not BaoTime | — |

## Milestones

**M0 — Contract (this plan).** Human approves slices.

**M1 — HTTP spine.** OkHttp client, ping, stricter info, create still works.

**M2 — Live incoming.** Socket + answer + existing incoming UI + WebView.

**M3 — Call hygiene.** Leave, status events, availability, NLS fallback labelled.

**M4 — Optional push / FG.** Only if M2 incoming fails when backgrounded.

Each milestone leaves assembleDebug + `:app:test` green.

## Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Flutter source mistaken for copy-paste Kotlin | High | Clean-room; cite paths only |
| Private API / Monterey / helper missing | High | Gate on `/server/info`; typed errors |
| `admitAndLeave` race (Mac leaves before Android joins) | High | Known BB issue ([helper#38](https://github.com/BlueBubblesApp/bluebubbles-helper/issues/38), [server#601](https://github.com/BlueBubblesApp/bluebubbles-server/issues/601)). Keep WebView; open link immediately after `answer`; do not add Apple-side hacks |
| Socket.IO version skew | Med | Pin client to Socket.IO 4 / server 4.7.5 |
| Password in query string | Med | Server-mandated; HTTPS on non-LAN; never log URL |
| AGP 9.6 alpha | Med | No extra alpha deps |
| FCM on Graphene / no GMS | Med | Socket + NLS first; UnifiedPush only if requested |
| Apache-2.0 if someone pastes BB Kotlin | Med | Review diffs; no Flutter `MethodChannel` types |
| `availability/facetime` may still need helper | Low | Probe on real server in Task 8 |
| Local Mac `tasks/` conflict | Med | Parent must not overwrite an in-flight plan at `/Users/brandon/Downloads/facetime_android` |

## Open questions (human)

1. Is killed-app incoming required in M2, or is “app open / recent” enough?
2. Keep Beeper NLS scrape after socket works?
3. Will BaoTime take a licence (Apache-2.0 to match BB, or other) before any copy?
4. Minimum server: 1.9.12 only, or also 1.9.9?
5. User certs / cleartext: stay as today?

## Task list (index)

Full criteria in [`todo.md`](todo.md).

### Phase 1: Foundation
- [ ] Task 1: Record API/version fixtures from `/server/info` + ping
- [ ] Task 2: OkHttp client behind existing façade (create+info still work)
- [ ] Task 3: Gate FaceTime on helper/private_api/version

### Checkpoint: Foundation
- [ ] `:app:test` + assembleDebug; create-link path unchanged on a helper-up Mac

### Phase 2: Live incoming
- [ ] Task 4: Socket.IO 4 connection + auth + reconnect Flow
- [ ] Task 5: Handle `incoming-facetime` → existing incoming UI (`call_uuid`)
- [ ] Task 6: `POST /facetime/answer/:call_uuid` → WebView

### Checkpoint: Incoming
- [ ] Accept from socket-driven UI joins a room; NLS still works as fallback

### Phase 3: Hygiene
- [ ] Task 7: leave + `ft-call-status-changed`
- [ ] Task 8: FaceTime availability on New Call
- [ ] Task 9: MockWebServer + socket unit tests; structured logs

### Checkpoint: Hygiene
- [ ] Decline/leave; unavailable handle; no password in logs

### Phase 4: Optional
- [ ] Task 10: Decide FCM vs UnifiedPush vs FG socket (design only until approved)
- [ ] Task 11: If approved, FCM/UnifiedPush payload → same incoming UI

### Checkpoint: Complete
- [ ] Human review; no Flutter sources in tree; README still accurate

## Local transfer note

This agent **cannot** write `/Users/brandon/Downloads/facetime_android`. Copies also live under `/opt/cursor/artifacts/tasks/`. Parent copies `tasks/plan.md` and `tasks/todo.md` only. **Do not** overwrite that folder’s README. **Do not** copy if that folder already has an **unchecked** plan for other work — resolve the conflict first.
)
