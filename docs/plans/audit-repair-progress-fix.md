# Audit repair progress fix

## Target and outcome

Implement on `base/SKILL-380-phase-slot-strategies` in
`/home/sermilion/StudioProjects/skill-bill-base-380`.
Preserve the existing work in that worktree. Keep this fix separate from
SKILL-384's capability refactor and its acceptance criteria.

An audit may launch another repair only when its count of unresolved production
criteria decreases after the preceding repair. The first valid nonempty audit
establishes the baseline. An explicit `[]` completes the audit. An equal or larger
count blocks with the previous and current criterion IDs. An unreadable count
blocks instead of becoming apparent progress.

The rollout record below tracks implementation, validation, installation, and
resume of the existing SKILL-384 workflow.

## Evidence and remaining defects

The stored audits for `wftr-20260929-182426-w78b` reported these open criteria:

| Audit | Open criteria | Count |
| --- | --- | --- |
| 1 | AC-002, AC-003, AC-004, AC-008 | 4 |
| 2 | S3-AC2, S3-AC3, S3-AC4 | 3 |
| 3 | S3-AC2, S3-AC3, S3-AC4, S3-AC8 | 4 |
| 4 | S3-AC2, S3-AC3, S3-AC4, S3-AC5, S3-AC6, S3-AC8 | 6 |
| 5 | S3-AC2, S3-AC3, S3-AC4 | 3 |

`AcceptanceAuditProgress.stalled` currently allows equal counts when the sets
contain different IDs. Its parser recognizes `AC-002 / S3-AC2` but misses
standalone `S3-AC2`. It converts an unrecognized report to one text item, so audit
5 becomes a count of one. It also searches all prose, which can count references
to satisfied criteria as open findings.

`AuditImplementFixPromptSections.endsWithCompletionMarker` currently uses suffix
matching. A final line such as `Deliberately not claiming audit_repair_complete: true`
still passes. The completion marker must occupy its own final line.

The guard scope clarification already exists in both prompts. Retain it and its
tests. A required architecture guard is in scope even under a test source set;
its example and regression cases remain the responsibility of the test phases.

## Implementation

### 1. Establish criterion identity from the accepted plan

Build one immutable criterion catalog from the run's persisted acceptance
criteria. Share identity derivation with `appendAcceptanceCriteria` and
`canonicalAcceptanceCriterionRef`, so rendering and audit settlement agree.

Preserve explicit canonical IDs. For criteria without one, use the existing
ordinal-derived canonical ID. Register an original leading label such as
`S3-AC2` as an alias for that catalog entry. Resolve aliases through this catalog,
not by assuming that equal numeric suffixes identify the same criterion.

Normalize case and supported zero padding. Deduplicate references to the same
criterion, including combined labels such as `AC-002 / S3-AC2`. Reject duplicate
catalog identities, conflicting aliases, and references outside the accepted
plan with an attributed failure.

Keep the existing phase envelope and prose value. Use a closed internal parse
result to distinguish a known remaining set, an empty list, and unusable evidence.
Remove the fallback that counts the entire response as one criterion.

The parser must identify the criterion attached to each remaining finding, rather
than harvest every ID mentioned in its explanation. Support the stored JSON
finding lists and identified prose or bullet entries needed by existing runs.
Unidentified findings, ambiguous labels, contradictory resolved/open entries, and
unsupported report shapes cannot supply a count. Reject them through the existing
bounded output correction or blocked-output path. Record the cause and preserve
the report as diagnostic evidence.

### 2. Enforce decreasing counts at settlement

Update `AcceptanceAuditProgress` and `AcceptanceAuditRound` so the sole retry
condition after repair is `currentCount < previousCount`. Smaller sets may contain
newly discovered criteria, provided every ID belongs to the accepted plan.

Block equal counts even when every ID changed. Block increased counts. Keep
explicit empty-list completion and process-failure precedence. Group several
production gaps under one criterion into one count; closing only some of those
gaps does not authorize another automatic repair under this policy.

Distinguish a genuine first audit from a missing comparison after repair. Read
the preceding accepted audit before persisting the current result. Retain that
comparison across backward-edge traversal and durable resume using the existing
phase records and ledger. If a repair has run but its preceding accepted audit
cannot be recovered, block with a missing-baseline reason. Do not restart the
baseline or reset retry accounting.

The blocked record must include bounded, sorted canonical IDs and counts before
and after repair, the phase and attempt, and the specific rejection reason.
Use the existing settlement and diagnostic owners. Preserve checkpoint ownership,
lease fencing, cancellation, and required-write failures.

### 3. Require an exact repair completion line

After trimming trailing whitespace and optional standalone closing Markdown
fences, require the final nonempty content line to equal
`audit_repair_complete: true`. Do not strip arbitrary punctuation or accept a
suffix within an explanatory sentence.

Missing or refused markers save the partial report and block through the existing
incomplete-work path when the step requires a single agent session. An explicit
operator resume renders the saved reports in the next repair prompt. The marker authorizes a fresh audit, which still
decides whether the production requirements are met.

Align the prompts and operator documentation with these rules. Ask audit to emit
only open findings with catalog IDs, and keep satisfied summaries outside the
remaining value. Document the exact completion line and the decreasing-count
rule. Do not weaken S3-AC2 through S3-AC4 to make the run pass.

## Regression tests

Extend the existing audit retry and prompt suites. Each test must assert launch
order, final workflow outcome, or persisted blocked evidence.

| Bug the test catches | Scenario and expected boundary |
| --- | --- |
| Equal count escapes the stop rule | One open ID becomes a different open ID. Block before a second repair or review. |
| Source labels fabricate progress | Three `S3-AC` findings match their three canonical aliases despite wording or format changes. Block when the count stays three. |
| Alias formatting inflates counts | Canonical, original, and combined labels deduplicate to the same catalog entry. A real decrease advances; format changes alone block. |
| Explanations corrupt the count | Findings mention other criteria as satisfied or as dependencies. Only finding identities count; contradictory or unidentifiable entries are rejected. |
| Malformed evidence restarts the loop | Unknown IDs, conflicting aliases, and nonempty unidentified prose never become a count of one or an empty list. |
| Restart loses the baseline | Persist an audit, enter repair, recreate the durable runtime, then return an equal or larger audit. Block with the retained before/after IDs and preserve attempts and checkpoints. |
| Missing history bypasses comparison | A resumed repair lacks a recoverable accepted audit. Block with a missing-baseline reason. |
| A refusal satisfies the marker | Put a refusal ending in the marker on the final line. Continue repair. An exact standalone final marker permits the next audit. |

Retain the existing growth, genuine decrease, empty-list completion, guard scope,
process-failure, required-persistence, and composition coverage. Add a sanitized
fixture based on audit 5's standalone labels and its trailing satisfied summary.
Do not use the live workflow database as a test fixture.

## Validation and rollout

1. Run the focused audit retry, prompt, composition, and durable-resume tests in
   the base worktree. Run `skill-bill operation unit-test-value-check` over the fix.
2. Run `skill-bill phase validation` from the base worktree and repair required
   project failures. Compilation alone does not finish validation. Review the
   scoped diff for architecture, wire vocabulary, and contract boundaries.
3. If implementation adds a durable field or changes a governed payload shape,
   update its canonical schema, version, typed parse errors, compatibility policy,
   and parity coverage together. Prefer reuse of existing evidence over new
   storage. Never normalize incompatible records silently.
4. Commit the fix on `base/SKILL-380-phase-slot-strategies`. Preserve unrelated
   edits and record the exact tested source revision.
5. Before replacing the installed runtime, stop the active goal through its
   supported control path and confirm the worker exits. Install from the tested
   base worktree with `./install.sh`, then verify installation health and packaged
   behavior. Read source-generation guidance before any installer or skill edits.
6. Inspect the existing SKILL-384 child and resume it with the supported runtime
   command. Preserve subtasks 1 and 2, the child workflow, accepted outputs,
   attempts, and checkpoints. Do not delete rows or perform a hard reset.
7. Verify that a nonshrinking audit blocks with the actual previous/current IDs,
   or that an explicit empty list advances to review. Report liveness separately
   from acceptance-criterion completion.

## Acceptance criteria

- The actual three standalone S3 labels count as three criteria.
- Rewording, alias spelling, ordering, and duplicate references cannot fabricate
  progress. Equal and larger open counts block after repair.
- Missing or ambiguous counting evidence cannot launch another automatic repair.
- Durable resume applies the same comparison as uninterrupted execution.
- A marker inside a refusal cannot complete repair, including on its final line.
- Existing guard scope, terminal failure handling, storage ownership, and
  checkpoint recovery remain covered by passing tests.
- The fix is implemented, validated, and installed from the base branch. The
  existing SKILL-384 workflow resumes without resetting completed subtasks.

## Rollout record

Implemented on `base/SKILL-380-phase-slot-strategies`, starting at
`a7e1d1fadc1ddd0f1ce4de3d74ba21f0b9459ea1`. The worktree already contained pending
runtime changes. The fix remains uncommitted alongside that work.

Validation completed on 2026-09-29:

- Full `./runtime-kotlin/gradlew -p runtime-kotlin check` passed, including runtime
  tests, architecture guards, formatting, static analysis, and prompt snapshots.
- `skill-bill validate` and `scripts/validate_agent_configs` passed.
- `npx --yes agnix --strict .` reported zero errors and 28 warnings. Repository CI
  accepts the zero-error result even when strict mode returns a warning exit code.
- The scoped `unit-test-value-check` returned Strong and requested no deletions
  or rewrites.
- `git diff --check` passed.

Stopped SKILL-384 through `skill-bill goal stop` and confirmed idle liveness.
Reinstalled from this worktree with `./install.sh --from-source --reuse-last-selection`.
Doctor passed. The installed runtime jars match the tested build:

- Engine SHA-256 `3cf7374280d5c463f9f3ce427c7229abad960231eccb42710e2eac43d1391f4e`.
- Domain SHA-256 `c21e854b2ae4b3408143b89d4a29738fdcb7247522496854f1eb9c59e45ed356`.

Cleared the pause and launched the foreground goal driver with Cursor in the
feature worktree. Status at 19:48 UTC reports live execution in subtask 3's
`audit_implement_fix`, with two subtasks complete and no pause. No reset or
workflow deletion occurred. Driver output is in
`/tmp/skill-bill-SKILL-384-resumed.log`.

The existing status diagnostics still report missing boolean validation metadata
on completed subtasks 1 and 2. This fix preserves those records and does not alter
their acceptance.

## Follow-up session and resume correction

The first resumed run reached audit 6 and correctly blocked when the open count grew from three to five. It also exposed a separate defect: eight fresh repair continuations ran without receiving saved partial reports. The follow-up fix now blocks unfinished single-session repairs, delivers saved reports on explicit resume, and persists the latest normalized blocked audit report. An operator-authorized audit retry permits one new validated baseline. Later automatic rounds still require decreasing counts.

Follow-up validation on 2026-09-29 passed all 58 focused tests and the full runtime check. Test reports contain 5,564 tests, zero failures, zero errors, and four skips. Source validation and agent-config validation passed. Agnix reported zero errors and 28 warnings. The test-value operation for the new session-resume regressions returned Strong after strengthening the unknown-criterion test to prove that resumed audit launched and rejected the invented criterion. Git whitespace checks passed.

Reinstalled the follow-up fix from base with `./install.sh --from-source --reuse-last-selection`. Doctor passed, and the installed engine jar matches SHA-256 `409a39a2613d81957840e61e1232a7439db10b346687dc960c7e036281645ea0`. The domain jar still matches `c21e854b2ae4b3408143b89d4a29738fdcb7247522496854f1eb9c59e45ed356`.

Resumed the existing goal with Cursor. At 20:54 UTC the child `wftr-20260929-182426-w78b` is running audit attempt 7, started at 20:53:43 UTC. Its operator retry artifact records authorization to reopen the blocked audit. Status reports live execution, two completed subtasks, one active subtask, and no pause. No reset or workflow deletion occurred. The earlier validation-integrity diagnostics for subtasks 1 and 2 remain unchanged. The driver log is `/tmp/skill-bill-SKILL-384-session-fixed-resumed.log`.

The runtime fix remains uncommitted in the base worktree alongside its existing pending changes. Feature acceptance closure is still the resumed goal's work.
