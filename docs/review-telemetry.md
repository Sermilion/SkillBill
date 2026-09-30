# Review telemetry

## Review mode telemetry

There are three review modes and telemetry reports which one produced a result.
`inline` is the default light depth tier: one review subagent covering the routed
areas at reduced judgment depth with full broker evidence delivery, with no specialist fan-out.
`delegated` is the experimental full-depth tier, reached only by explicit
selection: the reviewing agent fans the routed areas out to specialist subagents
inside its own harness. `auto` and omission both resolve to `inline` for every
pass and for a scope with no pass number.

Mode telemetry is bounded accounting, not a transcript. It may record elapsed
time, selected and completed routed areas, launched and failed
lanes, and the resolved mode with the applicable named `auto` rule. It never
retains prompts, complete diffs, source bodies, raw transcripts, or tool logs.

An inline result is consumed exactly as a delegated one: the same severity
vocabulary, the same finding admission gate, and the same `F-XXX` risk-register
lines flow through import, triage, and `skillbill_review_finished`. Telemetry
records that specialist depth was not applied rather than treating the inline
result as equivalent coverage.

## Bounded delegated-review accounting

Delegated reviews enforce byte, evidence-read, result-size, and assignment-expansion limits before or during a lane. The named `ReviewContextBudgetPolicy.DEFAULT` policy uses 524288 parent-packet bytes, 65536 lane-launch bytes, 262144 cumulative lane-evidence bytes, 65536 bytes per evidence result and lane result, and three assignment expansions. Repositories may override individual values through the strict `review_context_budget` object in `.skill-bill/config.yaml`; malformed, negative, unknown, or inconsistent nested values fail before launch.

Two further per-specialist limits bound a lane's own work: 40 tool calls and 24 model turns. Overrides are strict — every supplied value must be a positive integer, evidence-result bytes cannot exceed cumulative lane-evidence bytes, and lane-launch bytes cannot exceed parent-packet bytes. An incoherent combination fails before launch rather than being clamped.

Delegated specialists never rediscover what the parent already resolved. Repository status, scope, base/head revisions, diff recomputation, build and test invocation, platform-pack and add-on resolution, routing, learnings resolution, and project-guidance files are refused at the broker with a typed forbidden-operation category. Evidence outside a lane's assignment requires an authorized expansion recorded in the parent packet's ledger; the default budget admits three per assignment.

Harness-native specialists are verified before any lane starts. A missing, stale, or dangling managed native-agent link fails the review with the logical specialist name, harness, expected path, reason, and the repair command `skill-bill install apply`. There is no generic-specialist fallback — repair the install and rerun.

Durable output and telemetry retain only byte/count accounting, lane identifiers
(harness-returned launch id when the harness provides one, otherwise plan-identity
such as routed area and assignment digest), packet and assignment digests,
and terminal outcomes. Prompts, diffs, source, project
guidance, rubric bodies, and tool output remain transient.

Skill Bill can record a measurement loop for code-review usefulness. Telemetry uses a three-level model selected during install: `off`, `anonymous` (default), or `full`.

- each top-level review session should expose a `Review session ID: ...` using `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`)
- each concrete review output should expose a `Review run ID: ...` using `rvw-YYYYMMDD-HHMMSS-XXXX` (4-char random alphanumeric suffix for uniqueness)
- each finding in `### 2. Risk Register` should use `- [F-001] Severity | Confidence | file:line | description`
- feedback history and learnings stay local in SQLite regardless of telemetry state

The `skill-bill` CLI and MCP server are installed automatically by `./install.sh`. The MCP server exposes review, learning, workflow-state, workflow-stats, telemetry, scaffold, quality-check, PR-description, and health tools. The CLI provides the same runtime functionality plus learnings CRUD, install primitives, validation, and telemetry management.

```bash
skill-bill --help
```

Default database path:

```text
~/.skill-bill/review-metrics.db
```

Default config path (durable, survives installs):

```text
~/.config/skill-bill/config.json
```

You can override the database path with `--db` or `SKILL_BILL_REVIEW_DB`.

Typical workflow:

1. Save a review output to a text file.
2. Import the review so the run and findings are stored locally.
3. Use numbered triage to respond with issue numbers instead of raw finding ids.
4. Optionally store reusable learnings separately from raw feedback history.
5. Resolve active learnings for the next review context when you want that feedback to influence future reviews explicitly.
6. Query summary stats for one run or for all imported runs.

Example:

```bash
skill-bill import-review review.txt
skill-bill triage --run-id rvw-20260402-001
skill-bill triage --run-id rvw-20260402-001 --decision "1 fix - keep current terminology" --decision "2 skip - intentional"
skill-bill triage --run-id rvw-20260402-001 --decision "fix=[1] reject=[2]"
skill-bill triage --run-id rvw-20260402-001 --decision "all fix"
skill-bill learnings resolve --repo Sermilion/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001
skill-bill stats --run-id rvw-20260402-001 --format json
skill-bill feature-task-stats --format json
skill-bill goal-stats --format json
skill-bill verify-stats --format json
```

`skill-bill stats` (and MCP `review_stats`) include per-stage verdict distribution and `refutation_rate_by_stage` from the same durable-row aggregator as `skillbill_review_finished`. A `--run-id` payload also carries that run's `resolved_tier`. Unscoped stats group those rates by `inline` and `delegated` under `stage_metrics_by_tier` and do not emit a pooled rate.

The `triage` command maps the visible numbers back to the stable `F-001` ids internally. For agent-driven flows, prefer a structured selection string like `fix=[1,3] reject=[2]` so every finding is resolved deterministically in one step. Use `all <action>` to apply the same action to every finding. Supported triage actions are:

- `fix` -> records `fix_applied`
- `accept` -> records `finding_accepted`
- `edit` -> records `finding_edited`
- `skip`, `dismiss`, or `reject` -> records `fix_rejected`
- `false positive` -> records `false_positive`

In agent-driven review flows, only the parent review that owns the final merged review output for the current review lifecycle should call `import_review` and `triage_findings`. Delegated child reviews stay telemetry-free and return their review data and metadata to that parent.

You can still use the low-level command when you want direct control:

```bash
skill-bill record-feedback --run-id rvw-20260402-001 --event fix_applied --finding F-001 --note "keep current terminology"
```

## Learnings

Learnings are actionable domain-specific knowledge derived from **rejected** review findings. When you reject a finding and explain why, you can promote that rejection into a reusable learning so future reviews avoid the same mistake.

```bash
# First reject a finding during triage:
skill-bill triage --run-id rvw-20260402-001 --decision "2 reject - installer wording is intentionally informal"

# Then promote the rejection into a learning:
skill-bill learnings add --scope repo --scope-key Sermilion/skill-bill --title "Installer wording is intentionally informal" --rule "Do not flag installer prompt wording as inconsistent — the informal tone is a deliberate UX choice for CLI tools." --from-run rvw-20260402-001 --from-finding F-002

# Manage learnings:
skill-bill learnings list
skill-bill learnings show --id 1
skill-bill learnings edit --id 1 --reason "Confirmed by repeated skip feedback."
skill-bill learnings disable --id 1
skill-bill learnings delete --id 1
```

Both `--from-run` and `--from-finding` are required — learnings must trace back to a rejected finding. When `--reason` is omitted, the rationale is auto-populated from the rejection note.

The `add_learning` MCP tool creates the same learning from a running review and applies the same rejected-source validation: the source finding must exist and its latest outcome must be a rejection, or the call fails with a typed error. Triage only suggests: `triage_findings` returns `learning_candidates` for noted rejections, and the parent review calls `add_learning` after the user confirms which candidates to promote.

Raw finding-outcome history and learnings are stored separately. That means you can wipe or disable reusable learnings without losing the original review-feedback history.

Reviews apply those learnings automatically. The review driver resolves them inside the run: it derives the repo scope key from the reviewed repository's `origin` remote normalized to its full repository path (nested groups keep every segment), derives the skill scope key from the routed review skill, persists the resolved set as the run's `session_learnings` row, and delivers each learning's title and rule text inside every worker launch envelope. No agent calls a learnings tool. With no `origin` remote the driver resolves global and skill learnings only and records a diagnostic.

Inspect the same resolution by hand with:

```bash
skill-bill learnings resolve --repo Sermilion/skill-bill --skill bill-kotlin-code-review --review-session-id rvs-20260402-001 --format json
```

Resolution stays local-first and explicit:

- only `active` learnings apply
- precedence is `skill > repo > global`
- resolution yields stable learning references such as `L-003`
- a review session id keys the resolved set so the resolved-learning event groups with the matching review session; pass `--review-session-id` to `skill-bill code-review` when a parent workflow already holds one, otherwise the driver mints one
- review output surfaces `Applied learnings: none | <learning references>` so the behavior is auditable

This is intentionally not hidden auto-learning. The learnings layer remains inspectable, editable, disable-able, and deletable by the user.

## Stored review-run attribution

The `review_runs` table stores review attribution twice: the raw text exactly as reported, and
a canonical identifier resolved from it. The raw columns `routed_skill`, `detected_stack`, and
`detected_scope` are never rewritten; their canonical counterparts are
`routed_skill_canonical`, `detected_stack_canonical`, and `detected_scope_canonical`, plus
`detected_scope_detail` for the parenthesized remainder of a scope string (for example the
branch pair in `commit range (main...HEAD)`).

This is the stored-column contract, distinct from the emitted-payload normalization the
`skillbill_review_finished` event performs — the payload's `routed_skill`, `platform_slug`, and
`scope_type` fields are derived for the wire, while these columns are what the local store keeps.

| Canonical column | Resolves against |
|---|---|
| `routed_skill_canonical` | Known pack skill names of the form `bill-<slug>-code-review` (the discovered pack skills unioned with the canonical set: `kmp`, `kotlin`, `ios`, `python`, `php`, `go`, `rust`, `typescript`, `android`, `java`, `ruby`, `docs`, `generic`) |
| `detected_stack_canonical` | The same platform slugs; `kotlin multiplatform` and `kmp` both resolve to `kmp` |
| `detected_scope_canonical` | The controlled scope vocabulary: `working_tree`, `staged`, `commit_range`, `pull_request`, `other` |

### The unresolved-value convention

A value that cannot be resolved unambiguously is never silently bucketed into a plausible
category and never guessed. The raw text is retained as reported and the canonical column is
set to the explicit marker `unresolved` — the default of all three canonical columns. Ambiguity
counts as unresolvable: a raw `routed_skill` mentioning two pack skills, or a raw stack matching
two slugs, resolves to `unresolved` rather than picking one. The same marker is used for
`execution_mode`, which is `delegated` only when the run's own recorded specialist reviews prove
delegation and `unresolved` otherwise.

The canonical backfill for pre-existing rows is unambiguous-only under the same rule: it writes
only columns still holding `unresolved`, never touches the raw columns, and leaves rows whose
raw text does not resolve at `unresolved`.

## Per-lane review attribution

The `review_runs.specialist_reviews` column stays exactly as reported: it is parsed out of the
review text's `Specialist reviews:` / `Baseline review:` lines and persisted as one comma-joined
string. The plan-sourced attribution lives alongside it in `review_run_lanes`, one row per lane.
For runtime-launched reviews those rows come straight from the composed launch plan — the lanes
the runtime actually planned and launched. For imported reviews the lanes are resolved by
matching the parsed `specialist_reviews` narration against the composed plan, so a lane that
cannot be matched is recorded as `unresolved`:

| `review_run_lanes` column | Meaning |
|---|---|
| `lane_skill_name` | The specialist skill that ran, for example `bill-kmp-code-review-persistence` |
| `pack_slug`, `area` | The pack and declared area the lane came from |
| `depth`, `required`, `order_index` | Composition position of the lane in the launch plan |
| `origin_layer_chain` | Baseline-layer chain the lane was composed through |
| `resolution_state` | `resolved`, or `unresolved` when the lane could not be matched to a composed plan lane |

Every finding carries the lane that produced it. `findings.lane_skill_name` is the authoritative
attribution the runtime records from its own merge result before the review text is imported;
`review_run_finding_lanes` holds that same run-time mapping keyed by `(review_run_id,
finding_id)`. Provenance parsed out of review text is only the fallback for externally supplied
reviews.

Together with the canonical routed skill this makes pack-and-area effectiveness measurable —
which specialist area produces findings that survive triage:

```sql
SELECT r.routed_skill_canonical,
       l.pack_slug,
       l.area,
       COUNT(*)                                                   AS findings,
       SUM(CASE WHEN e.event_type = 'fix_applied' THEN 1 ELSE 0 END)   AS applied,
       SUM(CASE WHEN e.event_type = 'false_positive' THEN 1 ELSE 0 END) AS false_positives
FROM findings f
JOIN review_runs r ON r.review_run_id = f.review_run_id
LEFT JOIN review_run_lanes l
  ON l.review_run_id = f.review_run_id AND l.lane_skill_name = f.lane_skill_name
LEFT JOIN feedback_events e
  ON e.review_run_id = f.review_run_id AND e.finding_id = f.finding_id
WHERE r.routed_skill_canonical != 'unresolved'
GROUP BY r.routed_skill_canonical, l.pack_slug, l.area;
```

## The shared finding key

Findings recorded through review-run import (`review_runs` / `findings`, keyed by
`(review_run_id, finding_id)`) and findings raised inside the workflow review loop
(`unaddressed_findings`, keyed by `(workflow_id, review_pass_number, finding_ordinal)`) are
joined by the shared key `(review_run_id, finding_id)`, carried on both stores.

- `unaddressed_findings` gained nullable `review_run_id` and `finding_id` columns plus the
  `idx_unaddressed_findings_run` index.
- `review_finding_outcomes` records each workflow-loop finding's terminal disposition
  (`addressed`, `carried`, `rejected`) against that same key, with `key_state` being `resolved`
  only when both key columns are present and `unresolved` otherwise. It is a table of its own
  rather than columns on `unaddressed_findings` because that ledger is retracted and rewritten
  each pass, which would destroy an outcome stored on it.
- There is deliberately no foreign key to `findings`: a workflow-loop finding need not have been
  imported as a review run.

Through this key a triage disposition or `feedback_events` row on an imported finding resolves
back to the routed pack via `review_runs.routed_skill_canonical`, and a workflow-loop outcome
resolves to the same pack whenever its key is `resolved`.

Backfill coverage is honest about its limits: the key columns are added with no default and no
historical backfill. Rows written before the key existed — and any pass for which no review run
was imported — keep both columns NULL and read as `unresolved` rather than being attached to a
guessed review run. Those rows stay unjoined, so pack attribution over workflow-loop history is
complete only from the point the key was introduced.

## Snapshot retention and pruning

`~/.skill-bill/review-metrics.<label>.db` files are snapshots: hand-named copies of the live
store taken before a risky migration or to preserve a point-in-time reading.

Retention policy: snapshots are operator artifacts with no expiry. Nothing in the runtime
creates, rotates, or deletes them, so they accumulate until you prune them — one observed home
had accumulated 37 snapshots totalling roughly 2.9 GB. Pruning is opt-in and operator-driven:
the command below is the only code path in the runtime that removes a snapshot, and existing
snapshots are never deleted automatically. Selection is by filename pattern; the live
`review-metrics.db` never matches it and is therefore never a candidate.

```bash
# Dry run (default): lists candidate snapshots and deletes nothing.
skill-bill prune-snapshots

# Actually delete the listed snapshots.
skill-bill prune-snapshots --confirm
```

`--confirm` is the only flag that deletes; without it the command lists candidates only.
`--format json` returns the live database path, the confirm flag, the candidate list, and the
list actually deleted.

## Telemetry levels

Telemetry has three levels:

| Level | What is sent |
|-------|-------------|
| `off` | Nothing is transmitted; `sync` and `autoSync` short-circuit on the disabled level. Five events are still queued locally: `skillbill_runtime_exception` (message redacted to `[redacted]`, `skillbill.` frames only), `skillbill_feature_task_runtime_projection_measurement` (bounded counters, the repository checkpoint fingerprint, `contract_version`, `consumer_phase_id`, and a raw `workflow_id`), `skillbill_feature_task_runtime_shared_evidence` (checkpoint fingerprint, consuming phase id, outcome, and bounded index counters), `skillbill_feature_task_runtime_diagnostic_degradation` (workflow, phase, attempt, generation, operation, typed failure class, conflicting key; `repair_turn` only when scoped to one turn), and `skillbill_review_stage_degradation` (review_run_id, seam, expected, actual, and a closed reason). Enabling telemetry later does not discard those rows — `clearsPendingOutbox("off", "anonymous")` is `false` — so they can upload on the next sync. |
| `anonymous` | Aggregate counts, finding ids with issue category/severity/confidence/outcome type, anonymized learning references, and `install_id` plus `skill_bill_version` on every uploaded event. Issue keys — and issue keys embedded in correlation ids such as `SKILL-1:subtask:2` — are replaced with a salted SHA-256 prefix. `skillbill_runtime_exception` carries `error_message` as `[redacted]` and keeps only `skillbill.` stack frames. No file paths, descriptions, notes, learning content, or repository name. Exceptions: `skillbill_feature_task_runtime_projection_measurement` and `skillbill_feature_task_runtime_shared_evidence` are enqueued without a telemetry level and upload `contract_version`, `consumer_phase_id`, and a raw, unhashed `workflow_id` alongside their bounded counters. `skillbill_feature_task_runtime_diagnostic_degradation` is likewise enqueued without a telemetry level and uploads `contract_version`, `phase_id`, `attempt`, `generation`, `operation`, `failure_class`, `conflicting_key`, and a raw `workflow_id`. |
| `full` | Everything in `anonymous` plus: finding descriptions/titles, file locations, rejection notes, learning content (title, rule text), raw issue keys and correlation ids, and unredacted `error_message` and stack frames. Useful for teams that want actionable detail. |

`skill_bill_version` is attached from the `telemetry_outbox.skill_bill_version` column by
`telemetryProperties`; a row enqueued before release attribution existed omits the property rather
than sending a sentinel. No repository name is uploaded at any level: `resolve_learnings` accepts a
`repo` learning scope, but that value is validated locally and never enters an outbox payload.

The per-field breakdown across all events lives in
[Telemetry Privacy](telemetry-privacy.md), which uses the same field names and per-level verdicts
as this table.

The default level is `anonymous`. Existing configs with `telemetry.enabled: true` are migrated to `anonymous`; `enabled: false` becomes `off`.

## Telemetry events

The telemetry model emits a single event per review lifecycle:

- one `skillbill_review_finished` event when a review lifecycle becomes fully resolved (all findings triaged)

The finished event carries: total/accepted/rejected/unresolved finding counts, accepted/rejected rates, accepted/rejected finding details, a nested `learnings` object, normalized routed skill, normalized `review_platform`/`detected_stack`/`platform_slug`, optional `detected_stack_detail`, normalized `scope_type`, execution mode, specialist reviews, fallback metadata, and a distinct canonical `review_session_id` field so related telemetry can be grouped together in PostHog. Finding details always include `issue_category`, `severity`, `confidence`, and `outcome_type`; file locations, descriptions, and rejection notes are included only at `full` level. `unresolved_findings` is the count of findings whose latest outcome is not terminal yet; the finished event is emitted only once that count reaches zero. If a later import materially changes the review and reopens unresolved findings, Skill Bill clears the finish marker and emits a fresh event the next time the review becomes fully resolved.

The review issue category taxonomy is: `behavior_correctness`, `data_persistence`, `concurrency_lifecycle`, `ux_accessibility`, `testing_quality_gate`, `security_privacy`, `docs_contract`, and `other`.

When `learnings resolve` is called with `--review-session-id`, the resolved learnings are cached locally and included in the matching `skillbill_review_finished` event when it fires.

## Where this contract lives

The shared telemetry contract — standalone-first behavior, orchestrated flag semantics, child_steps aggregation, routers-never-emit rule, and ownership rules for `import_review` / `triage_findings` — is the single source of truth at `orchestration/telemetry-contract/PLAYBOOK.md`.

Each telemeterable skill consumes this contract through a generated `telemetry-contract.md` support pointer in its installed staging directory. That pointer resolves to `orchestration/telemetry-contract/PLAYBOOK.md`, so agents reading the installed skill always reach the canonical rules without needing to know the repo layout. The pointer is render/install output, not a committed source file under `skills/`. Skill-specific telemetry fields (session id format, event names, payload fields) remain in each skill's rendered `SKILL.md` and authored `content.md`.

## Session correlation

Skill Bill uses **parent-owned telemetry** across the whole skill suite. A single user-initiated workflow produces **exactly one** telemetry event, even when multiple skills run inside it.

### Standalone-first contract

Every telemeterable skill must be usable alone. When invoked directly by a user, each skill generates its own session id and emits its own events:

- `bill-code-review` lifecycle — `skillbill_review_finished` once the final review output is imported and all findings resolve
- `bill-code-check` lifecycle — `skillbill_quality_check_started` + `_finished`
- `bill-feature-verify` — `skillbill_feature_verify_started` + `_finished`
- `bill-pr-description` — `skillbill_pr_description_generated`

### The `orchestrated` flag

Every telemeterable MCP tool accepts `orchestrated: bool = false`.

- **`orchestrated=false` (standalone):** the tool generates its own session id, emits started/finished events, and owns its lifecycle.
- **`orchestrated=true` (nested):** the tool emits **zero** telemetry events. Instead it returns a `telemetry_payload` dict on the tool result that the caller (the orchestrator) collects.

The orchestrator is responsible for setting the flag. A child skill never infers orchestrated mode from ambient state.

### `child_steps` aggregation

When the parent's finished event fires, it embeds each collected `telemetry_payload` in a `child_steps` array. One workflow, one event:

```json
{
  "event": "skillbill_<parent>_finished",
  "session_id": "fis-20260413-104704-l84r",
  "completion_status": "completed",
  "duration_seconds": 1820,
  "child_steps": [
    {
      "skill": "bill-code-review",
      "review_session_id": "rvs-...",
      "total_findings": 7,
      "accepted_findings": 6,
      "rejected_findings": 1,
      "unresolved_findings": 0,
      "accepted_rate": 0.86,
      "rejected_rate": 0.14,
      "platform_slug": "kotlin",
      "scope_type": "branch_diff",
      ...
    },
    {
      "skill": "bill-code-check",
      "routed_skill": "bill-code-check",
      "result": "pass",
      "iterations": 2,
      ...
    },
    {
      "skill": "bill-pr-description",
      "commit_count": 3,
      "pr_created": true,
      ...
    }
  ],
  ...
}
```

With the prose lane retired, no currently emitted event carries `child_steps`: the runtime finished event (`skillbill_feature_task_runtime_finished`) has no such field. The array stays part of the orchestrator contract for future parents, and historical `skillbill_feature_task_prose_finished` rows still carry it.

### Graceful degradation

If a parent skill forgets to pass `orchestrated=true` to a child, the child emits its own standalone event. The workflow produces extra events but nothing is lost. Always pass the flag from the orchestrator's `SKILL.md` instructions.

### Non-goals

- **No cross-event correlation field.** There is no `parent_session_id` joining separate events — correlation by construction (one event per workflow) replaces correlation by foreign key.
- **No auto-detection.** Children never decide "am I orchestrated?" themselves.
- **No global session registry.** Each skill run is self-sufficient.

### Router skills never emit

`bill-code-review` and `bill-code-check` are thin routers. They do not emit telemetry merely because routing happened. Routing metadata is carried inside the concrete routed skill's telemetry call, and the router passes `orchestrated` through to the routed concrete skill unchanged. The user-facing standalone lifecycle can still produce the events listed below once the routed workflow reaches its telemetry seam.

### Event catalog

| Event | Emitted by | Orchestrated alternative |
|-------|------------|--------------------------|
| `skillbill_review_finished` | top-level code-review lifecycle once findings are resolved | `import_review` / `triage_findings` with `orchestrated=true` return payload instead |
| `skillbill_review_stage_degradation` | verification/adjudication measurement after those stages settle | none; queued even at telemetry `off` |
| `skillbill_quality_check_started` | standalone quality-check lifecycle | skipped in orchestrated mode |
| `skillbill_quality_check_finished` | standalone quality-check lifecycle | `quality_check_finished(orchestrated=true)` returns payload |
| `skillbill_feature_verify_started` | `bill-feature-verify` (standalone) | skipped in orchestrated mode |
| `skillbill_feature_verify_finished` | `bill-feature-verify` (standalone) | `feature_verify_finished(orchestrated=true)` returns payload |
| `skillbill_pr_description_generated` | `bill-pr-description` (standalone), and the runtime `pr` step after it completes (skeleton run and `skill-bill phase pr`) | `pr_description_generated(orchestrated=true)` returns payload |

## Quality-check telemetry

The quality-check workflow emits two events per standalone session:

- `skillbill_quality_check_started` — emitted once stack routing is resolved and the first check run begins
- `skillbill_quality_check_finished` — emitted when the quality-check loop terminates

Session id format: `qck-YYYYMMDD-HHMMSS-XXXX`.

### Quality-check payloads

Both `anonymous` and `full`:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `qck-YYYYMMDD-HHMMSS-XXXX` |
| `routed_skill` | string | Always `bill-code-check` for quality-check lifecycle events; blank/unresolved routing emits `unrouted` |
| `detected_stack` | string | Normalized stack slug routed for; blank/unresolved stack emits `unknown` |
| `fallback` | boolean | Whether routing used a fallback path |
| `fallback_reason` | string | Optional stable reason supplied by a route that actually used a fallback |
| `scope_type` | string | `files`, `working_tree`, `branch_diff`, or `repo` |
| `initial_failure_count` | integer | Failing checks before the first fix run |

`_finished` adds:

| Field | Type | Description |
|-------|------|-------------|
| `final_failure_count` | integer \| null | Failing checks after the last fix attempt. Null unless `final_failure_count_availability` is `measured`. |
| `final_failure_count_availability` | string | `measured`, `unavailable_incomplete` (the reconciler closed this check), or `unknown` (a row that predates the field). |
| `completion` | string | `operator_completed` for a check that reported its own terminal; `reconciler_stale` for one the reconciler closed after it stopped reporting. |
| `stale_reason` | string | Present only on a `reconciler_stale` row; a closed token, today always `no_terminal_before_threshold`. |
| `iterations` | integer | Fix-run cycles |
| `result` | string | `pass`, `fail`, `skipped`, `stale`, or `unsupported_stack` |
| `duration_seconds` | integer | Wall-clock seconds from started to finished |

**Clean-gate queries.** Filter on `completion = 'operator_completed'` **and**
`final_failure_count_availability = 'measured'` together, in both the numerator and the denominator.
A `reconciler_stale` row carries no failure count at all, so a query that treats a missing count as
zero reports checks as clean that nobody ever observed finishing.

The same rule governs the local aggregates. `skill-bill feature-task-stats` reports `finished_runs`
(rows with a terminal of any kind), `observed_runs` (terminals the run itself reported), and
`reconciler_closed_runs`. Every rate — `completed_rate`, `blocked_rate`, `decomposed_rate`,
`error_rate` — is over `observed_runs`, not `finished_runs`. Mirror that denominator in any dashboard
query rather than dividing by the terminal count.

`skill-bill goal-stats` has the same shape at a different grain. A `goal_run_sessions` row is one
invocation segment, and every resume writes a new one, so `total_runs` counts invocations rather than
goals. Read `logical_goals` for the goal count, `resumed_invocations` for how many of those segments
were restarts, and `goal_identity_availability` to know whether the attribution is complete:

| Field | Type | Description |
|-------|------|-------------|
| `total_runs` | integer | Invocation segments in the store, resumes included |
| `logical_goals` | integer | Distinct goals those segments belong to |
| `resumed_invocations` | integer | Segments that restarted an already-started goal |
| `invocations_with_unknown_goal` | integer | Segments written before the parent id was persisted |
| `goal_identity_availability` | string | `measured`, `unavailable_incomplete`, or `unavailable_no_durable_state` |

Segments recorded before the parent id existed are not attributable and are never folded into
`logical_goals` as one anonymous goal; they are counted in `invocations_with_unknown_goal` and drop
`goal_identity_availability` below `measured`. A dashboard that divides by `total_runs` is reporting
a per-invocation rate, which is a different question from how often goals succeed — say which one it
answers.

Remotely, the same split already exists as two events: `skillbill_goal_finished` is per invocation
segment, and `skillbill_goal_issue_finished` is the goal, carrying `parent_workflow_id`,
`total_invocations`, `total_resumes`, and `total_blocks`. Build goal-level rates on
`skillbill_goal_issue_finished`; counting `skillbill_goal_finished` rows counts restarts.

`full` level additionally includes in `_finished`:

| Field | Type | Description |
|-------|------|-------------|
| `failing_check_names` | list | Check names that remained failing at the end |
| `unsupported_reason` | string | Explanation when `result` is `unsupported_stack` |

## Feature-verify telemetry

The feature-verify workflow emits two events per standalone session:

- `skillbill_feature_verify_started` — emitted after Step 2 (acceptance criteria confirmed)
- `skillbill_feature_verify_finished` — emitted after Step 8 (verdict delivered) or when the workflow ends early

Session id format: `fvr-YYYYMMDD-HHMMSS-XXXX`.

### Feature-verify payloads

Both levels:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `fvr-YYYYMMDD-HHMMSS-XXXX` |
| `acceptance_criteria_count` | integer | Number of criteria extracted from the spec |
| `rollout_relevant` | boolean | Whether the spec requires a guarded rollout audit |

`_finished` adds:

| Field | Type | Description |
|-------|------|-------------|
| `feature_flag_audit_performed` | boolean | Whether the rollout/feature-flag audit ran |
| `review_iterations` | integer | Code-review iteration count |
| `audit_result` | string | `all_pass`, `had_gaps`, or `skipped` |
| `completion_status` | string | `completed`, `abandoned_at_review`, `abandoned_at_audit`, or `error` |
| `history_relevance` | string | How relevant history-entry reading was: `none`, `irrelevant`, `low`, `medium`, or `high` |
| `history_helpfulness` | string | How helpful history-entry reading was: `none`, `irrelevant`, `low`, `medium`, or `high` |
| `duration_seconds` | integer | Wall-clock seconds from started to finished |

`full` adds:

| Field | Type | Description |
|-------|------|-------------|
| `spec_summary` | string | One-sentence summary of the verified feature (from `_started`) |
| `gaps_found` | list | Short descriptions of gaps identified during the completeness audit |

## PR description telemetry

The PR description workflow emits a single event per generation:

- `skillbill_pr_description_generated` — emitted after the PR description is presented (and, when applicable, after the PR is created)

Session id format: `prd-YYYYMMDD-HHMMSS-XXXX`.

### PR description payload

Both levels:

| Field | Type | Description |
|-------|------|-------------|
| `session_id` | string | `prd-YYYYMMDD-HHMMSS-XXXX` |
| `commit_count` | integer | Commits included in the PR |
| `files_changed_count` | integer | Files changed in the PR |
| `was_edited_by_user` | boolean | Whether the user requested changes to the generated description |
| `pr_created` | boolean | Whether the PR was actually created |

`full` adds:

| Field | Type | Description |
|-------|------|-------------|
| `pr_title` | string | Generated PR title |

## Audit-repair counters

Runtime-mode feature-task sessions report their own terminal event directly from the foreground runtime driver's lifecycle telemetry, emitted as `skillbill_feature_task_runtime_finished`. Alongside `review_fix_iteration_count`, that event describes the completeness-audit loop at the one grain the runtime durably owns. They are compact numbers and booleans — no gap text, criterion text, diagnoses, evidence strings, paths, or agent output is sent at any telemetry level.

| Field | Type | Description |
|-------|------|-------------|
| `audit_gap_availability` | string | `measured` when the run held a durable phase ledger to count from; `unavailable_no_durable_state` when it did not; `unknown` on a row written before this field existed. |
| `audit_gap_measurement_grain` | string | Always `audit_gap_rounds_per_run`. Stated on the wire so no consumer has to guess what one unit counts. |
| `audit_gap_iteration_count` | integer \| null | Audit-gap rounds the run durably entered: one per `audit_repair` loop edge, plus one per older `audit_ac_retry` continuation and legacy `audit_gap` loop edge on runs that predate the stateless audit. Null unless `audit_gap_availability` is `measured`. |
| `audit_first_pass_convergence` | boolean \| null | Whether the first audit was satisfied outright. True only when `audit_gap_iteration_count` is 0; null whenever that count is null. |
| `audit_repair_item_availability` | string | Always `unavailable_unsupported`. Per-gap and per-repair-item accounting has no durable identity in runtime state, so no such counter is emitted. |

Read `audit_gap_availability` before `audit_gap_iteration_count`. A null count is "nobody measured this run", which is a different fact from a measured zero, and a clean-rate denominator built without that distinction silently counts unmeasured runs as converged.

`audit_first_pass_convergence` is the headline quality signal for the implementation phase: across runs where it is non-null, a high rate means implementation is landing acceptance criteria the first time, and a falling rate means specs, planning, or review are letting unmet criteria through to the audit.

Recurring-gap, new-gap, attempted-repair-item, and resolved-repair-item counters are **not** emitted. Earlier revisions of this document described them; no runtime producer ever computed them. Reconstructing them needs a per-criterion gap identity that durable audit state does not carry, so `audit_repair_item_availability` declares them unsupported rather than reporting a zero nobody measured.

## Resolved agent and model context

The same terminal event reports which agents and models the run's phases actually resolved, read from the durable phase records rather than from anything an agent reported about itself.

| Field | Type | Description |
|-------|------|-------------|
| `agent_context_measurement_grain` | string | Always `distinct_resolved_agents_per_run`. One unit is the distinct set observed across the run's phases, not a per-phase value. |
| `resolved_agent_availability` | string | `measured` when at least one durable phase record named a resolved agent; `unavailable_no_durable_state` when none did. |
| `resolved_agent_ids` | array \| null | Distinct, sorted agent slugs the run's phases resolved. Null unless `resolved_agent_availability` is `measured`. |
| `launched_model_availability` | string | `measured` when at least one phase launched under a model directive; `unavailable_no_durable_state` when none did. |
| `launched_models` | array \| null | Distinct, sorted model strings the phases were launched with, in the exact form handed to the agent CLI. Null unless `launched_model_availability` is `measured`. |

A run can know its agents and not its models: a phase that ran with no model directive resolves an agent and launches no model. Segment on `resolved_agent_ids` only after filtering on `resolved_agent_availability`; a null set means the run recorded nothing, never that it ran without an agent.

## PostHog dashboard spec

The local `skill-bill feature-task-stats`, `skill-bill goal-stats`, and `skill-bill verify-stats` commands are the source of truth for workflow-summary semantics. PostHog dashboards should mirror those summaries instead of inventing a separate analytics vocabulary.

Recommended global filters:

- date ranges: last 7 days, last 30 days, and rolling quarter
- use started events for intake/adoption charts
- use finished events for completion, iteration, and duration charts
- exclude non-production installs by default: require `properties.install_id IS NOT NULL`, `trim(toString(properties.install_id)) != ''`, and `toString(properties.install_id) != 'test-install-id'`
- do not mix started and finished denominators in one chart unless the chart explicitly describes funnel dropoff

### Feature-verify dashboard

Use `skillbill_feature_verify_started` for intake metrics and `skillbill_feature_verify_finished` for outcome metrics.

Recommended tiles:

- `Verify runs started`: count of `skillbill_feature_verify_started`
- `Verify runs finished`: count of `skillbill_feature_verify_finished`
- `Verify completion rate`: `completion_status = completed` on finished events divided by all finished events
- `Verify abandonment rate`: `completion_status != completed` on finished events divided by all finished events
- `Rollout-relevant rate`: `rollout_relevant = true` on started events divided by all started events
- `Audit gaps rate`: `audit_result = had_gaps` on finished events divided by all finished events
- `History read rate`: finished events where history telemetry is not `none` divided by all finished events
- `History relevant rate`: finished events where `history_relevance in (medium, high)` divided by all finished events
- `History helpful rate`: finished events where `history_helpfulness in (medium, high)` divided by all finished events
- `Average review iterations`: average `review_iterations` on finished events
- `Average verify duration`: average `duration_seconds` on finished events
- `Acceptance criteria distribution`: histogram or average of `acceptance_criteria_count` on started events

Recommended breakdowns:

- `completion_status`
- `audit_result`
- `rollout_relevant`
- `history_relevance`
- `history_helpfulness`

Mirror to local stats:

- `total_runs` ~= count of started events
- `completion_status_counts` = finished breakdown by `completion_status`
- `audit_result_counts` = finished breakdown by `audit_result`
- `rollout_relevant_rate` = started filter on `rollout_relevant = true`
- `history_read_rate` = finished runs where `history_relevance != none` or `history_helpfulness != none`
- `history_relevant_rate` = finished runs where `history_relevance in (medium, high)`
- `history_helpful_rate` = finished runs where `history_helpfulness in (medium, high)`
- `average_review_iterations` = average on finished `review_iterations`
- `average_duration_seconds` = average on finished `duration_seconds`

### Health stat defaults

Local health views use the rows available in the local telemetry database. `source_counts` buckets
every record by where its payload came from — `standalone` (a review run's own record), `embedded` (a
record carried inside another workflow's payload), `malformed` (a record that did not parse), and
`unknown` (a record whose source this surface could not classify). `unknown` is always present, at
zero when nothing landed in it, so a reader can tell "no unclassified records" apart from a view that
cannot report unclassified records at all.

Review health publishes three grains separately, because no single count over "records" answers more
than one of the questions a reader usually means:

| Aggregate | Unit | Definition |
|-----------|------|------------|
| `queued_delivery_rows` | transport row | Review payloads that reached the local outbox. An embedded payload never did and is not counted here. |
| `delivery_attempts` | transport attempt | Sum of `telemetry_outbox.delivery_attempts`. One event retried three times contributes three. Never a review denominator. |
| `logical_events` | minted event | Distinct `telemetry_outbox.event_uuid`. A retry is the same event; a re-emission after a correction is a new one. |
| `rows_with_unknown_delivery_identity` | transport row | Outbox rows written before `event_uuid` existed. Reported, never folded into `logical_events`. |
| `logical_reviews` | review | Distinct non-blank `review_run_id` across included payloads. The only honest review denominator. |
| `records_with_unknown_review` | record | Included payloads carrying no `review_run_id`. Reported, never counted as a review of its own. |

Exclusion defaults: malformed records stay out of every finding denominator; unattributable rows are
reported in their own counter rather than being assigned to a bucket; and no row is excluded on a
source classification, because none is stored (see below).

There is no test or synthetic marker on a review payload record, so health views cannot exclude one.
Nothing durable distinguishes a record produced by a test harness from a record produced by a real
review, and classifying rows on a signal nobody stores would be a guess reported as a fact. If a
local database has been exercised by tests, its health numbers include that traffic; say so rather
than filtering on a field that does not exist.

Malformed records stay in `malformed_review_payload_records` and `data_quality_debt_records` and out
of every finding denominator, so an instrumentation problem shows up as debt instead of vanishing.

Review health combines two review payload sources:

- standalone `skillbill_review_finished` events
- embedded code-review entries inside historical `skillbill_feature_task_prose_finished.child_steps` rows (legacy prose lane; no new rows are produced)

Do not attempt to de-duplicate standalone and embedded review payloads unless a stable shared key is present. Local stats report `source_counts` for `standalone`, `embedded`, `malformed`, and `unknown`. Rejected findings mean reviewer feedback explicitly rejected or marked a finding false positive. Unresolved findings mean the latest finding outcome is missing or not accepted/rejected.

Feature-task health uses production rows with valid `fis-*` session ids as the denominator. It reports `source_counts`, excluded non-production rows, malformed session ids, unknown sources, duplicate terminal finished calls, invalid durations, synthetic zero-duration runs, long-running durations, and malformed `child_steps` as exclusion or data-quality signals. Duration averages, medians, and p90 values use only normal production durations.

Large-feature health is segmented by `feature_size`. `LARGE` runs report completion, abandonment, error, open-run, and duration summaries separately. The deterministic recommendation threshold is any non-zero unhealthy `LARGE` rate (`>= 0.001`) that is at least the overall unhealthy rate; in that case, dashboards should recommend decomposing large features or blocking earlier before implementation.

### PostHog query patterns

Use these named HogQL patterns as dashboard starting points. Keep them close to the local stats definitions rather than copying exploratory SQL from a one-off analysis.

`review_health_last_60_days`:

```sql
SELECT
  source,
  count() AS review_payload_records,
  avg(total_findings) AS average_findings,
  quantile(0.5)(total_findings) AS median_findings,
  quantile(0.9)(total_findings) AS p90_findings,
  sum(accepted_findings) AS accepted_findings,
  sum(rejected_findings) AS rejected_findings,
  sum(unresolved_findings) AS unresolved_findings
FROM (
  SELECT
    'standalone' AS source,
    toInt(properties.total_findings) AS total_findings,
    toInt(properties.accepted_findings) AS accepted_findings,
    toInt(properties.rejected_findings) AS rejected_findings,
    toInt(properties.unresolved_findings) AS unresolved_findings
  FROM events
  WHERE event = 'skillbill_review_finished'
    AND timestamp >= now() - INTERVAL 60 DAY
    AND properties.install_id IS NOT NULL
    AND trim(toString(properties.install_id)) != ''
    AND toString(properties.install_id) != 'test-install-id'
  UNION ALL
  SELECT
    'embedded' AS source,
    JSONExtractInt(child_raw, 'total_findings') AS total_findings,
    JSONExtractInt(child_raw, 'accepted_findings') AS accepted_findings,
    JSONExtractInt(child_raw, 'rejected_findings') AS rejected_findings,
    JSONExtractInt(child_raw, 'unresolved_findings') AS unresolved_findings
  FROM events
  ARRAY JOIN JSONExtractArrayRaw(toString(properties.child_steps)) AS child_raw
  WHERE event = 'skillbill_feature_task_prose_finished'
    AND timestamp >= now() - INTERVAL 60 DAY
    AND properties.install_id IS NOT NULL
    AND trim(toString(properties.install_id)) != ''
    AND toString(properties.install_id) != 'test-install-id'
    AND JSONExtractString(child_raw, 'skill') LIKE '%code-review%'
)
GROUP BY source
```

The `embedded` branch reads the retired `skillbill_feature_task_prose_finished` event and matches only historical rows; once those age out of the window the branch returns nothing and review health is standalone-only.

### Alignment rule

If a PostHog chart disagrees with local CLI/MCP stats for the same date range, treat the local stats command as the contract and fix the chart definition first.

## Remote stats contract

For org-wide analysis, Skill Bill can query aggregate workflow metrics through the configured telemetry proxy instead of reading one install's local SQLite database.

CLI:

```bash
skill-bill telemetry capabilities
skill-bill telemetry stats verify --since 30d
skill-bill telemetry stats implement --date-from 2026-04-01 --date-to 2026-04-22
skill-bill telemetry stats verify --since 30d --group-by day
skill-bill telemetry stats implement --since 30d --group-by week
```

MCP:

- `telemetry_proxy_capabilities`
- `telemetry_remote_stats`

Client capability contract:

```json
{
  "contract_version": "1",
  "supports_ingest": true,
  "supports_stats": true,
  "supported_workflows": [
    "bill-feature-verify",
    "feature-task-runtime"
  ]
}
```

Client request contract:

```json
{
  "workflow": "bill-feature-verify",
  "date_from": "2026-04-01",
  "date_to": "2026-04-22",
  "group_by": "day"
}
```

The MCP tool accepts the canonical workflow id `bill-feature-verify` or the
short alias `verify`. The dispatcher maps the alias to the canonical id before
calling the proxy. The bundled proxy answers `/stats` only for
`bill-feature-verify`.

The client sends that payload to:

- `<configured telemetry proxy url>/capabilities`
- `<configured telemetry proxy url>/stats`

The proxy owns backend-specific query logic. It may answer from PostHog, ClickHouse, BigQuery, or any other analytics store.
The bundled PostHog proxy queries apply the same production-install default as dashboards: they exclude null `install_id`, blank `install_id`, and `install_id = 'test-install-id'`.

Normalized remote stats payloads now include:

- `started_runs`
- `finished_runs`
- `in_progress_runs`
- `in_progress_rate`
- optional `group_by`
- optional `series`

For `feature-task-runtime`, normalized remote stats also include:

- `boundary_history_written_runs`
- `boundary_history_written_rate`
- `boundary_history_useful_runs`
- `boundary_history_useful_rate`
- `boundary_history_value_counts`

For `bill-feature-verify`, normalized remote stats also include:

- `history_read_runs`
- `history_read_rate`
- `history_relevant_runs`
- `history_relevant_rate`
- `history_helpful_runs`
- `history_helpful_rate`
- `history_relevance_counts`
- `history_helpfulness_counts`

For the proxy contract, `in_progress_runs` is a range-scoped estimate derived from event counts in the selected window: `max(started_runs - finished_runs, 0)`. It is useful for top-level workflow monitoring, but it is not a durable workflow-state query.
`boundary_history_useful_runs` is defined as `boundary_history_value in ('medium', 'high')`.
`history_relevant_runs` is defined as `history_relevance in ('medium', 'high')`.
`history_helpful_runs` is defined as `history_helpfulness in ('medium', 'high')`.

When `group_by` is provided, the proxy returns a `series` array with day or week buckets. Those buckets use the same event-window math as the top-level summary, so they are trend-oriented monitoring data, not cohort/session-complete analytics.

Recommended auth model:

- set `SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN` on the client machine
- have the proxy require `Authorization: Bearer <token>` for `/stats` and optionally `/capabilities`
- keep ingest and stats authorization concerns separate if you want public ingest but private aggregate reads

## Remote sync defaults

Fresh installs default telemetry to `anonymous`, with a level prompt during `./install.sh`. When telemetry level is not `off`, Skill Bill generates an install id, writes telemetry config to `~/.config/skill-bill/config.json`, and can batch-sync queued telemetry to the hosted Skill Bill relay. If you configure a custom proxy, Skill Bill sends telemetry to that proxy only.

- enabled telemetry (`anonymous` or `full`) can enqueue local telemetry events in SQLite before sync
- the helper can batch-sync pending events automatically after local writes to the hosted relay, or to a configured custom proxy override
- automatic sync and manual `skill-bill telemetry sync` both reconcile stale lifecycle rows before flushing the outbox, so CLI-only sessions receive the same terminal-event repair as MCP sessions
- reconciliation uses a durable cadence independent of upload timing and one globally ordered maximum batch per SQLite transaction; eligible repeated runs drain larger backlogs without holding an unbounded write transaction
- reconciliation is best-effort: a reconciliation failure is recorded diagnostically and does not prevent the requested telemetry flush
- if the remote destination is missing or unavailable, local workflows still succeed and the enabled telemetry outbox stays pending
- `off` telemetry is a no-op: no telemetry config is required, no telemetry events are queued locally, and telemetry payload-building is skipped
- `skill-bill telemetry disable` sets the level to `off` and clears any queued telemetry events without deleting non-telemetry review data; it is an in-place level write, so `install_id` and every other key in `config.json` are retained and re-enabling reuses the same install identity

Default hosted relay:

- `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
- used automatically when no custom proxy is configured

Custom proxy setup for your own deployment:

- deploy the example Worker in `docs/cloudflare-telemetry-proxy/`
- set it with `SKILL_BILL_TELEMETRY_PROXY_URL`
- keep the backend credential only in the Worker secret store
- when set, the custom proxy becomes the only remote telemetry destination

Telemetry commands:

```bash
skill-bill telemetry status
skill-bill telemetry enable                  # defaults to anonymous
skill-bill telemetry enable --level full     # enable with full detail
skill-bill telemetry set-level full          # change level directly
skill-bill telemetry set-level anonymous
skill-bill telemetry disable                 # sets level to off
skill-bill telemetry sync
```

Proxy configuration:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
export SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN="replace-with-your-stats-token"
export SKILL_BILL_TELEMETRY_LEVEL="full"                   # optional override (off, anonymous, full)
export SKILL_BILL_TELEMETRY_ENABLED="true"                 # legacy override (maps true→anonymous, false→off)
export SKILL_BILL_TELEMETRY_BATCH_SIZE="50"                # optional override
export SKILL_BILL_CONFIG_PATH="/custom/path/config.json"  # optional: pin config to a custom path (overrides the durable default)
```

When telemetry is enabled, the local config stores the generated install id used as the anonymous event `distinct_id`. The config lives at the durable `~/.config/skill-bill/config.json` by default, outside the `~/.skill-bill/` tree that installs wipe; you can edit it directly if you want to keep the hosted relay or replace it with your own proxy target, but the supported way to opt out is `skill-bill telemetry disable`.
