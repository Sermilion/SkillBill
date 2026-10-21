# SKILL-436 - implement-one-acceptance-criterion-at-a-time

## Mode

single_spec, represented by the normal governed one-subtask bundle.

## Intended outcome

Implement completes one acceptance criterion at a time and checks it before moving on. This prevents a worker's whole-spec completion claim from hiding unfinished criteria until audit. The change belongs inside the existing implement phase and reuses its strategy, prompt, continuation, settlement, and workflow-artifact owners.

## Scope

One coherent subtask delivers ordered fresh worker/check sessions, bounded retries, durable recovery, and legacy compatibility together. The feature has five production outcomes and does not need separate layer or test subtasks. Its executable spec is spec_subtask_1_criterion-implementation-loop.md. Every parent criterion maps to the same numbered criterion in that spec.

## Acceptance Criteria

1. Implement visits the selected spec's acceptance criteria in authored order. Each worker is a fresh session assigned only the current criterion, with the full spec as reference and access to the current working tree, without earlier session transcripts or accumulated continuation prose.
2. After each worker, implement launches a fresh read-only session that checks only the assigned criterion against source. A satisfied decision closes that criterion and advances to the next; an unsatisfied decision supplies short gap prose to a fresh worker on the same criterion. Invalid decisions or source edits by a check cannot close a criterion.
3. Implement permits at most three workers per criterion before blocking with needs_user_action that names the criterion and latest gap. An ordinary restart retains the exhausted budget; explicit operator resume grants the current criterion three new workers while retaining all closed criteria. Checks and process/output retry accounting cannot bypass this worker limit.
4. One versioned workflow-artifact progress record durably holds ordered criterion IDs, current index, worker count for the current criterion, and closed IDs. Reservation precedes worker launch, closure and index advancement are atomic, and crash recovery preserves the count and reconciles settled work before another worker. Missing progress starts at the first criterion; corrupt progress, inconsistent identity/order, and failed persistence fail loudly without resetting closure or launching unauthorized work.
5. A selected spec without parseable acceptance criteria retains the existing implement launch and continuation behavior without weakening run-invariant validation. Once all criteria close, implement emits its normal final implementation receipt and continues the unchanged outer phase sequence, including simplify, through the existing settlement tools.

## Non-goals

Do not restore the discarded SKILL-429 coordinator role, authenticated session grants, implementation MCP endpoint, provider launch command changes, permission changes, or per-criterion carryover accounting schema. Do not change settlement tools or their schemas, audit, audit repair, their budgets, or the outer phase list. Do not introduce SQL tables, another storage authority, a new module, dependency, platform pack, provider, or account/environment capability. Migration is limited to initializing absent progress at criterion one; retain existing workflow history and working-tree edits.

These boundaries preserve the feature's behavior and architecture. They do not prohibit production wiring, test setup, formatting, or lint repairs required by later review and validation.

## Dependency notes

Subtask 1 has no predecessor. Its production wiring, progress contract, and regression obligations form one reviewable change. The selected-spec parsing, accepted-launch policy, and crash reconciliation decisions below are settled design constraints for plan_subtasks to turn into a bounded implementation plan. No earlier contract landing or external service is required.

## Decisions and assumptions

The preplan digest is the sole repository evidence for this bundle. The failed tracker lookup does not block planning because the supplied intake contains the complete requirements. The seed is runtime-authored intake, so this bundle replaces its duplicated intake criterion with five focused outcomes. No operator-authored parent is being replaced.

Use the existing GovernedSpecSectionParser through PlanBundleSpecText.acceptanceCriteriaOf to determine whether the selected spec has a parseable acceptance section. A zero-result parse selects the legacy implement path. Keep FeatureTaskRuntimeRunInvariants validation nonempty and unchanged. Where invariants describe the selected spec, use their criteria for assignment and keep the selected subtask/spec reference aligned. A mismatch must fail with evidence rather than silently assigning another spec's criteria.

Reuse acceptanceCriterionIdentity and canonicalAcceptanceCriterionRef for identities. Reject duplicate identities and any ordered-criterion drift against existing progress. Closed IDs are exactly the ordered prefix before the current index. Define the cursor as zero-based, initial index/count as zero, and terminal index as criterion count with all IDs closed and active worker count zero. During an active criterion, the count records reserved workers, bounded from zero to three. Advance closure and reset the next criterion's count in one owned transaction.

Reserve each worker slot durably before launch. Retain a reservation across a crash even if launch delivery is uncertain. Use existing per-launch attempt/settlement identities to reconcile whether that worker settled and whether a check is owed. Replay a pending read-only check when needed rather than launching another worker. If evidence cannot distinguish an unfinished reserved worker from a settled one, retain the reservation and fail loudly with recovery evidence rather than reset the count, skip a check, or exceed the cap. Implement must confirm the existing evidence can support these distinctions without adding fields to the four-field progress payload or changing attempt-history and settlement schemas. The latest retry gap belongs in existing attempt/settlement evidence, not in a new carryover record.

Only explicit operator reopening resets the active criterion count. Observe that boundary before launch preparation clears reopening state. Crash reconstruction and generic persisted-block relaunch rules must not trigger that reset. A criterion-cap block must remain needs_user_action on ordinary restart.

Use a small typed implementation-turn context beside implementation, separate from currentAcceptanceCriterion's audit-repair meaning. Keep worker/check launches inside the existing accepted attempt loop. The read-only check uses a narrowly owned internal launch mode and existing read-only/file-manifest safeguards, with unchanged provider permissions and unchanged generic admission checks. Do not copy a run with an arbitrary read-only policy or replace a PhaseStepCall outside its owning strategy contract. Source mutation by a check is a failed check, never evidence of satisfaction.

Consume worker/check results at the existing settleCompletedRound boundary before outer phase completion persists. Use the existing verdict/value surface for satisfied or unsatisfied and short gap prose, with an explicit recognized decision required for closure. Workers and checks settle through feature_task_phase_complete and feature_task_phase_block. Preserve the final per-spec no-change contract without demanding a whole-spec no-change claim from each single-criterion worker. The final implement receipt summarizes implementation work, not the check verdict.

The digest names the active feature branch but does not name the repository default branch. The manifest assumes main as base_branch; implement must confirm that assumption through its authorized discovery before relying on branch operations. No branch operation belongs to this plan phase. The digest reports unrelated staged SKILL-429 deletions and SKILL-434 additions; preserve them without claiming ownership.

## Test Obligations

- AC 1, 2, and 5. Catch premature whole-spec completion and transcript carryover with one scripted runner scenario containing three criteria, where criterion two needs two workers. Assert W1,C1,W2,C2,W2,C2,W3,C3 order, current-criterion assignment, full-spec reference, absence of prior transcript sentinels, current-tree visibility, gap delivery only to the retry, the ordered closed set, and one outer implement completion followed by normal simplify. Assert that malformed or blank check decisions and a source-mutating check cannot produce closure, using focused rejection branches where the main scenario cannot prove them.
- AC 3. Catch a fourth worker or an accidental budget reset with three unsatisfied checks after an earlier criterion has closed. Assert needs_user_action names the active criterion and latest gap, ordinary restart stays blocked, and explicit operator resume grants three new workers while retaining the closed prefix. Assert checks do not spend worker slots and generic continuations do not bypass the cap.
- AC 4. Catch lost reservations, skipped checks, duplicate closure, and replay of closed criteria by reconstructing the runner with retained artifacts across reservation, worker settlement, check settlement, and closure boundaries. Assert the reserved worker count survives and pending checks can replay without spending another worker. Include a rejected reservation write and assert that no subsequent worker launches.
- AC 4. Catch acceptance of corrupt durable state with governed schema/version parity and focused acceptance/rejection coverage for the new progress contract. Cover unsupported versions, unknown properties, duplicate or changed ordered IDs, a closed set that is not the ordered prefix, invalid index/count combinations, and the distinction between absent and corrupt progress. Assert typed failures at read/write boundaries. Retain all existing governed parity and real regression coverage.
- AC 5. Catch accidental activation of the new loop on legacy input with a spec lacking a parseable acceptance section through the real entry path. Keep valid nonempty run invariants and assert today's launch/continuation behavior. Do not manufacture an empty-invariants fixture that the runtime cannot admit.

Use the existing runner harness, scripted launcher, actual settlement service, and retained-artifact repository access. The proposed runner suite is FeatureTaskRuntimeImplementationCriteriaTest in the existing runner package. Prefer these observable boundary scenarios over per-helper tests, mock call verification, or duplicate literal variations. The rejection branches above each protect a distinct failure of the progress or closure contract.

## Validation strategy

Planning authors this bundle only. Implement and audit establish or inspect repository end states without executing builds, tests, or generators. Only the build phase may execute the configured pack build command for buildability proof. Only validate discovers and executes the complete repository gate from project instructions, build configuration, scripts, and CI, including applicable architecture and schema checks and the required test-value gate. Compilation alone is insufficient. No test execution is claimed by this plan.

Validate must exercise the Test Obligations below using the repository's configured commands. Preserve the existing implementation-continuation regressions for legacy accumulation, reconstruction parity, loop identity, prompt carryover, and simplify. Adjust affected runner scripts deliberately because their default two-criterion fixtures may currently expect implement to finish after one launch. Do not disable the new path globally or weaken unrelated assertions, architecture guards, baselines, or governed parity coverage.

Later review applies architecture-guidelines A1-A12 and the section 5 checklist, citing rule IDs. Emphasize A2 ownership, A3/A5 avoiding a coordinator or collaborator bag, A6 shared keys and identities, A7 typed observable failures and cancellation, A10 test placement, and A11 narrow visibility. G1-G7 continue to apply. Preserve dependency direction, transactional ownership, and contract enforcement. Kotlin remains free of line and non-KDoc block comments; KDoc stays on interfaces and their members. Preserve simple imported type names and package/file-size limits. This plan relies on the digest's architecture evidence; implement must read the governing architecture documents before touching runtime-kotlin.

## Next path

The runtime's next step is plan_subtasks for subtask 1. It owns the implementation plan; this bundle contains no Implementation Details section. Implementation, audit, validation, history, commit, PR, and monitoring remain with their assigned phases. No workflow command is to be invoked during this phase. The prepared goal entry point for later authorized use is `skill-bill goal SKILL-436`.
