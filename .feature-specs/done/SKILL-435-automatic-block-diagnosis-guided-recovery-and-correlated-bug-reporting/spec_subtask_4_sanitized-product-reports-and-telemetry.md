# SKILL-435 subtask 4: sanitized product reports and correlated telemetry

## Scope

File an issue on the Skill Bill product repository automatically for evidence-supported Skill Bill defects, with sanitized bounded content, a local copy, deduplication and delivery reconciliation that survives restarts and ambiguous network outcomes. Emit diagnosis, recovery and report telemetry with actual run identities through the existing outbox, and report telemetry availability truthfully.

This subtask covers intake paragraphs 8, 9 and 10, which make up parent criterion AC-003 and the identity and telemetry parts of AC-005. Reporting runs independently of recovery: a recoverable defect is still reported, and a report failure never blocks diagnosis or recovery.

Credential decision (preplan D2): the parent spec allows existing credentials only, with no prompt, no secret persistence and no anonymous submission. The runtime has no GitHub credential source today, so this subtask adds one narrow port that reads `GH_TOKEN`, then `GITHUB_TOKEN`, then `gh auth token`. No token is ever stored. Absent credentials keep a local report with reason `MISSING_AUTH`.

## Acceptance Criteria

1. A pure sanitizer builds bounded, allowlisted report content and records a redaction outcome for every field.
   A new object `ProductReportSanitizer` in `runtime-domain/.../model/blockincident/report/` takes the incident occurrence, latest diagnosis record, recovery attempt history and runtime facts (runtime version and build, OS family, provider id, phase strategy id and model id) and returns `ProductReportDraft` or `ProductReportRejected`.
   Allowlisted fields: symptom and impact from `summary`; expected versus actual behavior from `whatHappened`; cause or evidence gap from `cause` labelled `Suspected` unless `certainty` is `DIAGNOSED`; reproduction steps only when the diagnosis supplies them; runtime version and build; OS family; provider, phase strategy and model; failure code and disposition; evidence summaries (max 8 items, 512 characters each); observed, diagnosed and recovery timestamps; incident token, workflow id, parent workflow id, phase id, invocation id, attempt and recovery attempt ids; and recovery outcomes. The whole body is capped at 16 KiB.
   Dropped always: `worktreePath`, repository root, prompts, briefing text, producer output bodies, environment variables and any database row content. Redacted to `[redacted]`: home-directory paths (`/home/<name>`, `/Users/<name>`, `C:\Users\<name>`), tokens matching GitHub, OpenAI, Anthropic, AWS and generic bearer or `key=`/`token=`/`secret=` patterns, email addresses and `skillbill.`-excluded stack frames, following the `RuntimeExceptionTelemetry` redaction idiom. Each field records `KEPT`, `REDACTED`, `TRUNCATED` or `DROPPED`.
   After redaction a final scan rejects the draft with `ProductReportRejected(RESIDUAL_SECRET)` when any token pattern remains; a rejected draft is never submitted and is kept locally with that reason.
   The sanitizer also derives `fingerprint` = sha256 of `failureCode:origin:phaseId:normalizedCause`, where `normalizedCause` lowercases the cause, strips digits, hex runs and paths, and never includes run ids.
   Persistence and submission are criteria 2 through 4. Audit confirms by reading the allowlist, the redaction patterns, the residual scan and the fingerprint.
2. Evidence-supported Skill Bill defects create a durable local report with submission intent, and repeated observations deduplicate onto it.
   A new append-only migration (next free version, expected 79) creates `block_incident_reports` (`report_id` `bir_<uuid>`, `fingerprint` UNIQUE, `status`, `failure_reason`, `attempts`, `next_attempt_at`, `issue_number`, `issue_url`, `body` sanitized text, `body_sha256`, `redaction_outcomes`, `created_at`, `updated_at`) and `block_incident_report_links` (`report_id`, `occurrence_token`, `comment_state`) with primary key `(report_id, occurrence_token)`, plus desktop change-revision triggers and a `DesktopReadSchema.VERSION` bump if its rule requires one. Status enum `ProductReportStatus {LOCAL_ONLY, PENDING, SUBMITTING, AMBIGUOUS, DELIVERED, LINKED_EXISTING, FAILED}`; failure enum `ProductReportFailure {MISSING_AUTH, OFFLINE, PERMISSION_REFUSED, RATE_LIMITED, SUBMISSION_FAILED, RESIDUAL_SECRET, RETRIES_EXHAUSTED}`; wire values in the runtime-contracts blockincident keys file.
   A new port `ProductReportRepository` in `runtime-ports/.../blockincident/`, exposed on `UnitOfWork`, offers `createOrLink(draft, occurrenceToken)`, `due(now, limit)`, `transition(reportId, expectedStatus, newStatus, failure, issue)` and `forIncident(token)`, with sqlite and testFixtures in-memory implementations.
   A new `ProductReportScheduler` in `engine/diagnostics/block/report/` runs after the investigation worker settles a diagnosis. It creates a report only when `responsibleCategory` is `SKILL_BILL_DEFECT`, `certainty` is `DIAGNOSED` or `SUSPECTED` with at least one evidence item, and the incident was observed at or after the subtask 1 rollout marker. A matching fingerprint links the new incident to the existing report instead of creating one. Every other category never reports.
   Audit confirms by reading the migration, port, scheduler conditions and fingerprint link.
3. A product issue adapter talks to the authoritative product repository with existing credentials only.
   The numeric repository id `1183084383` moves from the private `RELEASES_URL` in `runtime-infra/http/.../GitHubReleaseCatalogAdapter.kt` into one shared constant `SKILL_BILL_PRODUCT_REPOSITORY_ID` in `runtime-infra/http`, used by the release adapter and the new adapter. No setting can redirect reports, and the user's project repository is never used.
   New port `ProductIssueTrackerPort` in `runtime-ports/.../blockincident/` with `findByFingerprint(fingerprint, token)`, `create(title, body, labels, token)` and `comment(issueNumber, body, token)`, returning typed results `Found(number, url)`, `NotFound`, `Created(number, url)`, `Failed(ProductReportFailure)` and `Ambiguous`. `GitHubProductIssueAdapter` in `runtime-infra/http` implements it over `RemoteTransportPort` against `https://api.github.com/repositories/1183084383/issues`, searching open and closed issues for the body marker `<!-- skill-bill-report-fingerprint:<fingerprint> -->`. Status mapping: 401 and 403 without rate-limit headers give `PERMISSION_REFUSED`, 404 gives `PERMISSION_REFUSED`, 403 or 429 with rate-limit headers give `RATE_LIMITED`, 5xx gives `SUBMISSION_FAILED`, `IOException` before a response gives `OFFLINE`, and a timeout after the request body was sent on `create` gives `Ambiguous`.
   New port `ProductIssueCredentialPort` with `token(): String?`. The `runtime-infra` implementation reads `GH_TOKEN`, then `GITHUB_TOKEN`, then runs `gh auth token` with a 5-second timeout and no shell, and returns null when none succeed. It never logs or persists the token.
   Both ports bind only in `skillbill.di.core.RuntimeComponent`.
   Audit confirms by reading the shared constant, both adapters, the status mapping and the bindings.
4. A delivery reconciler submits due reports with bounded retries and resolves ambiguous outcomes before any new create.
   `ProductReportDelivery` in `engine/diagnostics/block/report/` runs after the scheduler and from `BlockIncidentEntryReconciler` at entry, at most 4 reports per run. For each due report it moves `PENDING` or `AMBIGUOUS` to `SUBMITTING`, then always calls `findByFingerprint` first: a found issue sets `LINKED_EXISTING` (or `DELIVERED` when this runtime created it) with its number and url, and `create` runs only after `NotFound`. A null credential sets `LOCAL_ONLY` with `MISSING_AUTH` and schedules no retry until the next entry finds a credential.
   Each linked incident adds at most one comment carrying its sanitized incident and recovery evidence, tracked by `comment_state` and a `<!-- skill-bill-incident:<token> -->` marker, so restarts never post duplicate updates. When a recovery attempt for a linked incident reaches a terminal status after the report was delivered, its outcome is added to that incident's single pending comment if not yet posted.
   Retries: at most 3 attempts per report with backoff of 1 minute, 10 minutes and 1 hour stored in `next_attempt_at`; `RATE_LIMITED` honours the reset header when present. After the third failed attempt the report becomes `FAILED` with `RETRIES_EXHAUSTED` and keeps its body locally. A `SUBMITTING` row whose process died returns to `AMBIGUOUS` on the next entry and is reconciled by search.
   Delivery never touches recovery attempts, incident diagnosis state or workflow state, and its failures emit a `RuntimeDiagnostics` warning only.
   Audit confirms by reading the search-before-create order, the retry table and the comment marker handling.
5. Diagnosis, recovery and report telemetry carry actual correlation identities, respect telemetry settings, and expose truthful availability.
   `TelemetryOutboxEvent` in `runtime-contracts/.../contracts/telemetry/` gains `BLOCK_INCIDENT_DIAGNOSED` (`skillbill_block_incident_diagnosed`), `BLOCK_INCIDENT_RECOVERY` (`skillbill_block_incident_recovery`) and `BLOCK_INCIDENT_REPORTED` (`skillbill_block_incident_reported`). Each payload carries `incident_token`, `workflow_id`, `parent_workflow_id`, `phase_id`, `invocation_id`, `attempt`, `diagnosis_revision`, `recovery_attempt_id` and `report_id` when applicable, plus `responsible_category`, `certainty`, the status values, and `failure_code`. Fields that do not apply are omitted, never invented. Only actual ids from the incident, attempt and report records are used; `install_id` stays the PostHog distinct id.
   A new `BlockIncidentTelemetry` in `runtime-application` (beside `LifecycleTelemetryService`) enqueues through `TelemetryOutboxRepository.enqueue` and follows the existing level gate: level `off` enqueues nothing; `anonymous` omits `report_id` and issue url; `full` sends all fields. It is called by the investigation worker after settle, by `BlockIncidentRecoveryService` on each terminal attempt status, and by `ProductReportDelivery` on each terminal report status; an enqueue failure is caught and never affects them.
   `BlockIncidentTelemetry.availability(incidentToken): BlockIncidentTelemetryAvailability` returns `DISABLED` (level off), `PENDING` (queued, not synced), `SYNCED` (marked synced), `FAILED` (outbox latest error) or `UNAVAILABLE` (outbox unreadable), plus the exact search identifiers (`install_id`, incident token, workflow id, invocation id). No PostHog URL is produced because no deployment host or project configuration exists.
   `../../../docs/telemetry-privacy.md` documents the three events, their fields per level, and whether they are queued at `off`, following `docs/observability-policy.md`.
   Audit confirms by reading the enum, the payload builder, the level gate, the availability mapping and the doc entry.

## Criterion Sizing

1. About 260 production lines, 3 files, 1 area (`runtime-domain` blockincident report). Fits.
2. About 290 production lines, 7 files, 3 areas (`runtime-ports` blockincident, `runtime-infra/sqlite`, `runtime-engine` diagnostics/block report). Fits.
3. About 270 production lines, 6 files, 3 areas (`runtime-ports` blockincident, `runtime-infra/http`, `runtime-infra` process credential reader), plus two binding lines in `runtime-core`. Fits.
4. About 230 production lines, 2 files, 1 area (`runtime-engine` diagnostics/block report). Fits.
5. About 240 production lines, 5 files, 3 areas (`runtime-contracts` telemetry, `runtime-application` telemetry, `docs`), plus one call line each in the worker, recovery service and delivery. Fits.

## Decisions and assumptions

Using the user's existing `gh` or environment token to file a public issue on the product repository is what the parent decision describes, so it is planned without a confirmation prompt. If the reviewing operator rejects that, the safe fallback is to drop the credential implementation and leave every report `LOCAL_ONLY` with `MISSING_AUTH`; the rest of this subtask is unchanged. Implement confirms the `RemoteTransportPort` timeout and response surface supports the ambiguous mapping; if it cannot tell a pre-send failure from a post-send timeout, every transport timeout on `create` maps to `Ambiguous`.

## Non-Goals

No wholesale log or database upload. No historical flood: incidents before the rollout marker never report. No second report-repository setting, credential prompt or stored secret. No PostHog URL. No change to recovery behavior.

## Dependency Notes

Depends on subtask 1 (incident lifecycle and rollout marker), subtask 2 (diagnosis record and categories) and subtask 3 (recovery attempts for report content and telemetry). Subtask 5 presents report status, link, failure reason and telemetry availability.

## Test Obligations

1. T4.1 sanitizer redaction and rejection. A draft whose reason contains `/home/alice/project`, a `ghp_` token and an env dump keeps the failure code, redacts the path and token, drops the env dump, and a residual token pattern that survives redaction rejects with `RESIDUAL_SECRET`. Realistic bug: a credential reaches a public issue.
2. T4.2 duplicate and ambiguous submission. A `create` that returns `Ambiguous`, followed by a restart where search finds the marker, links the report without a second `create`; a second incident with the same fingerprint links to the same report and posts one comment once across two delivery runs. Use a fake `ProductIssueTrackerPort`. Realistic bug: restart files a second issue.
3. T4.3 missing auth and failures independent of recovery. A null credential leaves the report `LOCAL_ONLY` with `MISSING_AUTH` while the incident's recovery still succeeds; three `SUBMISSION_FAILED` attempts end `FAILED` with `RETRIES_EXHAUSTED` and the body retained. Realistic bug: report failure blocks recovery or loses the local copy.
4. T4.4 telemetry disabled and unavailable. At level `off` no block-incident event is enqueued and availability is `DISABLED`; an enqueue exception leaves the diagnosis settled. Realistic bug: telemetry failure aborts diagnosis or availability claims delivery that never happened.
5. HTTP status mapping in the `GitHubProductIssueAdapter` test for 403 with and without rate-limit headers, 404 and `IOException`. Realistic bug: rate limiting is reported as permission refused and never retried.
6. Migration upgrade evidence in `DatabaseMigrationsTest` preserving existing rows.

## Validation Strategy

Implement and audit read the tree only. Validate runs runtime-kotlin `./gradlew check --no-build-cache` and `../../../scripts/validate_agent_configs`, including `WireVocabularyArchitectureTest`, `RuntimeCompositionGuardArchitectureTest`, `StrategyCapabilityBoundaryArchitectureTest` and documentation parity under observability-policy. No real GitHub issue is filed by any test or validation step. Review applies architecture-guidelines section 5 with rule IDs and the security checklist for the sanitizer.

## Next path

```bash
skill-bill goal SKILL-435
```

## Implementation Details

All facts below come from the preplan digest. Paths are abbreviated the same way as the criteria above. Where the digest is silent, the step names the assumption implement confirms on first read.

### Upstream names this subtask consumes

Subtasks 1 to 3 own these; implement reads their landed names and adapts the calls, without renaming them:

- Subtask 1: the rollout marker (incidents observed before it never report), the investigation worker in `engine/diagnostics/block/` and its settle step, `BlockIncidentEntryReconciler.reconcileOnEntry()`, and the migrations it appends from 76.
- Subtask 2: the structured diagnosis record (`summary`, `whatHappened`, `cause`, `certainty` with `DIAGNOSED`/`SUSPECTED`, `responsibleCategory` with `SKILL_BILL_DEFECT`, evidence items, optional reproduction steps, `diagnosis_revision`).
- Subtask 3: the recovery attempt record (attempt id, action, terminal status, timestamps) and `BlockIncidentRecoveryService`.

The migration number is the next free version after the subtask 1 to 3 migrations, expected 79. Implement takes whatever number is next, without renumbering existing ones (preplan D1).

### Ordered tasks

1. **Wire vocabulary and enums (serves AC 2, 3, 5).**
   - Add `ProductReportStatus`, `ProductReportFailure`, `ProductReportFieldOutcome {KEPT, REDACTED, TRUNCATED, DROPPED}` and the link comment state `{NOT_REQUIRED, PENDING, POSTED}` to `runtime-domain/.../model/blockincident/report/`.
   - Put their wire values in a new `BlockIncidentReportKeys.kt` beside `BlockIncidentOccurrenceKeys.kt` in `runtime-contracts/.../contracts/workflow/identity/blockincident/`. Put the payload field keys there too (`incident_token`, `workflow_id`, `parent_workflow_id`, `phase_id`, `invocation_id`, `attempt`, `diagnosis_revision`, `recovery_attempt_id`, `report_id`, `responsible_category`, `certainty`, `failure_code`, status keys).
   - Add `BLOCK_INCIDENT_DIAGNOSED`, `BLOCK_INCIDENT_RECOVERY` and `BLOCK_INCIDENT_REPORTED` with their `skillbill_block_incident_*` wire values to `runtime-contracts/.../contracts/telemetry/TelemetryOutboxEvent.kt`.
   - Constraint: `WireVocabularyArchitectureTest` requires every wire string to live in runtime-contracts. Domain enums map to those strings and never repeat the literals.

2. **Pure sanitizer and fingerprint (serves AC 1).** New files in `runtime-domain/.../model/blockincident/report/`:
   - `ProductReportSanitizer.kt` is an object with `sanitize(occurrence, diagnosis, recoveryAttempts, runtimeFacts): ProductReportSanitization`.
   - `ProductReportDraft.kt` holds a sealed `ProductReportSanitization` = `ProductReportDraft(title, body, fingerprint, fieldOutcomes: Map<String, ProductReportFieldOutcome>)` | `ProductReportRejected(reason = RESIDUAL_SECRET, fingerprint, fieldOutcomes)`, plus a `ProductReportRuntimeFacts` value with runtime version and build, OS family, provider id, phase strategy id and model id.
   - `ProductReportRedaction.kt` holds the regex set and the residual scan.
   - Constraints:
     - Follow the allowlist, the drop list, the redaction patterns, the caps (8 evidence items of 512 chars, a 16 KiB body) and the `Suspected` label exactly as AC 1 states.
     - `runtime-domain` cannot import `runtime-application`, so the sanitizer copies the `RuntimeExceptionTelemetry` idiom (`[redacted]`, `skillbill.` frame filter, 512-char caps) instead of calling it.
     - The body ends with the marker `<!-- skill-bill-report-fingerprint:<fingerprint> -->`.
     - The fingerprint uses the same sha256 helper as the `bi_<sha256>` occurrence token. Assumption: that helper lives in domain. If it does not, add a private sha256 in the report package.
     - `normalizedCause` lowercases, then strips paths, hex runs of 6 or more characters, and digits, then collapses whitespace. It never includes incident tokens, workflow ids or invocation ids.
     - New files only. `BlockIncident.kt` is at 598 lines (line ceiling).
   - Tests: T4.1 `ProductReportSanitizerTest` in runtime-domain.

3. **Report persistence (serves AC 2, AC 5 store preservation).**
   - Port `ProductReportRepository` in `runtime-ports/.../blockincident/`, with KDoc on the interface and its members (`CommentAndInterfaceKdocArchitectureTest`):
     - `createOrLink(sanitization, occurrenceToken, now): ProductReportRecord` inserts with `INSERT ... ON CONFLICT(fingerprint) DO NOTHING`, then reads back and links. The creator link starts `NOT_REQUIRED` and later links start `PENDING`.
     - `due(now, limit, credentialAvailable)` returns `PENDING`/`AMBIGUOUS` rows with `next_attempt_at <= now`, `DELIVERED`/`LINKED_EXISTING` rows with a `PENDING` link, and `SUBMITTING` rows whose `updated_at` is older than the stale window. It also returns `LOCAL_ONLY`+`MISSING_AUTH` rows, only when `credentialAvailable`.
     - `transition(reportId, expectedStatus, newStatus, failure, issue, attempts, nextAttemptAt, now): Boolean` is a compare-and-set on `status`.
     - `markComment(reportId, occurrenceToken, expected, new): Boolean`.
     - `forIncident(token): ProductReportRecord?`.
   - Put `ProductReportRecord` and `ProductReportIssue(number, url)` in the same ports model package.
   - Add `UnitOfWork.productReports` in `ports/persistence/UnitOfWork.kt`, the default in testFixtures `UnitOfWorkDefaults.kt`, and a new testFixtures `InMemoryProductReportRepository.kt`.
   - Sqlite:
     - The new migration creates the two tables exactly as AC 2 states. `body_sha256` is checked on readback, and a mismatch quarantines the row the way `SqliteBlockIncidentRepository` does.
     - Add a `(status, next_attempt_at)` index. Add revision triggers through a new `addProductReportRevisionTriggers` beside `addBlockIncidentRevisionTriggers` in `core/schema/DesktopChangeRevisions.kt`.
     - New `SqliteProductReportRepository.kt` and codec in a separate file. Do not grow the 651-line `SqliteBlockIncidentRepository.kt`.
     - Register it in `SqliteRepositories.kt`.
   - `report_id` = `bir_` + a uuid from the existing `IdentifierGenerator` port.
   - Rejected drafts store body `[withheld: residual secret]`, never the draft text, with status `LOCAL_ONLY` and failure `RESIDUAL_SECRET`.
   - Bump `DesktopReadSchema.VERSION` only if its rule covers new revision topics. Assumption: subtask 5 owns the read-model bump. This step bumps only when the triggers alone require it, and records in the migration KDoc which version it took.
   - Tests:
     - `DatabaseMigrationsTest` upgrade evidence: existing `block_incidents` rows and runs survive, and the new tables are empty (obligation 6).
     - New `SqliteProductReportRepositoryTest` with one case: two `createOrLink` calls with the same fingerprint return one report with two links, and a `transition` with a stale `expectedStatus` returns false and changes nothing. Realistic bug: concurrent entries create two reports or both claim `SUBMITTING`.

4. **Report scheduler (serves AC 2, AC 3 independence).**
   - `engine/diagnostics/block/report/ProductReportScheduler.kt` exposes `schedule(unitOfWork, incident, diagnosis, recoveryAttempts, runtimeFacts)`.
   - It returns without writing unless all three hold: `responsibleCategory == SKILL_BILL_DEFECT`; `certainty == DIAGNOSED`, or `SUSPECTED` with at least one evidence item; and `occurrence.observedAt` is at or after the subtask 1 rollout marker. Otherwise it sanitizes and calls `createOrLink`, and the initial status is `PENDING`.
   - Runtime facts come from the injected `RuntimeVersion` and the diagnosis record's provider, strategy and model fields. Assumption: subtask 2 records those fields. When one is missing, omit it from the body. Never invent it.
   - The investigation worker calls it after a successful settle, in its own transaction. A scheduler exception is caught there and logged as a `RuntimeDiagnostics` warning. The settled diagnosis stays committed and `observeBlock` is never called.
   - Tests: new `ProductReportSchedulerTest` with one case. A `USER`/operator category and a pre-marker defect create nothing. A post-marker defect creates one report, and a second incident with the same fingerprint links to it. Realistic bug: user-caused or historical blocks get filed publicly (preplan R9 and R8).

5. **Product issue adapter and credential (serves AC 3).**
   - Add `SkillBillProductRepository.kt` in `runtime-infra/http` with `const val SKILL_BILL_PRODUCT_REPOSITORY_ID = 1183084383L` and the derived base `https://api.github.com/repositories/$SKILL_BILL_PRODUCT_REPOSITORY_ID`. `GitHubReleaseCatalogAdapter.RELEASES_URL` is rebuilt from it so the release URL stays byte-identical.
   - Ports in `runtime-ports/.../blockincident/`:
     - `ProductIssueTrackerPort`. Results are sealed `ProductIssueResult` = `Found(number, url)` | `NotFound` | `Created(number, url)` | `Failed(failure: ProductReportFailure, retryAt: Instant? = null)` | `Ambiguous`.
     - `ProductIssueCredentialPort { fun token(): String? }`.
     - `retryAt` is an additive field that carries the rate-limit reset.
   - `GitHubProductIssueAdapter` in `runtime-infra/http` over `RemoteTransportPort`:
     - `findByFingerprint`:
       - First, `GET /repositories/{id}` resolves `full_name`, cached for the adapter's lifetime.
       - Then `GET https://api.github.com/search/issues?q="skill-bill-report-fingerprint:<fp>" repo:<full_name> is:issue in:body` covers open and closed issues. If that finds nothing, a bounded fallback `GET /repositories/{id}/issues?state=all&creator=<login>&per_page=100` scans bodies for the marker. `<login>` comes from `GET /user`, cached.
       - The fallback covers search-index lag right after an ambiguous create. Assumption: one page is enough, because only this user's recent issues matter.
     - `create` is `POST /repositories/{id}/issues` with title, body and labels `["skill-bill-report"]`. Labels may be dropped for non-collaborators, which does not matter because matching never relies on labels.
     - `comment(issueNumber, body, token)` is idempotent inside the adapter. It first lists `GET /repositories/{id}/issues/{n}/comments?per_page=100` and returns `Found` when a comment already holds the body's `<!-- skill-bill-incident:<token> -->` marker. Otherwise it posts and returns `Created`. The port signature does not change.
     - Status mapping is exactly as AC 3 states. Rate-limit headers are `x-ratelimit-remaining: 0` or `retry-after`, and `retryAt` comes from `x-ratelimit-reset` (epoch seconds) or `retry-after`.
     - Assumption: `RemoteTransportResponse` exposes response headers and the transport throws `HttpTimeoutException` separately from connect failures. If headers are missing, `RATE_LIMITED` uses the fixed backoff. If the adapter cannot tell a pre-send failure from a post-send timeout, every timeout on `create` maps to `Ambiguous`, and on reads to `OFFLINE`.
     - Headers are `Authorization: Bearer <token>`, `Accept: application/vnd.github+json` and `X-GitHub-Api-Version`. Exceptions and diagnostics never include the token.
   - The credential reader `EnvironmentGhProductIssueCredential` in `runtime-infra`, next to the existing process adapters, reads `GH_TOKEN`, then `GITHUB_TOKEN`, through an injected environment lookup. Its last step runs `ProcessBuilder(listOf("gh", "auth", "token"))` with no shell, stdin closed and stderr discarded. It waits 5 seconds, calls `destroyForcibly` on timeout, and takes trimmed stdout only on exit 0. Missing binary, non-zero exit, timeout or blank output give null. Nothing is cached to disk.
   - Bind both ports only in `skillbill.di.core.RuntimeComponent`, through the existing provides pattern (`RuntimeCompositionGuardArchitectureTest`).
   - Tests:
     - `GitHubProductIssueAdapterTest` with a fake `RemoteTransportPort` covers obligation 5: 403 with rate-limit headers gives `RATE_LIMITED` with `retryAt`, 403 without gives `PERMISSION_REFUSED`, 404 gives `PERMISSION_REFUSED`, and an `IOException` before response gives `OFFLINE`. One more case: `comment` returns `Found` without a POST when the marker comment already exists. Realistic bug: a restart posts a duplicate update.
     - No test for the credential reader. It is env and process glue with no branch logic beyond fallthrough.

6. **Delivery reconciler (serves AC 4, AC 3 independence, AC 5 restart).**
   - `engine/diagnostics/block/report/ProductReportDelivery.kt` exposes `deliver(limit = 4)`. It is injected with `DatabaseSessionFactory`, `Clock`, `ProductIssueTrackerPort`, `ProductIssueCredentialPort`, `RuntimeDiagnostics` and the telemetry from task 7.
   - Sequence per run:
     1. Read `due` without a credential. Fetch the credential only when due rows or `MISSING_AUTH` rows exist, so a normal entry never spawns `gh`.
     2. With a credential, re-read `due(credentialAvailable = true)`.
     3. For each report, compare-and-set to `SUBMITTING` in a short transaction, incrementing `attempts` for submit paths. A null credential instead sets `LOCAL_ONLY`/`MISSING_AUTH` with no `next_attempt_at`.
     4. Make every HTTP call outside any DB transaction.
     5. Always call `findByFingerprint` first. `Found` gives `DELIVERED` when the row came from `AMBIGUOUS`, because this runtime attempted the create, and `LINKED_EXISTING` otherwise. `create` runs only after `NotFound`. `Created` gives `DELIVERED`, `Ambiguous` gives `AMBIGUOUS` (due immediately on the next run), and `Failed` follows the retry table.
     6. Each result is written with a compare-and-set from `SUBMITTING` in a second short transaction.
   - Stale `SUBMITTING`: rows not updated within 2 minutes return to `AMBIGUOUS` at the start of the run. Assumption: 2 minutes exceeds any single transport timeout. Implement confirms it against the `RemoteTransportPort` timeout.
   - Retry table:
     - failure on attempt 1 gives `next_attempt_at = now + 1 min`
     - failure on attempt 2 gives `now + 10 min`
     - failure on attempt 3 gives `FAILED`/`RETRIES_EXHAUSTED` with the body retained
     - `RATE_LIMITED` uses `retryAt` when present, clamped to at most 1 hour, otherwise 1 hour, and still counts as an attempt
     - `PERMISSION_REFUSED` goes straight to `FAILED`/`PERMISSION_REFUSED` with the body retained, because retrying a refused token is futile
     - Decision: this reads the spec's three intervals as two retry gaps plus a 1-hour rate-limit ceiling.
   - Comments: for each `PENDING` link on a delivered or linked report, build the comment from that incident's sanitized evidence plus the outcomes of any recovery attempts already terminal. The comment goes through the same sanitizer path and carries the incident marker. Call `comment`, then `markComment(PENDING → POSTED)`. A crash between the two is covered by the adapter's idempotent comment. A recovery that ends after the link is `POSTED` is not appended (spec: "if not yet posted").
   - Isolation: delivery only reads incidents, diagnoses and recovery attempts, and writes only report tables. Each report is wrapped in a catch that emits a `RuntimeDiagnostics` warning and moves on.
   - Wiring:
     - The investigation worker calls `deliver()` after the scheduler.
     - `BlockIncidentEntryReconciler.reconcileOnEntry()` calls it after its transaction commits, never inside it, and only when the database exists.
     - Both calls swallow failures as warnings.
   - Tests: new `ProductReportDeliveryTest` with a fake tracker and the in-memory repositories.
     - T4.2: `Ambiguous` create, then a second run (restart) where search finds the marker, gives `DELIVERED` with exactly one `create` call. A second incident with the same fingerprint gets one posted comment across two runs.
     - T4.3: a null credential gives `LOCAL_ONLY`/`MISSING_AUTH`. The incident's recovery is driven to success through the subtask 3 recovery service fixture while the report stays `LOCAL_ONLY`, and the incident state and attempt rows are untouched by delivery. Three `SUBMITTING`→`SUBMISSION_FAILED` runs advancing the clock past `next_attempt_at` end `FAILED`/`RETRIES_EXHAUSTED` with `body` unchanged.

7. **Correlated telemetry and availability (serves AC 5).**
   - `runtime-application/.../telemetry/service/BlockIncidentTelemetry.kt` sits beside `LifecycleTelemetryService` and has `diagnosed(incident, diagnosis)`, `recovery(incident, attempt)`, `reported(incident, report)` and `availability(incidentToken)`.
   - It reads the level from `TelemetrySettingsProvider`:
     - `off` enqueues nothing.
     - `anonymous` omits `report_id` and issue url.
     - `full` sends all fields.
     - Absent identities are omitted, never set to placeholder values.
   - Payload JSON is built with the keys from task 1 and enqueued through `TelemetryOutboxRepository.enqueue`. Every method catches all exceptions and emits a `RuntimeDiagnostics` warning.
   - `availability` returns `BlockIncidentTelemetryAvailability(state, installId, incidentToken, workflowId, invocationId)` with state `DISABLED` | `PENDING` | `SYNCED` | `FAILED` | `UNAVAILABLE`.
   - It needs an outbox read keyed by incident token. Assumption: no such read exists, so add `TelemetryOutboxRepository.latestByCorrelation(events, incidentToken)` returning queued/synced/latest-error state. The sqlite implementation matches on `json_extract(payload, '$.incident_token')` with a bounded `LIMIT 1` over the three events. A read exception gives `UNAVAILABLE`.
   - Engine callers are the worker after settle, `BlockIncidentRecoveryService` on each terminal attempt and `ProductReportDelivery` on each terminal report status. Assumption: if runtime-engine does not already depend on runtime-application, add a narrow `BlockIncidentTelemetryPort` in `runtime-ports/.../blockincident/` that `BlockIncidentTelemetry` implements, bound in `RuntimeComponent`. Otherwise inject the class directly. These call sites are the only edits to subtask 1 to 3 files: one line each, plus the scheduler and delivery hooks.
   - Docs: `../../../docs/telemetry-privacy.md` gets the three events, their fields per level, and "not queued at `off`", following `docs/observability-policy.md`.
   - Tests:
     - T4.4 `BlockIncidentTelemetryTest` in runtime-application: at level `off`, no event is enqueued and `availability` is `DISABLED`; at `anonymous`, `report_id` is absent.
     - One case in the subtask 1/2 investigation worker test: a throwing telemetry fake leaves the diagnosis settled. Assumption: the test is named after the worker class subtask 1 lands.

### Constraints for every task

- No new defensive null branches. Inline FQNs are forbidden. New code goes in new files to respect `ProductionFileLineCeilingArchitectureTest`.
- No test or validation step reaches real GitHub: the adapter tests use a fake transport. Nothing compiles, builds or runs tests before validate.
- No installer or install-refresh commands.
- Delivery and telemetry never change recovery behavior, incident diagnosis state or workflow state.
