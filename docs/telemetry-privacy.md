# Telemetry Privacy

This document states what Skill Bill collects, where it goes, how to turn it off, and what
correlates events. It describes shipped behavior only.

The default telemetry level is `anonymous`. Collection is on unless you disable it.

## Levels

| Level | Behavior |
|-------|----------|
| `off` | Nothing is transmitted: `sync` and `autoSync` short-circuit on the disabled level. Seven events are still written to the local outbox — `skillbill_runtime_exception`, `skillbill_feature_task_runtime_projection_measurement`, `skillbill_feature_task_runtime_shared_evidence`, `skillbill_feature_task_runtime_rejection`, `skillbill_feature_task_runtime_diagnostic_degradation`, `skillbill_review_stage_degradation`, and `skillbill_review_finished_legacy_regenerated` — see [What is still queued at `off`](#what-is-still-queued-at-off). |
| `anonymous` | Counts, enums, durations, and identifiers derived by one-way hash. No file paths, descriptions, notes, learning text, error messages, or non-Skill-Bill stack frames. |
| `full` | Everything in `anonymous`, plus the free-text and path fields marked below. |

## Per-level collection

`✓` = collected as-is. `hashed` = replaced with a salted SHA-256 prefix. `redacted` = replaced
with a fixed placeholder. `—` = not present in the payload. `queued only` = written to the local
outbox but never transmitted while the level is `off`.

### What is still queued at `off`

Seven producers enqueue without consulting the telemetry level:

- `TelemetryService.captureException` enqueues `skillbill_runtime_exception` guarded only by
  `database.databaseExists`; the level is used solely to choose redaction, so at `off` the row is
  written with the redacted `error_message` (`[redacted]`) and `skillbill.` frames only.
- `FeatureTaskRuntimePhaseRecorder.recordProjectionMeasurements` enqueues
  `skillbill_feature_task_runtime_projection_measurement` with no telemetry gate; the row carries
  the bounded counters and the repository checkpoint fingerprint.
- `FeatureTaskRuntimePhaseRecorder.recordSharedEvidenceMeasurement` enqueues
  `skillbill_feature_task_runtime_shared_evidence` with no telemetry gate; the row carries the
  checkpoint fingerprint, consuming phase id, outcome (`derivation` / `reuse` /
  `checkpoint_change_rederivation`), and bounded index counters only.
- `FeatureTaskRuntimeRejectedOutputRecorder.recordRejectionMeasurement` enqueues
  `skillbill_feature_task_runtime_rejection` with no telemetry gate; the row carries the workflow,
  phase, iteration, rule, JSON pointer, closed violation class, and bounded caps only.
- `migrateLegacyReviewFinishedRow` enqueues `skillbill_review_finished_legacy_regenerated` when it
  regenerates a legacy `skillbill_review_finished` row still in the outbox; the row carries the
  `review_run_id` and the from/to contract versions only.
- `FeatureTaskRuntimePhaseRecorder.degradeDiagnosticFailure` enqueues
  `skillbill_feature_task_runtime_diagnostic_degradation` with no telemetry gate; the row carries
  the workflow, phase, attempt, generation, operation, typed failure class, and conflicting key
  (`repair_turn` only when the failure was scoped to one turn).
- `ParallelCodeReviewRunner` / `LifecycleTelemetryStore.reviewStageDegradation` enqueues
  `skillbill_review_stage_degradation` with no telemetry gate; the row carries `review_run_id`,
  seam, expected, actual, and a closed reason (`spec_context_none`, `adjudication_skipped`,
  `worker_process_failed`, `worker_timed_out`, `worker_output_unusable`,
  `worker_launch_budget_exceeded`, `worker_launch_or_return_failed`, `stage_boundary_unreached`,
  and the `evidence_boundary_*` tokens). It never carries finding text, spec excerpts, or paths.
  The four `worker_*` causes were one token until SKILL-236; a worker that never started, one that
  ran past its deadline, one that returned output nobody could parse, and one refused by a launch
  budget each route to a different fix, so each reports separately. `worker_launch_or_return_failed`
  survives for the unsupported-agent refusal alone. A rejection reason no cause recognizes emits no
  worker-failure record rather than being attributed to a cause nobody classified, so a new reason
  has to be classified here to stay observable.

Nothing is transmitted while the level is `off`: `TelemetryService.sync` and
`TelemetryService.autoSync` return before upload when the resolved settings are disabled.

Enabling telemetry later does not discard those rows. `clearsPendingOutbox("off", "anonymous")` is
`false` — the pending outbox is cleared only on a downgrade or a move to `off` — so rows enqueued
while `off` remain pending and upload on the next sync after you enable. Clear them by running
`skill-bill telemetry disable` (or `set-level off`), which clears the pending outbox, before
enabling.

### Envelope, on every uploaded event

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `event` (event name) | — | ✓ | ✓ | `telemetryProxyBatchPayload` |
| `timestamp` (local enqueue time) | — | ✓ | ✓ | `telemetryProxyBatchPayload` |
| `install_id` (also the `distinct_id`) | — | ✓ | ✓ | `telemetryProperties` |
| `skill_bill_version` | — | ✓ | ✓ | `telemetryProperties`, from the `telemetry_outbox.skill_bill_version` column; omitted on rows enqueued before release attribution existed |
| `$process_person_profile` (always `false`) | — | ✓ | ✓ | `telemetryProperties` |
| `uuid` (top-level delivery identity) | — | ✓ | ✓ | `telemetryProxyBatchPayload`, from the `telemetry_outbox.event_uuid` column; omitted on rows that predate the column and were already synced |
| `$insert_id` (legacy mirror of `uuid`) | — | ✓ | ✓ | `telemetryProperties`, same value; PostHog does not read it |
| `$geoip_disable` (always `true`) | — | added by the relay | added by the relay | `transformBatch` |

The delivery identity is a random UUID minted in the same write that enqueues the row. It is
content-free: never derived from payload bytes, event name, timestamp, `install_id`, or any machine
property, so it discloses nothing beyond "this is one distinct queued event". It is assigned once and
never re-minted on rebatch, retry, restart, or reclaim, and it is independent per database file, so
two installs cannot produce the same identity.

PostHog keys event identity on the top-level `uuid`, so a resend of the same row overwrites the
stored event instead of adding one. Before SKILL-381 the identity rode only as `$insert_id`, which
PostHog ignores, and resends were stored again (one batch was stored about 32,630 times on
2026-08-15). A delivery whose outcome is unconfirmed — a timeout, a relay 502, a 408 or 429 — keeps
retrying without consuming a delivery attempt, so an offline machine never blocks its own events;
the `uuid` is what makes those resends safe.

Redaction is unchanged by it: the anonymous-level redaction path still governs event content, and
the identity is not part of that content.

### `skillbill_goal_started`, `skillbill_goal_finished`, `skillbill_goal_issue_finished`, `skillbill_goal_subtask_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `workflow_id` / `parent_workflow_id` | — | hashed where the issue key is embedded | ✓ | `redactedWorkflowId` → `redactIssueKeyReferences` |
| `issue_key` | — | hashed | ✓ | `redactIssueKey` |
| `status`, `mode`, `resumed`, `stop_reason`, `blocked_reason` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `started_at`, `first_started_at`, `finished_at`, `duration_seconds` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `subtask_total`, `subtask_id`, `subtasks_complete`, `subtasks_blocked`, `subtasks_skipped`, `attempt_count`, `total_invocations`, `total_blocks`, `total_resumes` | — | ✓ | ✓ | `GoalTelemetryPayloadSupport` |
| `feature_name` | — | — | ✓ | `goalStartedPayload` |
| `subtask_name`, `finalizing_agent_id`, `participating_agent_ids`, `boundary_history_written`, `boundary_history_value` | — | — | ✓ | `goalSubtaskFinishedPayload` |

### `skillbill_feature_task_runtime_started` / `skillbill_feature_task_runtime_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `feature_size` | — | ✓ | ✓ | `featureTaskRuntimeStartedPayload` |
| `issue_key` | — | hashed | ✓ | `redactIssueKey` |
| `completion_status`, `completed_phase_ids`, `phase_outcomes`, `review_fix_iteration_count`, `audit_*` fields, `regeneration_*` counters, `crash_reconciliation_*` counters, `last_incomplete_phase`, `blocked_reason`, `stale_reason`, `duration_seconds` | — | ✓ | ✓ | `featureTaskRuntimeFinishedPayload` |
| `correlation_availability`, `redacted_workflow_id`, `goal_parent_workflow_id` | — | hashed where the issue key is embedded | ✓ | `correlationFields` → `redactIssueKeyReferences` |
| `goal_subtask_id` | — | ✓ | ✓ | `correlationFields` |
| `agent_context_measurement_grain`, `resolved_agent_ids`, `resolved_agent_availability`, `launched_models`, `launched_model_availability` | — | ✓ | ✓ | `agentContextFields` |
| `feature_name` | — | — | ✓ | `featureTaskRuntimeStartedPayload` |
| `resolved_branch` | — | — | ✓ | `featureTaskRuntimeFinishedPayload` |

Both the started and finished events carry the correlation fields, so a run is joinable to its goal,
its rejection records, and its diagnostic records from either end. The workflow id goes through the
same salted redaction the goal events and the issue key use, so at `anonymous` the join survives
without a raw tracker key reaching the wire. A run that is not a goal child has no
`goal_parent_workflow_id` and no `goal_subtask_id`; `correlation_availability` is `unknown` only on a
session started before these columns existed, which is never rewritten into a measured value.

`resolved_agent_ids` and `launched_models` are the distinct agent slugs and model strings the run's
own phases resolved, read from the durable phase records and reported at the
`distinct_resolved_agents_per_run` grain the payload states. They are runtime configuration, not user
content: no prompt, repository path, issue text, or agent output rides them, which is why they are
carried at `anonymous` while `feature_name` and `resolved_branch` are not. A run whose phases
launched with no model directive reports `launched_model_availability` as
`unavailable_no_durable_state` and a null list, never an empty one.

### Availability fields

Several measurements pair a value with a sibling `*_availability` field
(`TelemetryMeasurementAvailability`): `measured`, `unavailable_no_durable_state`,
`unavailable_unsupported`, `unavailable_incomplete`, or `unknown`. Read the availability first. The
value is null whenever the availability is not `measured`, and it is never defaulted to `0` or
`false` — an unmeasured budget and a budget measured as intact are different facts, and collapsing
them is what let historical rows report exhausted fix loops and clean quality gates that nobody
observed. `unknown` is what a row written before its availability column existed reports; no current
producer writes it, and no migration backfills it.

Availability fields carry no run content: each is a fixed token from a closed vocabulary.

### `skillbill_feature_task_runtime_projection_measurement`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `contract_version`, `consumer_phase_id` | queued only | ✓ | ✓ | `FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap` |
| `workflow_id` | queued only | ✓ raw, not hashed | ✓ | `FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap` |
| `projection_contract_id`, `producer_iteration`, `repository_checkpoint_fingerprint`, `projected_utf8_bytes`, `projected_collection_items`, `estimated_tokens`, `private_evidence_utf8_bytes`, `delivered_projection_utf8_bytes`, `failure_classification` | queued only | ✓ | ✓ | `FeatureTaskRuntimeProjectionMeasurement.toTelemetryMap` |

This event is enqueued regardless of level. At `off` the row is written locally and not sent.

Unlike the goal events above, this event's `workflow_id` is **not** redacted at `anonymous`:
`LifecycleTelemetryStore.featureTaskRuntimeProjectionMeasurement` takes no level parameter and
enqueues `toTelemetryMap` verbatim, so an issue key embedded in the workflow id (for example
`SKILL-163:...`) is uploaded as-is once telemetry is at `anonymous` or `full`.

### `skillbill_feature_task_runtime_shared_evidence`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `contract_version`, `consumer_phase_id`, `outcome` | queued only | ✓ | ✓ | `FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap` |
| `workflow_id` | queued only | ✓ raw, not hashed | ✓ | `FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap` |
| `checkpoint_fingerprint`, `file_index_count`, `hunk_index_count` | queued only | ✓ | ✓ | `FeatureTaskRuntimeSharedEvidenceMeasurement.toTelemetryMap` |

This event is enqueued regardless of level. It carries no file paths, diff content, or prompt
bodies — only the identifiers and counters needed to compute reuse rate.

### `skillbill_feature_task_runtime_diagnostic_degradation`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `contract_version`, `phase_id`, `attempt`, `generation`, `operation`, `failure_class`, `conflicting_key` | queued only | ✓ | ✓ | `FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap` |
| `workflow_id` | queued only | ✓ raw, not hashed | ✓ | `FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap` |
| `repair_turn` | queued only, omitted when the failure was not scoped to one turn | ✓ when present | ✓ when present | `FeatureTaskRuntimeDiagnosticDegradationMeasurement.toTelemetryMap` |

This event is enqueued regardless of level. At `off` the row is written locally and not sent.

Unlike the goal events above, this event's `workflow_id` is **not** redacted at `anonymous`:
`LifecycleTelemetryStore.featureTaskRuntimeDiagnosticDegradation` takes no level parameter and
enqueues `toTelemetryMap` verbatim. No agent output, prompt text, database path, or process output
is present on the map.

### `skillbill_feature_task_runtime_rejection`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `contract_version`, `phase_id`, `iteration`, `rule`, `pointer_path`, `violation_class`, `exhausted_fix_loop`, `exhausted_fix_loop_availability` | queued only | ✓ | ✓ | `FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap` |
| `declared_cap`, `observed_length` | queued only, omitted when not measured | ✓ when present | ✓ when present | `FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap` |
| `workflow_id` | queued only | ✓ raw, not hashed | ✓ | `FeatureTaskRuntimeRejectionMeasurement.toTelemetryMap` |

This event is enqueued regardless of level. `pointer_path` is a JSON pointer into the rejected
phase output (`/` for the whole document), never a filesystem path, and the row carries
no agent output.

### `skillbill_review_finished_legacy_regenerated`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `event_name`, `contract_version`, `review_run_id`, `from_version`, `to_version` | queued only | ✓ | ✓ | `migrateLegacyReviewFinishedRow` |

This event is enqueued regardless of level, once per legacy `skillbill_review_finished` outbox row
the review-health materializer regenerates. It records the migration, not the review.

### `skillbill_quality_check_started` / `skillbill_quality_check_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `routed_skill`, `detected_stack`, `fallback`, `fallback_reason`, `scope_type`, `initial_failure_count`, `orchestrated` | — | ✓ | ✓ | `qualityCheckStartedPayload` |
| `final_failure_count`, `final_failure_count_availability`, `iterations`, `result`, `completion`, `stale_reason`, `duration_seconds` | — | ✓ | ✓ | `qualityCheckFinishedPayload` |
| `failing_check_names`, `unsupported_reason` | — | — | ✓ | `qualityCheckFinishedPayload` |

`completion` separates a check the operator ran to a terminal (`operator_completed`) from one the
stale reconciler closed without ever observing it finish (`reconciler_stale`). A
`reconciler_stale` row carries `final_failure_count: null` with
`final_failure_count_availability: unavailable_incomplete` and a normalized `stale_reason`; it never
reports a zero failure count, so it cannot land in a clean-gate numerator or denominator. Build a
clean rate only over rows that are both `operator_completed` and `final_failure_count_availability:
measured`.

The pack build strategy emits `_started` after the first gate run and `_finished`
when the gate settles, with `routed_skill: bill-code-check`. Failure counts and
iterations come from the runtime's command results.

`skill-bill phase validation` uses the same agent validation strategy as goal
validate. It does not emit these pack-build quality-check events or infer measured
failure counts from the agent's prose. Its result reports the validate step and
the checks the agent ran.

### `skillbill_feature_verify_started` / `skillbill_feature_verify_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `acceptance_criteria_count`, `rollout_relevant`, `orchestrated` | — | ✓ | ✓ | `featureVerifyStartedPayload` |
| `feature_flag_audit_performed`, `review_iterations`, `audit_result`, `completion_status`, `history_relevance`, `history_helpfulness`, `duration_seconds` | — | ✓ | ✓ | `featureVerifyFinishedPayload` |
| `spec_summary`, `gaps_found` | — | — | ✓ | `featureVerifyStartedPayload`, `featureVerifyFinishedPayload` |

### `skillbill_review_finished`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| Finding counts and rates (`total_findings`, `accepted_findings`, `rejected_findings`, `unresolved_findings`, `accepted_rate`, `rejected_rate`) | — | ✓ | ✓ | `filterReviewFinishedSummary` |
| `review_run_id`, `review_session_id`, `routed_skill`, `review_subskills`, `review_scope`, `review_platform`, `detected_stack`, `detected_stack_detail`, `fallback`, `fallback_reason`, `platform_slug`, `scope_type`, `execution_mode`, `review_finished_at` | — | ✓ | ✓ | `reviewFinishedPayload` |
| Finding detail `issue_category`, `severity`, `confidence`, `outcome_type` | — | ✓ | ✓ | `reviewFindingDetails` |
| Finding detail `location`, `description`, `note` | — | emptied | ✓ | `reviewFindingDetails` |
| `learnings.applied_count`, `applied_references`, `applied_summary`, `scope_counts`, entry `reference` and `scope` | — | ✓ | ✓ | `buildLearningsSection` |
| Learning entry `title`, `rule_text` | — | — | ✓ | `learningsEntries` |
| Per-stage verdict distribution (`verification`, `adjudication` claim_verdict and scope_disposition counts, `finding_count`) | — | ✓ | ✓ | durable `review_run_finding_verdicts` via `aggregateReviewStageMetrics` |
| `refutation_rate_by_stage`, `rejected_verdict_counts`, `severity_adjustment_counts`, `resolved_tier` | — | ✓ | ✓ | durable verdict rows and `review_runs.execution_mode`; never a worker self-report |

### `skillbill_review_stage_degradation`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `review_run_id`, `seam`, `expected`, `actual`, `reason` | queued only | ✓ | ✓ | `ReviewStageDegradationSelection` |

Counts and closed enums only. No finding text, paths, or spec bodies.

This event is enqueued regardless of level. At `off` the row is written locally and not sent.

### `skillbill_pr_description_generated`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `session_id`, `commit_count`, `files_changed_count`, `was_edited_by_user`, `pr_created` | — | ✓ | ✓ | `prDescriptionPayload` |
| `pr_title` | — | — | ✓ | `prDescriptionPayload` |

### `skillbill_runtime_exception`

| Field | off | anonymous | full | Source |
|-------|-----|-----------|------|--------|
| `workflow_phase`, `error_type` (exception class simple name, or the failure code label for coded runtime failures) | queued only | ✓ | ✓ | `enqueueRuntimeException` |
| `error_message` | queued only, redacted to `[redacted]` | redacted to `[redacted]` | ✓ (first 512 characters) | `enqueueRuntimeException` |
| `stack_trace` | queued only, `skillbill.` frames only, first 12 | `skillbill.` frames only, first 12 | first 12 frames | `redactedStackTrace` |

This event is enqueued regardless of level. At `off` the row is written locally with the redacted
message and `skillbill.` frames, and not sent.

An unresolved or unrecognized level falls to the redacted branch, not the `full` branch.

No payload builder emits a repository name, remote URL, or working-directory path. The
`resolve_learnings` MCP tool accepts a `repo` learning scope, but that value is validated locally
and no outbox payload carries a `repo` property.

## Where events go

- Default destination: `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
  (`DEFAULT_TELEMETRY_PROXY_URL`), used when no custom proxy is configured.
- The relay is self-hostable. The example Cloudflare Worker ships in
  `docs/cloudflare-telemetry-proxy/`; deploy it and keep the backend credential in the Worker
  secret store.
- Re-point it with `SKILL_BILL_TELEMETRY_PROXY_URL`, or with `proxy_url` in
  `~/.config/skill-bill/config.json`.
- When a proxy is configured, it becomes the only remote telemetry destination. Skill Bill does
  not also send to the default relay.
- The reserved test identity `test-install-id` never syncs to the hosted relay: the runtime refuses
  (`record_kind=refusal`, cause `reserved_test_install_id`) unless a custom proxy is configured,
  and the bundled relay drops events whose install id or distinct id is blank or reserved before
  forwarding, reporting the count as `dropped_test_events` in its response.
- The bundled relay sets `$geoip_disable: true` on every event. The only IP PostHog sees is the
  relay's egress address, so a location derived from it describes the relay, not the user.

## Opting out

Every supported mechanism:

- `skill-bill telemetry disable` — sets the level to `off`.
- `skill-bill telemetry set-level off` — equivalent to `telemetry disable`; `TelemetrySetLevelCommand`
  reaches the same `TelemetryService.setLevel` path.
- Choose `off` at the telemetry level prompt during `./install.sh`.
- `SKILL_BILL_TELEMETRY_LEVEL=off`.
- `SKILL_BILL_TELEMETRY_ENABLED=false` — legacy override, maps to `off` (`true` maps to
  `anonymous`).
- Writing `telemetry.level: "off"` (or the legacy `telemetry.enabled: false`) directly into
  `~/.config/skill-bill/config.json` has the same effect.

At `off`, no telemetry is transmitted and no telemetry config is required. Payload building is
skipped for every event except the producers listed in
[What is still queued at `off`](#what-is-still-queued-at-off), which are enqueued locally without
consulting the level and are not discarded when telemetry is later enabled.

Opting out still deletes rows. A consent downgrade, a move to `off`, and an explicit queue clear all
delete the pending outbox rows outright; the delivery identity lives in a column on those same rows,
so deleting them deletes the identity with them. Nothing in the drain, the migration backfill, or the
receiver's deduplication window can reconstruct a discarded event: the backfill only ever fills a
missing identity on a row that still exists, and it never recreates one.

## What correlates events

`install_id` is the sole correlation key. It is a random UUID generated on first install
(`UUID.randomUUID()` in `ensureTelemetryConfigFile`) and stored in
`~/.config/skill-bill/config.json`. It is sent as the event `distinct_id` and as the
`install_id` property.

- It persists across reinstalls: the durable config lives outside the `~/.skill-bill/` tree that
  installs wipe, and `install.sh` migrates a legacy `~/.skill-bill/config.json` to the durable
  path before the pre-install cleanup.
- It persists across enable/disable cycles: disable is an in-place `telemetry.level` write, never
  a delete, so re-enabling reuses the same identity instead of minting a fresh UUID.
- No hardware, machine, or device identifier is collected or derived. There is no MAC address,
  serial number, hostname, username, or machine fingerprint in any payload. `install_id` is not
  derived from any machine property.

`SKILL_BILL_INSTALL_ID` lets you pin the id explicitly, which is how CI installs stay
deterministic.

## Data retention

Stated as what is true today:

- Automated retention enforcement is not implemented in the relay. The example Worker forwards
  events and does not expire or delete them.
- Retention of forwarded events is governed by the analytics backend the relay points at, under
  whatever settings that backend is configured with.
- Self-hosters set their own retention. Pointing `SKILL_BILL_TELEMETRY_PROXY_URL` at your own
  relay puts retention entirely under your control.
- Local queued telemetry is not retained indefinitely: `skill-bill telemetry disable` and any
  level downgrade clear the pending outbox. An upgrade — including `off` to `anonymous` — does not,
  so rows enqueued while `off` survive until the next sync or the next downgrade. Local review snapshots have no expiry and are pruned
  with `skill-bill prune-snapshots --confirm`.

## Retired event names

The `skillbill_feature_task_prose_*`, `skillbill_feature_implement_*`, and
`skillbill_goal_prose_*` events were removed with the prose lane. The bundled
telemetry proxy accepts them unchanged: a batch carrying a retired name is never
rejected, and the events are forwarded to the analytics backend as-is and kept
under its normal retention. The retirement is enforced only on the reporting
side — no `/stats` aggregate counts a retired name. Already-installed older
clients therefore keep receiving normal success responses instead of errors. Locally, the outbox
takes only a `TelemetryOutboxEvent`, and that registry does not define the retired names, so an
in-tree emitter cannot enqueue one: it does not compile.
