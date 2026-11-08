# BaoTime — standalone workstream contracts and reconciliation

Status: PROPOSED; neither reviewer acceptance nor implementation completion is claimed.
Date: 2026-11-08 (Hong Kong, UTC+8).
Baseline: `7e7ec014691a5fab18e6200c58e64e28131d7b9e`.
Scope: documentation/planning only, based on all five exported baseline documents listed in `baotime-baseline-manifest.json`. That manifest freezes the exact exported bytes and canonical paths; baseline attribution is supplied context, not a fresh independent provider verification.

## 1. Authority and adoption

This is an independent planning deliverable, not a patch or a claim that repository documents were revised. The repository, application, local Mac files, provider settings and existing PR are unchanged by this deliverable. No Cursor/cloud agent, git/provider write, application build, runtime/device test or merge is part of this work.

Only after BOTH Karen and Code Auditor explicitly accept the SAME SHA256 of this file and its baseline manifest does this addendum become the authoritative workstream planning overlay for the specified baseline and reviewed scope. Until then it remains a proposed replacement. Record each verdict against those hashes separately; silence, earlier technical acceptance, author self-review and historical audit text are not acceptance. Changes after review require re-freezing and re-review.

On adoption, precedence is: this addendum's workstream/dependency/support/milestone rules; then baseline technical and per-task acceptance contracts except the exact supersessions in section 7. Historical conflicting text remains visible but is not an alternative instruction. No requirement can be waived by selecting a weaker sentence in an old table. Unmapped baseline technical constraints remain in force. This grants no implementation, publication, legal, merge or release authorization. Future repo incorporation requires separate authorization; reviewers may accept this standalone overlay without claiming repo reconciliation occurred.

## 2. Decisions and immutable technical boundaries

### 2.1 Conservative support decision

The only planned supported server baseline is BlueBubbles **1.9.12**, pinned to `76d2b2e786c04d7516de089f31be978a68da8083`. There is no minimum-1.9.0 or implied >=1.9.12 compatibility range. Versions 1.9.0, 1.9.9, other older versions, unknown/unparseable versions and unassessed newer versions are unsupported by this plan. Task 3 fails closed for create/answer/leave outside the accepted baseline, with fixed explanatory copy and no crash. Helper connected and Private API enabled are also required for those operations. This is a planned capability gate, not current shipped behavior. URL-only joining remains governed by the existing room gate, not a claim of server answer capability.

Supporting another server requires a separately accepted compatibility decision with redacted fixtures, pinned API/event evidence and explicit test results before enabling it. Task 1 may collect comparative samples, but they do not broaden support. This conservative decision removes the old unresolved minimum-version question rather than leaving a default broad range.

### 2.2 Preserved contract floor

These rules constrain every stream and its receiving reviewer; nothing below redesigns the already reviewed technical contract:

- One process-scoped socket and lifecycle store, keyed by connection/config generation plus invite generation. Activity destroy only unbinds UI; recreation/onNewIntent rebinds, not a second socket. Settings change cancels old work and reconnects with new configuration. Terminal transitions are idempotent; local ringing expires at 30 seconds.
- Same non-null UUID duplicates collapse. NLS URL-only, legacy caller/timestamp and socket UUID are NOT interchangeable identities. UUID→URL→weak key is within one event, not a cross-source matcher. Never correlate by time, proximity or caller-window. A future matcher requires a real shared identifier both events carry; the pinned calling payload carries UUID/address, not room URL. An unrelated UUID replaces an NLS overlay without merging; the NLS invitation terminates independently as missed/declined. Never answer/leave the NLS URL using that unrelated UUID.
- Only the active UUID is answered or disconnected. Decline/hangup/config change while answer is pending cancels work; a late body cannot open WebView or create a joined Recents entry. If Mac answer already succeeded, best-effort leave is required. Leave failure is not a crash; URL-only joins never acquire fabricated REST capability. Outgoing minted rooms do not invent incoming UUID leave obligations.
- Listen to both calling object `ft-call-status-changed` (UUID, incoming status_id 4, disconnected 6) and legacy JSON-string `incoming-facetime` (caller/timestamp, no UUID). Do not assume every enum status is emitted: outgoing 3 and answered 1 are suppressed on the pinned upstream calling path. `/server/info` does not expose `facetime_calling`.
- Reconnect is not replay: missed events have no server/client offset recovery contract. Process death loses in-memory invites. Reconciliation is connected status, local expiry, NLS or separately approved push; never invented replay.
- M2/M3 default is no FGS/no push; process-alive socket only, foreground delivery is the guaranteed test boundary, background survival is not promised. Settings force-stop stops delivery until user reopens; optional push does not defeat that. Separate foreground, background, swipe-away, OS kill, force-stop and reboot results.
- Task 10 is a written decision, not permission to copy the upstream iMessage client's `remoteMessaging` declaration. FGS needs an approved exact type, permissions/start/timeout, FSI policy and distribution route; `dataSync` is not an always-on call socket. No claimed Play eligibility without evidence/approval.
- Generic create is `POST /api/v1/facetime/session` with `{}`, no callee; handle availability is advisory and cannot block generic creation without a separate approved product change. Mac capability gate still applies.
- Retain gated WebView, HTTPS canonical FaceTime room/navigation/media restrictions and Continue-before-media permission flow. Apple names Chrome/Edge, not WebView. Real-device admission/camera/mic/navigation/reconnect/PiP remain unverified until Task 13 evidence; no Apple, accessibility or runtime certification.
- Keep Keystore SecureStore v1; no secrets migration in these slices. DataStore-tink 1.3.0 is stable, excluded for migration/scope, not prerelease. No custom trust, auth-query logging, personal identifiers or raw FaceTime key material in fixtures/receipts.
- Kotlin/View FaceTime-web client only: no Flutter modules/MethodChannel port, iMessage expansion, native Apple Android API, undocumented WebRTC bypass or helper dylib on phone. Independent rewrite after source reading is not certified legal clean-room. Task 14 licence/NOTICE/transitive obligations are mandatory whenever triggered.

Pinned source references and their limits remain those in baseline `tasks/plan.md` sections Upstream pins, FaceTime REST, Socket events, Call lifecycle contract, Foreground service eligibility, Android process states, Secrets and DataStore, and WebView compatibility risk. This addendum does not claim to have re-fetched upstream sources.

## 3. Roles and common handoff contract

All roles below are UNASSIGNED; these are accountable slots, not named people or a claim of staffing. A package is independently assignable, not independently mergeable. Assign a delivery role and receiving/accepting role before implementation starts. Review should be independent of delivery; if staffing cannot provide that, stop at review rather than self-approve.

- **Shared Integration Owner (SIO):** single accountable integration role for MainActivity, BlueBubblesClient, CallLifecycle/IncomingCallController/socket interfaces, Application lifecycle, AndroidManifest, strings and Gradle/catalog/build files. Serializes changes, owns interface freeze/version and integration order; does not replace specialist acceptance.
- **Contract Reviewer:** accepts fixtures, identity/state/cancellation interfaces and lifecycle conformance.
- **Android Networking Reviewer:** accepts capability, socket, answer/leave and transport behavior.
- **QA Reviewer:** accepts regression/privacy evidence and device matrix applicability/results.
- **Product/Policy Approver:** accepts optional background/distribution decisions and user-facing limitations; cannot substitute for legal review.
- **Licence Reviewer:** accepts artifact obligations and required licence/NOTICE/attribution handling; this is an engineering evidence role, not automatic legal clearance. Questions requiring legal judgment are hard-blocked pending qualified approval.

Every task handoff contains: task/stream ID; baseline and intended change boundary; versioned input interface/fixture identifiers; proposed API/state changes; deliverable/diff or document artifact; test/evidence plan and later results with environment; redaction check; unresolved blockers/risks; requested receiving roles and their verdicts. Producer submits, SIO checks compatibility and serial integration, accepting reviewer records accept/reject with artifact revision. A blocked/missing prerequisite is not 'done'; an optional omitted task is marked NOT SELECTED with disabled/unclaimed feature, never passed.

## 4. Consolidated assignable workstream contracts

Every task belongs to exactly one delivery stream; cross-cutting SIO/review duties do not create a second implementation owner. Objective exits below are FUTURE implementation/delivery gates, not claims that work has run now. Baseline detailed per-task acceptance remains required unless explicitly replaced in section 7.

| Stream / tasks | Accountable delivery role | Inputs / hard entry | Handoff artifact and deliverable | Receiving integrator / accepting roles | Boundary and exclusions | Objective exit |
| --- | --- | --- | --- | --- | --- | --- |
| WS-A Foundation: 1,2,3 | Foundation Implementer | Pinned baseline/source contracts; Task1 acceptance before Task2/3 integration | Redacted fixture pack with provenance; versioned lifecycle states/events/cancellation/generation interface and unit tests; capability allowlist/error-copy contract and fixture tests | SIO receives; Contract Reviewer accepts1/2; Android Networking Reviewer accepts3 | Defines shared interfaces and Mac capability, not socket/UI transport, HTTP swap or broader server support | Task1 payload provenance/redaction accepted; Task2 plan scenario table covered by deterministic unit tests; Task3 fails closed on helper/private-api/unsupported version; foundation create regression and build/test evidence accepted |
| WS-B Live incoming: 4,5,6,7 | Incoming Lifecycle Implementer | Accepted2+3, relevant1 fixtures;4→5→6→7 integration order; triggered14 before deps | Process-socket wrapper/state evidence; both ingress parsers; separate-identity dedup; answer/leave adapters using existing HTTP facade; UI binding and complete scenario receipts | SIO receives/integrates shared files; Contract Reviewer + Android Networking Reviewer accept lifecycle/network; QA Reviewer accepts scenario packet | Implements accepted lifecycle, no second state machine, FGS/push, OkHttp migration or identity guessing | All4–7 acceptance met together, including pending-answer hangup/late-body/config cases, wrong UUID, unrelated UUID replacing NLS, recreation,30s expiry,no replay and NLS fallback. Partial4–6 never closes M2 |
| WS-C Advisory and regression: 8,9 | Quality/Advisory Implementer |8 needs3;9 final acceptance needs6–8, therefore completed7; tests may be authored early | Advisory UI/network adapter; info/create/answer/leave/availability and parser/lifecycle regression suite; redacted log sample and evidence index | SIO receives shared production/test seams; Android Networking Reviewer accepts8; QA Reviewer accepts9 privacy/regression | No new support range, create blocker, secret migration or lifecycle redesign | false/500 advisory cannot uniquely block create; rawURL path unchanged; JVM/build receipts plus no-password sample accepted; bugs return to responsible stream |
| WS-D Optional delivery policy/push: 10,11 | Background/Push Implementer |10 final decision follows accepted7–9 checkpoint;11 requires affirmative10 approval + accepted2,6,7 + triggered14 | Written foreground-only or exact approved background policy; if selected, push parser/registration/ingress adapter and per-process-state evidence | SIO receives; Product/Policy Approver accepts10 and feature selection; Android Networking Reviewer + QA Reviewer accept11; Licence Reviewer accepts triggers | No FGS implied by selecting push; no immortal-delivery/force-stop/replay claims;10 adds no production code |10 signed written disposition; either11 explicitly omitted/disabled/unclaimed or accepted parser/build/device-state receipts including reopen-after-force-stop limitation and provider/OEM caveats |
| WS-E Deferred HTTP hygiene:12 | Transport Migration Implementer |Accepted2 and ALL4–7; triggered14 before library landing;9 regression suite preferred input | OkHttp5.5.1 behind unchanged BlueBubblesClient facade; cancellation/redaction mapping and parity evidence | SIO serially integrates network/build changes; Android Networking Reviewer + QA Reviewer accept | No new lifecycle policy, auth model, secret store or changed answer semantics; not prerequisite for4–7 | Create/info/contacts/answer/leave parity, cancellation and no-secret-log evidence; JVM/build receipts accepted; no weakened pending-answer behavior |
| WS-F Compatibility evidence:13 | Device Validation Implementer |Planning independent; results require identified installable build/room behavior and devices; final incoming claims require accepted WS-B | Real-device matrix with device/OS/WebView/build identifiers and pass/fail/notes for admission,cam,mic,navigation/leave,reconnect,PiP; limitations recommendation | SIO receives defect/interface impacts; QA Reviewer accepts evidence; Product/Policy Approver accepts claim limits/remediation | Does not silently switch to Chrome or certify WebView/Apple/accessibility; emulator not substitute | Every applicable cell has evidence or explicit blocker; failures fixed/retested or feature/claim blocked; no WebView compatibility claim released while supporting cells unverified |
| WS-G Licence/claims gate:14 | Licence Inventory Implementer |Runs from start; exact added/copied artifacts and transitive inventory before each triggered landing | Version/SPDX/provenance/licence/NOTICE inventory, obligations checklist, required licence/attribution changes and claims review | SIO receives dependency/copy gate; Licence Reviewer accepts obligations; Product/Policy Approver receives legal/claims blockers | Not optional-last; no certified clean-room or legal opinion; no blanket licence clearance for future deps | Every introduced library/test tool or copied/substantially extracted source has accepted obligations BEFORE landing (may be earlier in same atomic change); unassessed artifact blocks that change; refresh on each addition/version change |

## 5. Authoritative per-task dependency and gate table

'HARD' means accepted output required before that task integrates/closes, including all transitive requirements. 'Preferred authoring' permits draft designs/tests earlier; it never relaxes HARD gates. Conditional gates apply whenever their condition occurs, including test-only dependencies. Serial order is allowed; no parallel throughput requirement is imposed.

| Task / owner | HARD integration prerequisites | Preferred authoring inputs (not extra hard edges) | Conditional gate / accepting handoff |
| --- | --- | --- | --- |
|1 / WS-A |None |Pinned upstream and redacted real captures |Missing real evidence blocks fixture acceptance; use attributed redacted samples, never invented payloads. Contract Reviewer accepts fixture pack |
|2 / WS-A |1 |State-machine drafts may start before fixture acceptance |Contract Reviewer accepts lifecycle interface/tests; SIO freezes shared contract before4–7 |
|3 / WS-A |1 |Can draft alongside2 |Networking Reviewer accepts pinned1.9.12 gate/copy; SIO serializes any shared UI/client wiring |
|4 / WS-B |2,3 |Socket design against frozen interfaces |14 for Socket.IO/deps; SIO integration + networking acceptance; no FGS |
|5 / WS-B |4 (transitively1–3) |Parser and UI drafts from1+2 |Contract/networking acceptance of separate identities and unrelated-UUID case |
|6 / WS-B |2,3,5 |Transport-independent answer tests |14 if test/runtime dependency introduced; networking/contract acceptance of generation cancellation |
|7 / WS-B |6 |Leave/status and pending-answer tests can be drafted with6 |Required before M2 complete AND before12; contract/networking acceptance of all terminal paths |
|8 / WS-C |3 |Advisory mocks may be authored after fixture specification |SIO serializes MainActivity/client edits; networking acceptance of nonblocking semantics |
|9 / WS-C |6,7,8 |QA plans and test drafts start with2; suite preferred before12 |14 for new test deps; QA acceptance requires full regression/redaction packet, not just test filenames |
|10 / WS-D |Accepted7–9 checkpoint for final disposition |Policy research/design can start earlier |Product/Policy Approver written decision before ANY FGS/FCM/UnifiedPush code; foreground-only is valid disposition |
|11 / WS-D |Affirmative10 approval,2,6,7 |Push schema research can precede approval; no production code |14 before deps/copy; approved provider/policy and process-state evidence; otherwise NOT SELECTED |
|12 / WS-E |2 and4,5,6,7 ALL accepted |9 tests/log policy preferred; facade comparison can be drafted earlier |14 for OkHttp/test deps; no HTTP-library landing before4–7, even if2 accepted |
|13 / WS-F |None to plan; identified build/device for execution |Existing rooms; WS-B scenarios if incoming claimed |QA evidence+Product/Policy claim decision; unverified compatibility blocks affected claims, not unrelated fixture authoring |
|14 / WS-G |None; recurring gate from project start |Dependency/source proposals from every stream |Before ANY new third-party dependency or source copy/substantial extraction lands, including1–9 test libraries; Licence Reviewer accepts per-artifact packet |

### Safe parallelism and shared-file sequencing

Task1→Task2 is a HARD integration edge (chosen over the old 'preferred'). Drafting Task2 in parallel with capture is safe only as unaccepted authoring. Task2 and3 may author concurrently after1, but both route shared-file changes through SIO. Task8 can author after3 alongside WS-B, with serialized MainActivity/BlueBubblesClient integration. WS-F planning and WS-G inventory can begin immediately. WS-D policy research can begin early, but approval/integration gates do not move.

Each stream supplies an isolated proposal plus input-contract version. No two streams independently integrate MainActivity, BlueBubblesClient, Gradle/catalog, manifest, Application or lifecycle/controller/socket interfaces. SIO keeps one queue, checks interface compatibility, integrates one coherent package at a time, and obtains the specified specialist verdicts before advancing. Stale proposals must rebase conceptually onto the accepted interface revision and re-review; parallel authoring is NOT parallel integration. Review rejection returns to producer; unresolved interface conflicts block consumers. No required git operation is prescribed by this standalone document.

## 6. Milestones, evidence and unresolved HARD blocks

| Milestone | Required tasks / completion boundary | Evidence receiver / closer |
| --- | --- | --- |
|M0 Planning |Both named reviewers accept this same frozen overlay+manifest; roles defined but unassigned |Parent records separate Karen and Code Auditor verdicts; no implementation authorization inferred |
|M1 Foundation |1–3 accepted; lifecycle/unit evidence, capability matrix, outgoing-create regression; still existing HTTP |SIO receives; Contract/Networking Reviewers accept; Product owner checkpoint before socket work |
|M2 Complete live incoming |ALL4–7, not4–6; foreground/process-alive noFGS path, hangup/config/late-body/wrongUUID/separateNLS scenarios complete |SIO assembles scenario packet; Contract+Networking+QA acceptance; product checkpoint.4–6 may be labeled partial only |
|M3 Advisory/regression |8–9 plus M2; availability advisory and redaction/regression evidence |SIO receives; Networking/QA accept |
|Deferred selected options |10 written disposition;11 only if approved;12 only after4–7;13 evidence whenever claims require it |Approvers as section4; omissions explicitly disabled/unclaimed.14 is recurring gate, NEVER optional M4 |

For future implementation milestones, evidence packet includes exact artifact/build revision, interface versions, environment, task-by-task criteria results, `:app:test` and `:app:assembleDebug` receipts for affected implementation delivery, manual/network scenario evidence where baseline calls for it, privacy review, known defects/limitations and reviewer verdicts. Unit tests are not device compatibility evidence. Tasks1–9 do not acquire a blanket instrumented-test requirement; existing gated harness restrictions remain. Task13 requires real-device results. Task10/14 documentation decisions need review, not fake build receipts. No commands above were executed for this addendum.

Hard blocks that remain explicit:

1. Both same-hash reviewer acceptances are pending. No planning acceptance claimed.
2. Accountable roles are unassigned: assign before implementation. Evidence/fixtures not yet delivered cannot be marked accepted.
3. Missing helper/private API or unsupported server blocks create/answer/leave; broader support stays blocked until explicit evidence decision.
4. Any unassessed new dependency/copy blocks its landing until Task14 obligations accepted; unresolved legal judgment blocks affected use/distribution. Source rewrite is not legal clearance.
5. No background/push/FGS code before Task10 approval, and no claimed Play eligibility without policy case. Default process-alive/noFGS scope is settled, not waiting on killed-app delivery.
6. WebView outcomes remain unknown. Relevant compatibility/release claims wait on Task13 evidence and Product/Policy acceptance; no failure is relabeled Apple support. Default WebView stays until separate approved change.
7. Full-screen-intent permission/policy and distribution route require explicit decisions before affected distribution/background claims. Sideload is not automatic policy clearance.

Beeper fallback retention defaults to preserving existing NLS behavior, not deleting it as an undocumented choice. Availability remains nonblocking. Product may propose changes later, but they cannot waive the current contract silently. Future release approval is separate from planning and implementation milestone acceptance.

## 7. Precise supersession and reconciliation map

Paths below are canonical baseline paths. Line ranges refer to the five supplied exports; section headings/task numbers are the stable anchors. This table does NOT claim source edits. On adoption, replacements take precedence over every historical instance of the listed rule, including summaries/indexes. Unchanged technical requirements in those sections remain binding.

| Baseline path / exact section and exported lines | Historical rule/conflict | Replacement and precedence |
| --- | --- | --- |
|`tasks/plan.md` Overview L17; Dependency table Task12 L414; Hard gates L420; Phase4 L483; `tasks/todo.md` intro L13, Task12 L398–426, Explicitly not tasks L509 |4–7 before12 versus2 required/7 preferred or only2 blocked |Section5 Task12: accepted2 AND ALL4–7 HARD before integration. All weaker summaries superseded;9 preferred only |
|`tasks/plan.md` Dependency table Task2 L404; `tasks/todo.md` Task2 Dependencies L71 |1 hard versus preferred |Section5 Task2:1 HARD integration; only drafting can start earlier |
|`tasks/plan.md` Assumptions L23, Stable table L67, Capability row L405, Open questions item4 L445; `tasks/todo.md` Task1 description L19 and Task3 L85–109 |Pinned target versus documented older/default minimum1.9.0 and open1.9.9 |Section2.1 settles support: only pinned1.9.12; all other/unknown versions unsupported pending explicit evidence decision. No accidental compatibility-range reading |
|`tasks/plan.md` Feature priority L378,L383; Milestones L391–395; Phase2/checkpoint/Phase3 L463–477; `tasks/todo.md` After4–6 L223–229, Task7 L233–260, Hygiene checkpoint L322–327, Complete L490 |M2=4–6 while hangup/config completion requires7;7 hygiene;14 optionalM4 |Section6 M2=4–7;4–6 only partial;M3=8–9. Task7 mandatory pending-answer closure, not deferred hygiene. Priority labels never waive requirements |
|`tasks/plan.md` Licence L78–97, Architecture decision10 L368, Feature priority L383, M4 L395, Task14 L416, Hard gate L422, Open question3 L444, Phase4 L485; `tasks/todo.md` Task4 risk L154, Hygiene checkpoint L327, Task12 risk L426, Task14 L461–484 |Strong before-deps rule conflicts optional-last/human-decides14 and samePR phrasing |Sections4/5 WS-G:14 recurring from start before any dependency/test library/copy lands, with accepted per-artifact obligations. Same atomic change allowed only with obligations accepted before landing; not later follow-up. Licence decision cannot postpone mandatory obligations |
|`tasks/plan.md` Milestones+Dependency/acceptance L385–423; `tasks/todo.md` Tasks1–14 L17–484, especially2/3 L50–109 and shared4–7 L123–260,12 L398–426 |Task descriptions without accountable delivery/receiving/accepting roles; parallel2/3 treated as safe shared-file work |Sections3–5 consolidated streams and per-task mapping overlay ALL14 tasks: role owner, inputs/artifact, exclusions, SIO and independent receiving reviewers; serial shared-file queue, not independently integrating authors |
|`tasks/plan.md` Call lifecycle Process vs Activity L211–218 |'Owner' could be mistaken for engineering assignment |Runtime-lifetime table preserved; engineering accountability is section3 SIO and section4 stream roles, not Activity/process labels |
|`tasks/plan.md` Background assumption L28, default L299–301, Open questions1/2/5/6 L442–447; `tasks/todo.md` Task10 L331–353,11 L357–394 |Optional unresolved decisions could be mistaken for authority to implement |Sections2/5/6: settled noFGS process-alive default, preserved NLS and advisory create;10 hard approval before background code, distribution/legal claims hard-blocked as applicable |
|`README.md` Documentation index L48–59; `tasks/README.md` index L5–11; `tasks/todo.md` companion/checkbox status L3 and Complete L488–494 |In-tree plan/todo presented as entire work-package authority |After both accept, THIS independent overlay governs reviewed workstream scope over baseline conflicts; root README's current-shipped behavior is unchanged. Future task checkboxes require evidence, not documentation acceptance |
|`tasks/audit-resolution.md` intro/status L3–5, finding-response rows L9–19, Findings accepted L21–23, re-review/history L36–38 |Historical 'fixed/accepted' text could be mistaken for present workstream approval |Historical technical response/provenance retained; not either reviewer's acceptance of this overlay. Only same-hash explicit Karen AND Code Auditor verdicts close M0 |
|`tasks/README.md` Local Mac transfer L13; `tasks/plan.md` Local transfer note L501–503; `tasks/todo.md` Explicitly not tasks L515 |Historical Cursor artifact/transfer workflow |No transfer/copy instruction is authorized or executed here. Standalone files stay under `/workspace/workstream-contracts/`; no repository/local Mac incorporation implied |
|`README.md` Verification L424–437; `tasks/plan.md` What this revision verified L487–499 |Earlier checks/settings may be read as current verification |Historical claims only, not re-certified by this work. No fresh authenticated rendering/Desktop/local-sync/runtime/legal proof; baseline export hashes prove bytes only |

All other baseline technical detail (including task-specific errors, URL gates, cancellation, REST/event mapping, state matrix, redaction, licence obligations and verification qualifications) is preserved. If a reviewer finds an unmapped genuine contradiction, stop affected assignment and amend/freeze/re-review this overlay rather than infer a waiver.

## 8. Review submission and result ledger

Submission consists of this file plus `baotime-baseline-manifest.json`; parent supplies their SHA256 values from the companion checksum output. Reviewers assess the same standalone artifact and exact five exported inputs. No repository revision is claimed.

| Reviewer | Exact overlay SHA256 | Exact baseline manifest SHA256 | Verdict / scope |
| --- | --- | --- | --- |
|Karen |Pending |Pending |PENDING independently assignable planning contracts |
|Code Auditor |Pending |Pending |PENDING dependency/coordination and preserved technical scope |

Acceptance must be recorded externally against frozen hashes, not by editing this table to self-certify. Both must be satisfied; any required correction creates a new frozen artifact for both. Even dual acceptance is planning-only, not build/device/security/accessibility/legal certification, merge permission, or permission to start implementation.
