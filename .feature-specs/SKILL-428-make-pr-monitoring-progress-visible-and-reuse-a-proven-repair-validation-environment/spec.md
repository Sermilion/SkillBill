# SKILL-428 Make PR monitoring progress visible and reuse a proven repair validation environment

## Mode

decomposed

## Intended outcome

Make monitor progress understandable in ordinary prose, reuse a working validation environment, and count failed CI repairs truthfully. A merge conflict is resolved without consuming a CI repair attempt.

The operator clarified that this work must stay prose centric. It introduces no schemas, schema validation, structured progress protocol, required completion receipts, or new block reasons. These instructions supersede the earlier draft that proposed those mechanisms.

## Evidence and scope

The observed session on 2026-10-15 exposed long silent repair periods, repeated container setup mistakes, a busy Mac runner, and a misleading three-attempt stop. These observations do not prove slow polling or that every full validation run was redundant.

On PR 477, the first repair changed source/tests and smoke behavior, the second merged the then-current main, and the third changed validation to avoid a shared Gradle cache lock. The third repair was pushed at 20:13 UTC. PR 473 merged at 20:14 UTC, advancing main and creating fresh conflicts in PR 477 before the third repair's CI verdict. The monitor stopped because its repair-loop counter had reached three. Its current monitor cycle routes both a failed check and a merge conflict through CI_FAILED, and its loop budget counts repair entries rather than completed changes subsequently rejected by CI.

Improve the shared monitor instructions, existing activity output, environment guidance, and the existing attempt-accounting owner. Keep agents reasoning and reporting in prose. Reuse current run, commit, check, progress and lifecycle facilities. Do not build another validation runner or progress transport.

Planning and this correction modify this bundle only. No implementation, builds, tests, installers, commits, pushes, or goal launch are authorized by the spec request. The earlier PR monitoring work has separate authorization.

## Acceptance Criteria

1. P1. Monitor guidance and caller output explain the current PR, available head/check links, current activity and known repair budget in concise prose. They distinguish source repair, merge resolution, environment setup, a local check, publication, queued CI and running CI. Unknown detail is described as unknown. Existing activity channels carry these updates without a machine-readable prose format.
2. P2. Meaningful activity changes reach callers before terminal completion. During long quiet commands or CI waits, existing progress facilities provide a short liveness update with elapsed time when available. Heartbeats do not claim source progress, reset deadlines or spend attempts. A 30-second update cadence is the intended default where the existing facilities support it. If they cannot provide that cadence, the implementation explains the limitation rather than adding a new transport.
3. P3. Repair guidance uses repository instructions, existing setup scripts and CI declarations to establish a working environment. It checks known tools, writable home/cache/temp locations and applicable display prerequisites early. It reuses that environment within the invocation while relevant setup remains unchanged, and explains any necessary setup retry. No descriptor, new configuration key, environment contract or validation service is introduced.
4. P4. Required local checks still run for each repair candidate before publication. Existing clean and cache-bypassing requirements remain intact. Reuse applies to setup and useful discovery context, not a new command-result cache or receipt gate. The existing CI verdict remains necessary. Environment and progress guidance introduces no additional blocking condition.
5. P5. One failed CI repair attempt means the agent made a source or merge change, claimed the repair complete, the change was committed and pushed, and CI actually failed on that published repair commit. Count that cycle once, regardless of which check failed or whether the failure was in source, tests or infrastructure. Several failed jobs or repeated observations on the same repair commit do not spend additional attempts. The initial CI failure that prompted repair is not itself a failed repair.
6. P6. A merge conflict is resolved without consuming a CI repair attempt or triggering exhaustion of that budget. Local setup retries, incomplete repairs, failed publication, pending/queued/absent checks and external base advancement do not spend it either. A merge repair that is published and then genuinely fails CI counts under P5. After three qualifying failed repair cycles, the existing cap prevents another CI repair. A third published repair remains under observation until its CI result is known. This distinction never resets an already counted failure merely because the next error differs.
7. P7. Reports distinguish repairs launched, repairs pushed, CI results still pending and CI-confirmed failed repairs in plain language using facts the runtime already has. At a stop, explain which published repair commits actually failed and identify any separate current conflict or environment problem. A new merge conflict cannot be described as a failed CI verdict on an unjudged repair.
8. P8. Existing targeting, worktree/publication ownership, cancellation, cleanup and terminal PR policy remain intact. The attempt-consumption change is deliberate and applies consistently to standalone monitor, goal monitor and phase-pr monitoring. No schema, schema validator, new wire requirement, progress journal, parsed activity vocabulary, completion receipt, migration or new block reason is added. Existing architecture guards and rejection fixtures stay unchanged or tighter.

P1-P4 and the absence of new protocols are primarily reviewed through the authored guidance and actual output paths. P5-P7 require focused behavioral proof at the existing monitor and loop boundaries. P8 combines existing regression coverage with the full architecture review checklist. Do not add tests that snapshot prose or mirror helpers.

## Prose-first design

Put the instructions in existing authored content and runtime-owned repair briefings. Agents describe what they are doing in natural sentences, without required headers, tokens, JSON, catalogs or files for the parent to parse. Ordinary terminal commands remain the agent's way to inspect and fix the repository.

Use existing activity/progress hooks for caller visibility. Forward available information through current sinks where needed. A missing optional progress detail is not a failure. Do not infer successful work from a heartbeat, turn a prose report into proof of validation, or add a new gate that requires an agent to report in a prescribed form.

Environment guidance tells the agent to discover applicable repository/CI setup once, check likely prerequisites before expensive work, keep a working setup for later rounds, and revisit it when relevant inputs change. Git, Python, a suitable JDK, writable Java user.home and Xvfb were concrete issues in this repository. They belong in repository-specific guidance derived from its CI, not a hard-coded platform table in the shared runtime. Reuse existing package/tool caches only where required commands permit it. Never install dependencies or stop unrelated processes without existing authority.

Use the current accounting and publication owners for the CI budget. A loop entry, a claim of completion, a successful local run and a new conflict are not a failed CI repair. Associate an observed CI failure with the actual published repair commit using existing repository/run/check observations. Do not manufacture associations, parse a prescribed agent sentence, or redesign durable formats to add this feature. Preserve existing cancellation, timeout, unavailable-data and local repair failure behavior. Unavailable evidence must not be invented as either success or a spent CI attempt.

## SKILL-427 integration

SKILL-427 owns isolated standalone monitor worktrees, target resolution, resource admission and guarded publication. This bundle does not implement or duplicate that work and does not require it to have landed.

The explicit operator clarification in P5-P6 replaces this bundle's earlier instruction to preserve the old attempt-consumption semantics. Preserve SKILL-427's ownership/publication boundaries and the current accepted execution model. Prefer the existing loop/accounting owner and shared briefings over edits to protected strategy sources. Reconcile overlapping work on the implementation checkout without reverting another bundle.

## Subtasks

1. Explain CI waits and current repair activity through prose and existing progress paths.
2. Add practical environment preparation and reuse guidance using current repository/CI declarations.
3. Correct CI repair attempt accounting and make terminal reports explain the actual failed cycles.

These are implementation boundaries within one feature. Their names do not authorize implementation during this planning request.

## Test obligations

The accounting regression must reproduce a published third repair whose CI is pending when main advances into a merge conflict. It proves that conflict resolution and continued observation do not spend a fourth attempt or report the third repair as failed.

A second scenario covers real failures on three published repair commits. Each counts once even with several failing jobs and repeated polls, while a changed failure cause still counts. The existing cap stops the next CI repair only after the third qualifying failure.

Retain or extend existing route, publication, cancellation and timeout tests where behavior changes. Add one focused progress regression only if runtime forwarding changes. A prose-only guidance change needs source review, not a new test suite. No new schema/parity/validator tests, prose snapshots, live PR mutations or generalized environment/cache tests are part of this feature.

## Architecture and validation

Apply AGENTS.md, docs/runtime-command-guidance.md, docs/architecture-guidelines.md A1-A12/G1-G7 and the full section 5 checklist, runtime-kotlin/ARCHITECTURE.md Design Principles, docs/code-principles.md, docs/skill-source-generation.md and docs/observability-policy.md. Keep one state/resource owner, inward dependencies, purpose-built existing boundaries, injected effects and typed internal bookkeeping where needed. These rules do not authorize a new schema or protocol.

Later validation discovers and runs the repository's required checks, including applicable runtime checks, lint/config checks, desktop packaging and installation smoke. Required clean/cache-bypass commands remain required. Refresh installation through ./install.sh if authored skill/support generation changes. Planning does not run these commands. Do not weaken guards, add exemptions, introduce comments forbidden by repository rules, or substitute compilation for validation.

## Next path

This revised bundle remains planning-only. The existing plan was wftr-20261015-193806-2f58. These authored corrections replace its earlier design assumptions; any later execution must use the revised files and the runtime's normal planning-drift handling rather than stale saved instructions.

A future authorized run can use skill-bill SKILL-428. Do not launch it as part of this spec correction.
