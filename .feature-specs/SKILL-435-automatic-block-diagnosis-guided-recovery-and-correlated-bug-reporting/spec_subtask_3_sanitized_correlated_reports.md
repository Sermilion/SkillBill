# Subtask 3: sanitized correlated product-defect reporting

## Scope

Add proposed BlockBugReportService with pure sanitizer/fingerprint decisions and injected transport ports. Persist report intent/results in the incident lifecycle. Extend existing lifecycle telemetry contracts/pipeline. Recovery and report delivery operate independently.

## Acceptance Criteria

1. Every public issue create/update accepts only bounded allowlisted sanitized content with persisted redaction/omission outcomes and retains private evidence locally.
2. Evidence-supported Skill Bill defects automatically prepare and submit a report to the authoritative product repository, including actual incident/run provenance and honest cause/evidence limits even when recovery succeeded.
3. Durable fingerprinted submission intents and observation hashes reconcile matching issues, restarts and ambiguous network outcomes without blindly repeating creates or updates.
4. Missing auth, service failures, permission refusal, rate limits, sanitizer refusal and exhausted retry retain a local report with specific independent delivery state and bounded scheduling.
5. Diagnosis, recovery and issue telemetry use actual shared correlation identities and settings-aware delivery evidence, with exact search identifiers or an authoritative configured PostHog link and no fabricated ingestion claims.

## Sanitization and report requirements

Default public allowlist is generated IDs, timestamps, runtime build/version, OS/provider, selected strategy/model, original failure code, bounded product-owned class/stack locations, category/confidence, safe symptom/impact, expected/actual behavior, known reproduction steps and recovery type/result. Raw project source, prompts/briefings, full tool arguments/output, credentials/tokens/headers, raw environment, personal paths, checkout names and whole DB contents are excluded. Private references remain local. Before any excerpt leaves the machine, redact secret patterns, personal/path content and URL queries; reject uncertain excerpts. Record redaction/omission reason and counts without the secret value. Default report body cap is 16 KiB UTF-8 with at most 4 KiB sanitized excerpts and 1 KiB per excerpt. Metadata-only reporting remains useful when all text is omitted. Sanitizer refusal never causes raw fallback submission.

Report title/body state symptom and impact, expected versus actual, supported cause or suspected hypothesis/evidence gap, reproduction only when known, version/build, platform/provider/phase strategy/model, error code, sanitized bounded evidence, timestamps, actual correlation IDs and recovery result. Recovery success never suppresses a supported product defect. Unknown/project/environment/provider causes do not automatically become product defects. A deterministic invariant violation or corroborated product failure evidence must support the classification; model suggestion alone is insufficient.

Extract/share GitHubReleaseCatalogAdapter's numeric repository 1183084383 as authoritative product identity. Human name Sermilion/skill-bill is informative; do not derive target from project remotes. A release mirror override affects artifact acquisition and does not redirect reports. Inject a product GitHub client using existing RemoteTransportPort/process credential infrastructure, with permission checked without revealing secret values. Implement confirms the available provider/account/write scope; unavailable auth yields a retained local report and specific instructions. Do not add another report-target setting or assume permission exists. All calls in verification use fakes or a controlled fixture; this planning invocation files no real issue.

## Delivery state and deduplication

Persist sanitized payload/hash, product failure fingerprint, stable submission marker, incident observation IDs, update hashes, retry count/next time, claim token/generation, ambiguous state and remote issue identity/URL before network effects. Fingerprint stable product failure family/code plus causal evidence/version, excluding volatile paths/timestamps. Distinct incidents may share one bug. Serialize local claims and reconcile the product repository using fingerprint/marker before create and after ambiguous outcomes. A possible accepted POST with no confirmed absence stays unconfirmed; do not retry creation blindly because search may lag. Cross-install marker reuse reduces duplication but cannot promise GitHub exactly-once semantics under eventual consistency. Suppress already-attached incident/recovery observation hashes; append newly sanitized recovery outcome once.

States distinguish local/pending/submitting/linked/delivered/unconfirmed/missing_auth/offline/permission_refused/rate_limited/sanitizer_refused/failed/exhausted. Default is at most three transport attempts per delivery intent in 24 hours with 1-minute and 15-minute exponential steps; honor Retry-After without extending the deadline. Permanent permission or sanitizer refusal needs correction before an explicit recheck/new safe intent. Unknown acceptance permits bounded reconciliation searches only, never another create until absence is established. No background tight loop. Record each fallback/retry through existing diagnostics. Report failure cannot alter recovery availability or the primary block.

## Correlation and telemetry

Use LifecycleTelemetryService settings-aware writes, TelemetryOutboxDrain bounded claim/settlement semantics and HttpTelemetryClient's configured proxy. Extend canonical telemetry event schema, version/parity requirements, TelemetryOutboxEvent wire names and LifecycleTelemetryPayloadKeys. Use shared owning key constants and existing enum wireValue. Persist a stable incident ID plus actual available goal, parent workflow, subtask, workflow, phase-run, invocation/execution, attempt/generation, diagnostic and recovery IDs with explicit availability. Retain lifecycle sessionId identity, including actual ftr-prefixed sessions; do not invent replacements. Keep identical correlation values in diagnosis, report and events without collecting extra private information.

Expose disabled, unavailable, pending, unconfirmed, accepted-by-relay and failed based on settings/outbox evidence. Delivered is scoped to the actual acknowledged transport; relay acceptance never asserts PostHog ingestion. Proxy URL is not a PostHog browser project. Default UI/report offers exact identifier search instructions. Form a direct lookup URL only from authoritative configured browser host/project information; lack of that configuration is expected. Telemetry absence cannot block diagnosis, recovery or GitHub delivery. No new telemetry side channel or bypass of current level/redaction policy.

## Test Obligations

- T3.1 catches public leakage. Pure sanitizer boundary fixtures include tokens/headers/private code/prompts, personal paths, checkout names, URL queries and oversized excerpts. Assert public payload excludes them, records safe omission counts, rejects uncertainty and never falls back to raw content on create or update.
- T3.2 catches wrong repository and unsupported accusations. Fake transport observes numeric product target despite unrelated project remotes/release mirrors; supported defect includes required safe provenance and suspected wording, recovery success still reports, unknown-only/model-only diagnoses do not auto-submit.
- T3.3 catches duplicate public issues/updates after accepted-but-lost POST and restart. Use durable real SQLite intent/claims and fake eventually consistent GitHub search; matching issue reuse, concurrent reports, repeated observations and recovery updates produce stable links without blind re-create or duplicate updates. Unconfirmed absence stays unconfirmed.
- T3.4 catches dropped local reports and runaway retries. Fake clock/transport covers missing auth, offline, 403/refusal, 429/Retry-After, permanent failure and exhausted retries. Assert bounded attempts/deadline, specific local reasons, original incident/recovery unaffected and no secrets in diagnostics.
- T3.5 catches false telemetry claims and broken joins. Settings/outbox fixtures cover disabled/unavailable/pending/unknown acknowledgement/relay acceptance/failure, actual nullable identity joins and version/schema parity. Assert no invented PostHog URL/ingestion status and no telemetry prerequisite on local or GitHub work.

## Non-Goals

No full log upload, issue creation in user projects, new auth manager, independent telemetry pipeline, hard-coded browser project or exactly-once promise across GitHub installations. No real submission during planning.

## Dependency notes

Depends on subtasks 1 and 2 for durable intent/claims, classification, immutable identity and recovery history. Reporting consumes classification without controlling workflow mutation. Root wiring remains RuntimeComponent; HTTP adapters implement transport only. Append any additional migration after the current tail, never rewrite the migration from subtask 1.

## Next path

Runtime plans subtask 4 to present the saved local/report/correlation states. Public submission is future product behavior, not an action authorized in this planning invocation.

## Governing constraints

Planning workflow is wftr-20261021-055741-uosu. This bundle is planning-only until a later explicit implementation request. Use the active workflow checkout and branch. Preserve unrelated SKILL-429 coordinator-admission/carryover changes and the SKILL-434 bundle. File names below identify owners, not an exclusive repair allowlist. Required later review and validation repairs may touch production wiring, test setup, formatting and lint while preserving behavior and assertions.

The preplan digest is the sole repository evidence for this plan. Names marked proposed are new APIs, not installed-runtime capabilities. Implement must confirm mutable integration facts within its assigned authority. No implementation, test execution, build, public issue submission, commit, push or goal launch occurs during this planning phase. Runtime-owned review, history, publication and settlement remain outside implement acceptance criteria.

Apply architecture-guidelines A1 through A12, section 5, G1 through G7 and P1 through P8, Runtime Architecture Design Principles, code-principles, runtime-command-guidance and observability-policy. The digest supplies their application here: narrow capabilities and authority, one coupled transition owner, explicit resource cleanup, deleted duplication rather than moved duplication, no policy in adapters, no second composition root, append-only persistence preserving records, no new guard exemption/baseline, and observable boundary proof. Runtime-domain owns types/pure policy, runtime-contracts owns shared serialized vocabulary, runtime-ports owns consumer I/O contracts, runtime-engine/application own use cases, runtime-infra owns mechanics. Only skillbill.di.core.RuntimeComponent under runtime-core/src/main/kotlin/skillbill/di composes them. Never introduce an all-access strategy context, raw runner/workflow mutator or callback bag. Kotlin source follows the repository comment restrictions.

## Validation Strategy

Only validate executes tests and full project checks; only build supplies buildability proof. Plan, implement and audit inspect production end states without invoking proof commands. At validate, resolve affected gates through runtime/manifest routing and include runtime-kotlin `./gradlew check --no-build-cache` and `scripts/validate_agent_configs`. Desktop changes also require all affected module checks, Linux Xvfb where needed, `./gradlew clean check createDistributable --no-build-cache --no-configuration-cache` under desktop-tray and `packagedDataSmoke` as required by desktop CI. Keep actual evidence and failures in validate outputs, never claim future checks already passed. Review applies the section-5 checklist with rule IDs and the existing PrincipleEnforcementInventory, StrategyCapabilityBoundaryArchitectureTest, RuntimeCompositionGuardArchitectureTest, WireVocabularyArchitectureTest, CommentAndInterfaceKdocArchitectureTest, InlineFqnArchitectureTest and ProductionFileLineCeilingArchitectureTest. Any new mechanical guard needs acceptance and rejection fixtures proving a real boundary. Preserve existing regression assertions. Do not write layout-pinning or incidental-prose tests.


## Implementation Details

Execute the following tasks in order after subtasks 1 and 2 supply their durable incident and recovery contracts. Each task produces repository end states for later audit. Test obligations name future coverage; only validate runs tests and checks. No proof commands run in this phase or implement. Existing sections above remain authoritative.

### 1. Define report state, identity and consumer capabilities

Serves acceptance criteria 2 through 5. Extend the proposed blocked-incident family in runtime-domain with immutable report input, product failure fingerprint, submission marker, sanitized observation identity, delivery state, retry policy and typed transport outcomes. Keep report eligibility separate from recovery eligibility. Consume the saved classification, evidence strength and recovery history from subtasks 1 and 2. A model hypothesis alone cannot authorize automatic submission, and recovery success cannot suppress an eligible defect.

Extend the area-owned BlockIncidentRepository and bounded report capabilities in runtime-ports. Proposed operations prepare an intent, claim or renew delivery, record reconciliation, compare-and-set progress and settle a result. Requests bind the selected store, incident identity/revision, report intent, claim token/generation and observation hashes. Expose no raw mutable database session to the report worker. Share the existing incident correlation envelope rather than recreating IDs from prose or matching error messages.

Paths and symbols are the proposed incident types under runtime-domain, BlockIncidentRepository under runtime-ports, and the incident contract family under orchestration/contracts established by subtask 1. Extend its canonical Draft 2020-12 schema, version pin, parity fixtures and typed RuntimeFailureCode/SkillBillRuntimeException parse seams for new report sections. Apply owning Keys constants and enum wireValue at serialized boundaries. Reject malformed producer, stored and imported report records through the existing incident quarantine/degradation path without replacing the original block.

Assumption for implement to confirm: upstream incident storage and scheduling can hold report claims and deadlines independently of diagnosis/recovery. If not, extend that narrow area contract rather than introduce a generic queue or second lifecycle owner. Preserve nullable coordinates and explicit availability, including real standalone invocation/execution IDs and lifecycle session IDs.

Test obligations: T3.2 proves classification eligibility and recovery independence; T3.5 includes mandatory contract/version parity and actual correlation joins. Add no getter or helper-layout tests.

### 2. Build the public-payload sanitizer and stable fingerprint policy

Serves acceptance criteria 1 through 3. Add pure area-owned sanitizer, report-content and fingerprint decisions in runtime-domain. BlockBugReportService consumes their typed result before any create or update can reach transport. Use explicit allowed fields, the 16 KiB body cap, 4 KiB total excerpt cap and 1 KiB per-excerpt cap, measured as UTF-8 bytes. Redact secrets, personal paths, checkout names and URL queries before accepting excerpts; omit uncertain text or refuse unsafe payloads. No raw fallback is permitted. Keep safe omission/redaction reasons and counts while retaining private evidence references locally.

Build symptom/impact, expected/actual, known reproduction, supported or suspected cause, evidence gaps, original code, runtime/platform/model provenance, timestamps, actual identities and recovery outcome from saved facts. Treat log instructions and agent text as evidence, never execution authority. Unknown reproduction or cause stays explicitly unknown. Metadata-only content is permitted when safe excerpts are absent. Avoid transporting private prompts, source, whole tool output, environment dumps or database contents.

Fingerprint stable product failure family/code, causal evidence and version. Exclude timestamps, local paths and incidental text. Give each submission a durable marker and each incident/recovery attachment a stable sanitized observation hash. Define canonical serialization so restart computes the same identities without exposing private material.

Paths are proposed reporting policy types in runtime-domain and BlockBugReportService under runtime-engine diagnostics/block, using the existing module dependency direction. Adapter code receives a validated public payload type, not arbitrary diagnostic text.

Test obligations: T3.1 catches leaks through both create and update, including oversized multibyte content, uncertain excerpts and a failed sanitizer. T3.3 catches volatile fingerprints or observation hashes that duplicate an issue/update after restart. Reuse fixtures across transport paths rather than duplicate the sanitizer test matrix.

### 3. Persist delivery intent and reconcile network uncertainty

Serves acceptance criteria 1, 3 and 4. Implement connection-bound report persistence through UnitOfWork, DatabaseSessionFactory and SQLiteUnitOfWork in runtime-infra/sqlite. Store the safe payload/hash, fingerprint/marker, linked observations, claim generation/deadline, attempt count, next eligible time, reconciliation state and issue identity/URL before external effects. Use uniqueness constraints for fingerprints, intent identity and attached observation hashes. Database mutations and progress markers share the existing transaction owner; network calls occur outside write transactions. Reject stale claims when settling results.

Append any required migration after the then-current tail in DatabaseMigrationEntries.kt. Never rewrite an upstream migration or assume version 66 remains available. Preserve existing runs, incident history and pending recovery actions. Add applicable goals/phases invalidation triggers in the new migration through core/schema/DesktopChangeRevisions.kt, not solely through historical migration-52 code. Keep DesktopReadSchema compatibility consistent if the runtime read surface changes. Prefer the existing runtime detail projection over a second writable desktop store.

Implement report orchestration in proposed BlockBugReportService under engine/diagnostics/block. Reuse the durable incident worker scheduling/reconciliation from subtask 1, with separate delivery claims and retry state. On start/restart, reconcile pending, expired and unconfirmed intents. Search the product repository by fingerprint/marker before create and after any ambiguous acceptance. Reuse matching issues and suppress acknowledged observation hashes. If search cannot establish absence after a possibly accepted request, retain unconfirmed state and perform bounded reconciliation only. An ambiguous update also requires marker/hash reconciliation before repetition. Do not promise global exactly-once delivery across installations.

Use the declared three-attempt, 24-hour policy with 1-minute and 15-minute steps and Retry-After bounded by the deadline. Count ambiguous transport attempts durably; bounded searches must have their own named finite budget within that deadline. Assumption for implement to confirm: use at most three reconciliation searches per unconfirmed intent in that window, then retain unconfirmed with an explicit recheck path. Do not convert exhausted uncertainty into permission to recreate. Permanent permission and sanitizer failures await correction. Record degraded scheduling, retries, cancellation and exhausted claims through existing RuntimeDiagnostics without recursively diagnosing report-worker failures. Primary block and recovery state remain intact.

Test obligations: T3.3 uses real SQLite plus fake transport to catch concurrent claims, accepted-but-lost POST, restart, lagging search, matching reuse and duplicate observation updates. T3.4 catches deadline/Retry-After loops, lost local reports and stale claim settlement. Extend DatabaseMigrationsTest with an upgrade preserving existing runs and pending intents; verify committed invalidation and no rollback publication at the existing SQLite boundary.

### 4. Wire authoritative product transport and automatic submission

Serves acceptance criteria 2 through 4. Extract a shared product repository identity from runtime-infra/http GitHubReleaseCatalogAdapter's numeric repository 1183084383 and consume it from both release lookup and the injected report adapter. Preserve release behavior. Treat the release mirror override as artifact-acquisition configuration, never report authorization or destination. No current project remote or new report-target setting selects the repository.

Add a narrow product GitHub capability in runtime-ports and HTTP/process mechanics in runtime-infra, using RemoteTransportPort and existing credential infrastructure as applicable. Implement confirms the available provider/account/write scope without printing credentials or adding an auth manager. Missing auth remains a local report state with instructions. Validate remote issue identity/link against the product target before saving or presenting it. HTTP results distinguish confirmed acceptance, unknown acknowledgement, missing auth, offline, refusal/403, rate limit/429 and other permanent/transient failures. Close response/process resources and preserve cancellation.

Trigger report preparation automatically when a saved diagnosis has evidence-supported Skill Bill defect classification. Also consume newly saved recovery outcomes for eligible reports without making delivery a recovery prerequisite. Compose the service, worker and transport only under runtime-core/src/main/kotlin/skillbill/di, through skillbill.di.core.RuntimeComponent and narrow providers. HTTP, CLI, desktop and SQLite adapters neither classify defects nor decide retries. Reconcile only queued/new incidents or the upstream explicitly marked bootstrap occurrences; do not scan historical blocks into a public-report flood.

Test obligations: T3.2 catches misrouting to a user repository/release mirror, unsupported accusations and suppression after successful recovery. T3.4 uses fake transports for auth/offline/403/429 and verifies safe local instructions and independent recovery. All submission coverage uses fakes or controlled fixtures, never the real product issue endpoint.

### 5. Extend shared telemetry and saved report projection

Serves acceptance criteria 4 and 5. Extend LifecycleTelemetryPayloadKeys, TelemetryOutboxEvent and the canonical telemetry event contract/version/parity coverage for diagnosis, recovery and report observations. Reuse LifecycleTelemetryService settings-aware writes and TelemetryOutboxDrain settlement states, not a new delivery channel. Carry the same stable incident and actual available goal/parent/subtask/workflow/phase-run/invocation/execution/attempt/generation/diagnostic/recovery/session fields as local diagnosis and reports. Persist unavailable-field reasons without inventing identifiers or collecting private facts for correlation.

Expose saved report link/state/reason, redaction summary, exact correlation search identifiers and settings/outbox-backed telemetry availability through the incident projection handed to subtask 4. Disabled, unavailable, pending, unconfirmed, accepted-by-relay and failed remain distinct. Acknowledgement describes the actual transport only. HttpTelemetryClient proxy settings cannot establish PostHog browser host/project or ingestion. Default to identifier search instructions; enable a lookup URL only with authoritative deployment configuration. Assumption for implement to confirm: no authoritative browser configuration exists, so identifier-only lookup is the initial supported behavior.

Paths include application/telemetry/lifecycle/LifecycleTelemetryService.kt, LifecycleTelemetryRequests.kt, contracts/telemetry/LifecycleTelemetryPayloadKeys.kt, TelemetryOutboxEvent.kt, application/telemetry/sync/TelemetryOutboxDrain.kt and infra/http/HttpTelemetryClient.kt. Prefer extending vocabulary and projecting existing evidence over changing drain mechanics. Telemetry persistence/send failure records secondary degradation and never prevents local diagnosis, recovery or report submission.

Test obligations: T3.5 covers actual identity joins and each availability/acknowledgement branch using existing lifecycle/outbox fixtures. Assert that disabled telemetry still permits local and GitHub work and that proxy-only configuration never yields a PostHog lookup URL or ingestion claim.

### 6. Complete boundary coverage and the implementation handoff

Serves acceptance criteria 1 through 5. Consolidate the T3.1 through T3.5 scenarios beside owning domain, engine/application, SQLite and transport modules. Name each concrete leak, duplicate effect, dropped report or false correlation claim before adding its test. Preserve existing telemetry and incident regressions. Do not add tests for incidental report prose, class placement or trivial glue.

Implementation retains A1 through A12 end states, narrow authority and one transition owner, with no policy in adapters, duplicate repository identity, second composition root, new exemption or baseline relaxation. Later review applies section 5, G1 through G7 and P1 through P8 with rule IDs. Keep Kotlin comment restrictions and governed wire vocabulary. Use the existing architecture enforcement owners named above; add a guard only for a concrete boundary with acceptance/rejection fixtures.

Hand off to subtask 4 the typed saved delivery/correlation fields, report links, retry/refusal reasons and safe recheck semantics. Presentation consumes those facts and cannot infer classification or delivery from text. Record implementation-confirmed credential and scheduling choices, finite search budget and any safe fallback in the owning plan/decision evidence without changing the scope or acceptance criteria. Validate later owns the declared affected checks, migration/contract parity evidence and all test execution. Runtime-owned audit, review, history, publication and future goal launch remain with their phases. This planning edit supplies no test, delivery or validation receipt.
