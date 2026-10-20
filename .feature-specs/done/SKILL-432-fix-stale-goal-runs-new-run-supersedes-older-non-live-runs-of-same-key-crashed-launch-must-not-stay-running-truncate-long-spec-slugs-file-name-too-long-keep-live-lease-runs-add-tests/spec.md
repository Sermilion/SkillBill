# SKILL-432 - repair stale goal invocations and bound generated spec paths

## Mode

single_spec, represented by one executable subtask in the governed manifest.

## Intended Outcome

A new goal invocation repairs older non-live runs of the same key without disturbing live owners. Catchable launch failures close their invocation, and long generated spec names fit filesystem component limits.

## Scope

The runtime-engine goal runner and telemetry emitter, SQLite goal lifecycle persistence and emission, and feature-spec preparation writer own this change. Preserve all supplied intake requirements, including regression tests under Test Obligations.

## Acceptance Criteria

1. Fresh goal-start persistence supersedes older unfinished non-live invocation segments of the same issue key atomically, while preserving live or uncertain other-parent ownership, unrelated keys, first-terminal evidence, and correct logical-goal accounting.
2. Catchable failures after a persisted goal start close that invocation as a supported terminal failure without replacing the original throwable, claiming logical-goal completion, or changing ordinary completion and stop semantics.
3. Newly generated parent directory and subtask filename components use one deterministic bounded naming policy, with consistent planned paths, written paths, manifest references and links, while preserving short names, full display titles and explicit existing parent paths.

## Decisions and assumptions

The persisted invocation status for supersession is `abandoned`, with owner-declared stop reason `superseded_by_new_run`. Catchable post-start failures use `blocked` with owner-declared reason `goal_run_failed`. Failure reasons are bounded to 512 UTF-8 bytes and exclude raw exception messages from exported payloads. Preserve the original throwable and attach cleanup failures as suppressed evidence. Cancellation and interruption retain their original propagation and interruption semantics. Hard-crash remnants are repaired on the next genuinely inserted start.

Only an inserted start authorizes reconciliation. A duplicate start neither supersedes rows nor increments aggregates. Compare old and new start times as instants, using strictly older segments; timestamp equality alone does not prove ordering. Exclusive same-parent admission authorizes superseding that parent's older unfinished segments even though the newly acquired lease is live. Other-parent segments with a valid unexpired lease remain untouched. Expired leases require positive non-live evidence from the existing execution-ownership recovery boundary; expiry alone is insufficient. Missing lease evidence and legacy parentless rows are eligible only when there is no conflicting or uncertain ownership evidence. Corrupt ownership data must loud-fail through the established typed decoder, preserving the transaction rather than pretending the owner is absent. Unavailable or indeterminate process evidence preserves the affected other-parent rows and records the deliberate degradation through existing observability policy.

The digest does not identify the exact carrier for supervisor-confirmed liveness. Implement must confirm how the existing ownership boundary can expose that evidence and use the smallest change to that boundary if needed. The assumption is that positive dead-owner evidence can be reused; if it cannot, retain uncertain rows and record the evidence gap rather than weakening the death rule. No SQLite process probing is authorized.

A superseded row receives abandoned status, a finish timestamp from the inserted start, a nonnegative elapsed duration, and complete nonnegative count fields using the established persisted progress sources. Unknown counts use zero rather than invented progress. Keep terminal rows stats-readable, preserve first-terminal writes and emit exactly one existing redacted GOAL_FINISHED event per transitioned row through GoalTelemetryEmit.

For same-parent supersession, retain the aggregate for the new running invocation. Historical cleanup must neither overwrite its latest-segment state nor increase total_blocks. For a different parent, abandon a logical aggregate only when its latest segment is among the superseded rows, it has no remaining protected unfinished segment, ownership is confirmed non-live, and the aggregate is unfinished. Preserve completed, failed, abandoned and unrelated aggregates. A blocked aggregate without an eligible superseded latest segment is unchanged. Emit a logical terminal event once through its existing owner without treating abandonment as a measured execution block. Late historical finishes cannot change the newer logical invocation or emit duplicate terminals. Implement must confirm the existing logical-terminal event and persistence owner; extend that owner only as required, preserving the external event shape.

The component limit is 240 UTF-8 bytes including prefixes and suffixes. Normalize slugs by the existing ASCII normalization and `feature` fallback. Preserve a component exactly when it fits. Otherwise reserve 17 characters for `-` plus the first 16 lowercase hexadecimal characters of SHA-256 of the complete normalized slug, and truncate the slug portion to the remaining budget. Trim trailing hyphens from the retained prefix. Parent components reserve the exact trimmed issue key plus `-`; subtask components reserve `spec_subtask_<id>_` and `.md`. Count a non-ASCII issue-key prefix in UTF-8 bytes. If fixed identity material leaves insufficient room for a nonempty slug and the hash suffix, reject through the existing typed preparation-request failure rather than truncating issue identity. Use one path naming owner across planning and writing; display titles retain their full text. Explicit existing parent paths remain authoritative even when their components exceed the new generated limit.

The digest does not supply the repository default branch. This bundle uses `main` as an explicit planning assumption. The next runtime planning boundary must confirm it from its authorized context before branch-dependent execution. The manifest feature branch follows the supplied format and retains this workflow's existing long slug; this task bounds filesystem components, not branch names. No branch is created by this phase.

## Constraints

Use the supplied preplan digest as the repository evidence for this bundle. No tracker lookup is required. The tracker could not resolve SKILL-432; the supplied requirements and local issue identity remain authoritative.

Keep state ownership in the existing engine, lifecycle application and SQLite boundaries. SQLite changes share the existing lifecycle transaction and connection. Engine code must not access the database. Reuse supported abandoned and blocked statuses rather than introducing a finished status that stats cannot decode. Keep wire tokens and payload keys in their owning vocabulary objects.

Apply architecture rules A1-A12 and the section 5 checklist, retain G1-G7 guards, baselines and exemptions, and follow the Design Principles and Code Principles described in the digest. In particular, preserve ownership boundaries, typed malformed-state failures, injected clocks, narrow visibility and tests beside owners. Authored Kotlin must obey the repository comment restrictions. Planned owner paths guide the change; required production wiring, test setup, formatting and lint repairs remain allowed.

## Non-Goals

Do not change platform packs, dispatcher behavior, CLI grammar, feature-task settlement, preparation classification, or unrelated lifecycle families. Do not rename existing bundles, change completed workflow or commit state, delete telemetry history, add a feature flag, or promise immediate repair after an uncatchable hard kill. Periodic goal-session reconciliation and a new process-inspection mechanism in SQLite are outside this change.

## Dependency Notes

There is one executable subtask and no external dependency. Its engine, SQLite and writer changes form one reviewable correctness fix. There are no preparatory commits that leave replacement behavior incomplete. Review, history, commits and publication remain with the runtime-owned phases.

## Validation Strategy

Implement produces production changes and test sources; audit checks the repository end states against each criterion. Neither phase runs tests, builds or generators. Only validate discovers and executes the required focused tests and complete repository checks, including the pack full gate, and repairs failures. Only a runtime build phase may execute the pack build command. This planning phase executes none of those commands and records no test execution.

Validate should exercise GoalRunnerTelemetryTest, GoalTelemetryStoreTest or its owning-package equivalent, and FeatureSpecPreparationWriterTest, then the required repository validation strategy. Keep the existing architecture and contract parity checks. Validation owns command selection and execution evidence; a passing command is not an acceptance criterion.

## Test Obligations

- AC 1, stale rows and replay. Extend the real SQLite boundary coverage with a compact scenario containing several old same-key segments, same-parent restart under its newly live lease, protected live other-parent ownership, expired-but-uncertain ownership, confirmed-dead ownership, missing leases, legacy parentless rows, unrelated keys and prefinished rows. The realistic bug is either leaving stale invocations running or terminalizing another live invocation. Assert durable fields, readable stats, correct latest logical state, no fabricated block increments, and exactly-once redacted finish events. Replay the start and a late terminal to prove idempotence. Include equivalent offset timestamps and the exact expiry boundary so string comparison or expiry-as-death errors fail observably. Retain existing regression assertions.
- AC 1, atomic failure. Through the transaction-owning lifecycle boundary, inject a failure during supersession/start or outbox persistence. The realistic bug is committing only some terminal rows or counters after the new start fails. Assert rollback of the inserted start, affected old rows, logical progress and outbox together. Add a malformed-lease rejection case at this boundary, so decoder failure cannot silently authorize supersession.
- AC 1, aggregate closure. Cover a dead different-parent latest segment and a blocked aggregate with no superseded latest segment. The realistic bug is either leaving a superseded logical run running or abandoning an unrelated blocked goal. Assert logical terminal emission once and preservation of protected or completed aggregates. Combine this with the SQLite scenario when it retains clear outcome assertions.
- AC 2, post-start failures. Extend GoalRunnerTelemetryTest with planning and launch failures after persisted start. Assert one terminal for the started segment, original throwable identity, no issue completion or fabricated subtask success, and cleanup failure suppression. Cover cancellation and interruption propagation, including the interrupt flag where applicable. The realistic bugs are stranded running telemetry and masked cancellation or original errors. Preserve existing normal completion and stopped-report coverage instead of duplicating it. Update the subtask-finish telemetry failure expectation only to permit best-effort segment closure while retaining its loud original failure assertion.
- AC 3, generated filesystem writes. Extend FeatureSpecPreparationWriterTest with oversized parent and subtask names, names sharing a long prefix, and short names. Assert actual writes, the 240-byte component limit, deterministic distinct truncated names, planned/written equality and reloadable manifest paths and links. Verify full titles, explicit existing parent preservation and typed rejection of an issue prefix that leaves no safe slug budget. The realistic bugs are filename-too-long failures, overwritten bundles and dangling manifest references. Retain atomic rollback, byte-for-byte restoration and preserved runtime-status coverage; reuse existing tests where their assertions already prove those behaviors.

Do not add tests for trivial forwarding or duplicate branches already covered. Each new case must retain its named observable failure and avoid SQL-text, call-order or implementation-structure assertions.

## Next Path

The runtime proceeds to plan_subtasks using `spec_subtask_1_repair_goal_runs_and_bound_spec_paths.md` and `decomposition-manifest.yaml`. The parent goal reference is `skill-bill goal SKILL-432`; this phase does not launch it.
