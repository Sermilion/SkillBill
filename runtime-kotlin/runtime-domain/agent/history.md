# Boundary History — runtime-domain

## [2026-10-01] SKILL-397 subtask 2 — Domain-owned aggregate transitions
Areas: runtime-domain/workflow/decomposition, runtime-domain/workflow/model, runtime-domain/goalrunner, runtime-engine/goalrunner, runtime-application/decomposition
- Decomposition manifest subtask transitions (attempt, complete, stop, resume, branch setup, branch selection, runtime-state preservation) now live in the domain as pure functions on the manifest aggregate.
- Added domain restart and replan transitions (unlaunched-boundary check, hard and soft reset, restart and replan intent, post-child-deletion states); every reset path shares one internal reset-to-pending step.
- Added a closed subtask-action type with wire parsing; goal-runner model now owns operator-boundary pausing, target-reached, and stop-reason ledger/diagnostic/next-action mapping.
- Removed engine and application duplicates plus the subtask-ordering helper and string-token constants; callers import the domain functions. No typealias, baseline row, or exemption added.
- Pattern: aggregate state rules belong in the domain; engine and application only orchestrate and persist. reusable
- Known limitation: build, tests, and cycle/package-SCC checks are left to validate; a flagged cycle in workflow.decomposition or goalrunner.model must be fixed, not baselined.
Feature flag: N/A
Acceptance criteria: implemented per spec (audit counts settle in later phases)

## [2026-09-24] SKILL-372 subtask 2 — Domain-owned artifacts and shared rules
Areas: runtime-domain, runtime-ports, runtime-engine, runtime-application, runtime-infra-sqlite, runtime-mcp, runtime-cli, runtime-core
- Added typed artifact-family reads and writes, moved shared workflow rules and validator contracts to their owning boundaries, and preserved wire-compatible encoding.
- Removed duplicate adapter decoders, raw domain-key reads, forwarding validator extensions, and local integer coercions; malformed payloads now fail at typed seams.
- Pattern: keep pure artifact projection and coercion in runtime-domain; validate encoded payloads at adapter/application boundaries. reusable
- Known limitation: package and public-surface cleanup continues in SKILL-372 subtask 3.
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-09-24] SKILL-372 subtask 1 — Typed workflow aggregate and strict decode
Areas: runtime-domain/workflow, runtime-ports/workflow, runtime-engine/workflow, runtime-application/workflow, runtime-infra-sqlite/workflow, runtime-core
- `WorkflowStateSnapshot` now carries typed steps, artifacts, mode, and `Instant` timestamps; port mapping is the single strict JSON decode/encode seam and preserves stored bytes.
- Engine open/update validation remains always-on with typed schema failures; validator ownership moved to runtime-ports and consumers use typed snapshots.
- Domain and adapter timestamp handling uses `Instant`, removing consumer reparsing and lenient empty-map fallback paths.
- Pattern: decode durable row text once into a typed aggregate at the mapping boundary; keep validation at write/adaptation seams and quarantine malformed persisted input. reusable
- Breaking changes or known limitations: malformed legacy workflow data now fails typed and follows quarantine/regeneration; wire formats remain unchanged.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-09-19] SKILL-363 subtask 1 — Add the mandatory simplification phase
Areas: runtime-domain/taskruntime, runtime-engine/featuretask, runtime-infra/skills, orchestration/contracts, README, skills catalog
- Added the single-session `simplify` phase between `implement` and `audit`, with bounded subtask scope, receipt persistence, resume reconstruction, and audit-before-review routing.
- Retired the standalone over-engineering skill source and catalog entry while preserving governed install and validation boundaries.
- Pattern: keep mutating phase order, prompt scope, output validation, and recovery semantics explicit at the workflow boundary; use bounded receipts instead of free-form review transcripts. reusable
- Known limitation: simplification is intentionally limited to high-confidence feature-local reductions and does not perform repository-wide scans or delegated review.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-17] SKILL-351 subtask 3 — Shrink surface and merge count-split units
Areas: runtime-domain, runtime-contracts, runtime-core/architecture, runtime-engine/featuretask, runtime-infra-fs, runtime-infra-sqlite
- Removed unused runtime declarations and merged canonicalizer and goal-observability helper families into responsibility-owned units; deleted the closed-key parity branch and retired the remote-stats runtime wrapper.
- Reduced ledger accumulation to pure summary reduction, carried truncation records through decode results, and made install transaction symlink state immutable with a named transition.
- Pattern: keep boundary transformations pure and return diagnostics as typed result data; consolidate helper buckets only when one responsibility owns them. reusable
- Known limitation: no runtime behavior changes are intended; canonicalizer output and existing ledger/install semantics remain compatibility boundaries.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-17] SKILL-351 subtask 2 — Restore ownership and typed boundaries
Areas: runtime-domain, runtime-application, runtime-core, runtime-engine, runtime-infra-fs, runtime-infra-sqlite, runtime-ports, runtime-cli, runtime-mcp, runtime-contracts, runtime-kotlin docs
- Consolidated feature-task wire validation behind one closed artifact-kind boundary, moved ownership to domain/application seams, and removed redundant forwarding ports, adapters, and fixtures.
- Moved learning DTO/wire helpers to application ownership, relocated version ownership to runtime-core, and restored typed workflow status/resume boundaries.
- Removed workflow-engine coupling to taskruntime, centralized artifact map keys, and expanded governed wire-vocabulary coverage without changing supported wire bytes.
- Pattern: keep schema and wire vocabulary ownership at the boundary that validates or serializes it, with typed domain models crossing engine seams. reusable
- Known limitation: fixture parity for the learning session remains a validation-phase check.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-16] SKILL-351 subtask 1 — Unify durable decoding and failure reporting
Areas: runtime-domain/durable-decoding, runtime-domain/workflow, runtime-domain/goalrunner, runtime-domain/review, runtime-core/architecture, runtime-infra-sqlite/review
- Durable map, list, object, boolean, integer, and long reads now share one typed-error boundary; malformed durable values reach quarantine instead of leaking generic argument or state failures.
- Review enums, finding citations, workflow state, goal observability, phase records, and decomposition data preserve their family-specific failure identity; lane accounting uses `JsonCodec`.
- Silent fallback seams record substitutions, and the dead goal-observability liveness reader was removed.
- Pattern: centralize exact durable coercion and inject only the owning typed-error factory. reusable
- Known limitations: two area-specific parsing helpers remain, and existing durable-seam tests outside the new domain coverage still need assertion retargeting.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-09-12] SKILL-340 subtask 1 — File-level citation line normalization
Areas: runtime-domain/review, runtime-domain/goalrunner/subtaskreview, runtime-application/review
- Review citation ingestion now coerces zero and other non-positive numeric line values to `1` before constructing `ReviewFindingCitation`; positive lines remain unchanged.
- Trailing structured citation fields and codec callers preserve repository-relative path validation while retaining per-citation diagnostics for missing, blank, or non-numeric lines.
- Pattern: normalize recoverable boundary values at ingestion while preserving usable findings and diagnostics for malformed entries. reusable
- Breaking changes or known limitations: review prompts and path acceptance are unchanged.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-09-12] SKILL-333 subtask 1 — isolate malformed citations
Areas: runtime-domain/review, runtime-application/review, runtime-engine/featuretask, runtime-ports/review, runtime-core/architecture
- Malformed citation fields are isolated per finding, valid citations remain in the review result, and typed citation diagnostics retain finding or entry identity through review outcomes.
- Review parsing, claim adjudication, durable phase output, and retryable runtime handling now share the diagnostic-carrying result path.
- Pattern: preserve usable findings while making malformed structured fields observable at the ingestion boundary. reusable
- Breaking changes or known limitations: blank and repository-external paths remain rejected; positive-line validation remains enforced.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-24] SKILL-206 remove-provider-token-accounting
Areas: runtime-domain/review/context, runtime-application/review, runtime-ports/review, runtime-infra-fs, runtime-infra-sqlite, orchestration/contracts
- Review accounting now retains byte, count, routing, parent-analysis, integration, segment, and local byte-derived estimate surfaces without provider-reported token values.
- Removed provider-token review models, ownership and regression paths, threshold launch fragments, broker/protocol evaluation, and tree folding.
- Review-context contract 2.2 rejects retired provider fields while durable 2.1 accounting rows remain readable without rewriting.
- Pattern: keep runtime-owned accounting authoritative while transport-only provider fields remain isolated until their producer removal. reusable
- Transport token fields remain present and unused until the producer-removal subtask.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-24] SKILL-200 subtask 2 — Continuation map and documentation cleanup
Areas: runtime-domain/workflow, runtime-kotlin docs, docs, install
- Removed dead continuation content-path entries for `bill-feature-task` and `feature-task-runtime`; continuation prompts now add normal-instructions guidance only for retained mappings.
- Runtime-specific branching identifies `FeatureTaskRuntimePhaseWorkflowDefinition` by durable workflow name, while runtime persistence labels the implementation skill `bill-feature`; durable `workflow_name=bill-feature-task` identity remains unchanged.
- Repointed install guidance and internal-skill, source-generation, capability, and architecture documentation to the retained `bill-feature` and `bill-feature-goal` surfaces.
- Pattern: keep workflow identity separate from authored instruction paths and use definition metadata instead of deleted skill-name branches. reusable
- Breaking changes or known limitations: deleted authored continuation surfaces have no compatibility shim; the `skill-bill feature-task` CLI and durable workflow identity remain unchanged.
Feature flag: N/A
Acceptance criteria: 9/11 implemented

## [2026-08-20] SKILL-201 subtask 4 — Reporting schema and projections
Areas: runtime-infra-sqlite/sqlite/review, runtime-core/review (test)
- `loadReviewAccounting` quarantines persisted accounting rows whose lanes still list `evidence-unreviewable` in `unreviewed_segment_ids`; read returns null and emits `ACCOUNTING_CONTRACT_QUARANTINED` degradation telemetry instead of serving stale projection semantics or crashing
- `quarantineReviewAccounting` now takes an expected/actual pair shared by contract-version drift and legacy segment-id detection (reusable)
- `ReviewAccountingDurableRedactionTest` pins in-band quarantine and regeneration when a pre-subtask-1 row carries the retired segment id
- Pattern: legacy review accounting contracts fail loud at the SQLite load seam; regeneration belongs to the writer, matching runtime contract quarantine rules
- Known limits: end-to-end delegated review reproduction remains subtask 5
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-20] SKILL-201 subtask 3 — Per-lane budget derivation from assignment breadth
Areas: runtime-domain/review/context/model, runtime-application/review
- `ReviewContextBudgetPolicy.deriveLaneEvidenceBytes` scales each lane's cumulative broker `read_evidence` cap from the repo base policy by that lane's share of packet hunk content bytes, floored at `maxEvidenceResultBytes`; lanes owning more diff surface receive a larger allowance than narrow lanes
- `ReviewPreparationService.deriveSpecialistBudget` applies assignment-scaled derivation when composing specialist launches; `ParallelCodeReviewRunner.mergedBudget` sums each specialist's derived cap for the parent broker instead of `base * laneCount`
- `maxLaneEvidenceBytes` now names one thing everywhere: the cumulative broker read_evidence allowance for the bound assignment surface, not a flat per-lane constant independent of ownership breadth
- Pattern: derive enforcement budgets from assignment breadth at preparation; parent fan-out sums specialist-derived caps so parent and lane seams stay aligned. reusable
- Known limits: coverage report and integration-pass prompt changes remain subtask 4
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-20] SKILL-201 subtask 2 — Broker refusal drives lane_evidence_bytes incomplete verdict
Areas: runtime-domain/review/context/model, runtime-infra-fs/infrastructure/fs, runtime-application/review
- `FileSystemReviewEvidenceBroker` records each `lane_evidence_bytes` refusal at the read that triggers it, appending a `commitSha@path` unit to `deniedUnits`; accounting exposes `budgetDimension` and `unreviewedUnits` only when the terminal outcome names that budget kind.
- `ReviewLaneCompletionState.withBrokerEvidenceRefusal` is the completion seam: incomplete disposition, `lane_evidence_bytes` dimension, and the broker's denied-unit list — not a path-sorted greedy tail over the assembled bundle.
- `ParallelCodeReviewRunner` applies broker accounting to bundle completion after a worker run; a successful lane with no evidence refusal stays complete (builds on subtask 1's severed projection).
- Pattern: name unreviewed units only from enforcement (broker reads), never from pre-flight projection or alphabetical bundle order. reusable
- Known limits: per-lane budget derivation remains subtask 3; coverage report and integration-pass prompt changes are subtask 4.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-08-20] SKILL-201 subtask 1 — Sever coverage verdict from projected evidence budget
Areas: runtime-domain/review/context/model
- Removed `withLaneEvidenceBudget` from `GovernedReviewLaunch.completionState`; a lane whose worker run succeeded and whose segmentation stayed clean no longer flips to `incomplete` from a pre-flight sum of `entry.hunk.contentBytes` against `maxLaneEvidenceBytes`
- Retired `EVIDENCE_UNREVIEWABLE_SEGMENT_ID` and the projection-only evidence-budget rewrite; `unreviewedUnits` may now come only from withheld or refused reads or a failed run, not from an allowance the broker never attempted to spend
- Segmentation's `unreviewable` path for entries exceeding `maxLaneLaunchBytes` and `asFailedLaneRun` are unchanged; subtask 2 owns wiring broker refusal into completion
- Pattern: keep enforcement (broker reads) and projection (pre-flight size sums) separate at the completion seam so coverage verdicts name only code the worker did not receive. reusable
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-08-19] Amend-aware checkpoint-identity contract
Areas: runtime-domain/workflow/taskruntime, orchestration/contracts
- `FeatureTaskRuntimeCheckpointIdentity` now requires `checkpointRef` to equal `featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)`. The pattern alone only proved shape, so an inline-formatted or stale-subtask ref used to validate and then dedupe under an authority boundary its own record does not own; because the ref is the identity, that drift was invisible on every later read.
- The same rule is written as the `checkpoint_ref_derives_from_its_boundary` coherence check in `feature-task-runtime-checkpoint-identity-schema.yaml`, keeping the YAML contract and the Kotlin `require` in agreement.
- Ledger reads stay fail-whole: one drifted record raises `InvalidWorkflowStateSchemaError` for the entire artifact, never a valid subset.
- Duplicate-ref coverage was rewritten to build the collision from two records sharing a sequence number, since a hand-copied ref can no longer survive construction.
- Contract shape follows the amend model: `sequence_numbers_are_unique` stays (append-only ledger), `commit_shas_are_unique` is gone, ref uniqueness replaces it.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-08-18] KMP owns the architecture review area
Areas: runtime-domain/review-context, platform-packs/kmp (code-review), docs
- `kmp` now declares `architecture` in `routing_signals`, `declared_code_review_areas`, `area_metadata`, and `pointers`, with Android-appropriate content at `code-review/bill-kmp-code-review-architecture/`; the area no longer falls through to the backend/desktop-oriented `kotlin` baseline. `kotlin` is byte-unchanged.
- `ReviewOperationPolicy` now threads the assigned-path check into `classifyAbsoluteProhibitions`: a path explicitly assigned to a specialist outranks both the diff-artifact-rediscovery ban and the routing-path prohibitions. Unassigned paths keep the old bans. This unblocks specialists whose own rubric or pack file sits under a routing-shaped path.
- Ownership is pinned by regression tests on both routes (`KmpPlatformPackTest`, `ComposedReviewLaunchPlanTest`): an Android/KMP diff plans `architecture` owned by `kmp`, a backend Kotlin diff still plans it owned by `kotlin`, and the planned area set is unchanged.
- Native-agent bundle and snapshot updated for all six declared `kmp` areas.
- Docs/README wording refreshed alongside; SKILL-198 spec artifacts landed in the same branch.
Feature flag: N/A
Acceptance criteria: 11/11 implemented

## [2026-08-17] Cursor sessions resolve as invoking agent
Areas: runtime-domain/install, runtime-cli/codereview
- Flagless `skill-bill` from a Cursor agent used to miss detection and fall through to the last-resort `codex` lane, so review/goal/task workers launched Codex instead of Cursor.
- `INVOKING_AGENT_CONTEXT_SIGNALS` now maps session markers `CURSOR_AGENT` and `CURSOR_INVOKED_AS` to `cursor`, after Claude and Codex so existing dual-marker precedence is unchanged. `CURSOR_API_KEY` stays a credential, not a session identity.
- `--agent1` / `SKILL_BILL_AGENT` still win; empty environments still default to Codex.
Feature flag: N/A
Acceptance criteria: N/A (defect fix)

## [2026-08-06] SKILL-158 subtask 3 — Single-pass bundled lane review
Areas: runtime-domain/review, runtime-domain/review/context/model, runtime-application/review, runtime-infra-sqlite/db/core, runtime-infra-sqlite/review, runtime-ports/review/model, orchestration/contracts, orchestration/review-orchestrator
- `ReviewLaneBundleAssembly` assembles one bundle per selected lane from the sparse commit-to-lane assignments: assignment-limited hunk bodies only, ordered by commit order then path, with readable commit identity and a stable composition digest. Workers never see the raw complete diff
- Worker launch count now equals selected lane count and is invariant to commit count (1..20 commits, same launches); a multi-segment lane is still one launch
- Oversized bundles split into the fewest size-driven segments that fit — mechanical, never on commit boundaries — each carrying commit identity/order and its own byte accounting; an entry larger than the budget is recorded unreviewable rather than dropped
- Lanes that cannot finish terminate with an explicit incomplete disposition naming unreviewed segments; incomplete stays distinguishable from clean across launch, progress, result, and resume, and prior findings survive. Resume re-runs only incomplete lanes
- Persistence: new lane disposition, bundle composition digest, and per-segment accounting columns with self-healing column ensures for already-migrated databases
- `FORBIDDEN_REDISCOVERY` names the anti-patterns this replaced (per-commit stepping, worker-side relevance re-decision, aggregate-diff restart) so future work does not reintroduce them. reusable
- Finding parsing/merging extended with commit attribution while staying backward compatible with unattributed findings
Feature flag: N/A
Acceptance criteria: 10/10 implemented

## [2026-08-05] SKILL-136 subtask 4 — Controlled vocabularies for review-run attribution
Areas: runtime-domain/review, runtime-domain/review/model, runtime-domain/workflow/taskruntime/model, runtime-ports/review, runtime-application/review, runtime-application/featuretask, runtime-infra-sqlite/db/core, runtime-infra-sqlite/review, runtime-mcp, platform-packs/kmp native-agents, orchestration/contracts
- `ReviewAttributionCanonicalization` resolves routed skill, detected stack, and detected scope at ingestion against known pack skill names, platform slugs, and the closed scope vocabulary; raw prose is preserved beside each canonical id
- Unresolvable values are marked unresolved explicitly instead of bucketed into a default, and resolution failures surface as typed errors via `ReviewAttributionPort` with CLI/MCP parity coverage
- Free-form scope detail moved to its own field so canonical scope stays enumerable; `execution_mode` is now recorded for runs that previously omitted it
- Migration 24 plus `ReviewAttributionBackfillMigration` heals legacy columns and backfills unambiguous rows: canonical routed-skill grouping collapses 24 variants to one row per pack, canonical stack 57 to one per stack, with the unresolved bucket asserted separately
- Pattern followed for vocabulary work: canonicalize once at the ingestion seam in domain, keep raw text durable, and prove the backfill against a copy of the real store behind an env-gated harness (verified by a negative-path probe that the env actually reaches the test). reusable
- `severity`, `confidence`, `disposition`, and `event_type` deliberately untouched — verified by a zero-matching-line branch-diff check
Feature flag: N/A
Acceptance criteria: 9/9 implemented

## [2026-08-03] SKILL-159 subtask 2 — Review mode rename and single-prompt inline
Areas: runtime-domain/review, runtime-domain/review/context, runtime-domain/workflow/model, runtime-domain/workflow/taskruntime/model, runtime-application/review, runtime-application/featuretask, runtime-cli, runtime-contracts, orchestration/contracts, orchestration/review-*, skills/bill-code-review, skills/bill-feature*
- `CodeReviewExecutionMode` now means `delegated` = specialist subagent fan-out (and is the default when no `code-review:` token is given), `inline` = exactly one review prompt in the caller's own context; the old external-process meaning of `delegated` is gone
- `ReviewExecutionModePolicy` resolves `auto` to `delegated` on pass one and `inline` on every follow-up/remediation pass; rules renamed to `auto_mode_by_pass_number` / `auto_mode_default` so the resolved mode always reports a deciding rule
- Resolution is one-way: `ResolvedReviewExecutionMode` and the persisted `executed_mode` enums no longer accept `auto`, so a request token can never be mistaken for a resolution
- `goal-subtask-review-state-schema.yaml` bumped 0.2 -> 0.3 with its Kotlin constant and parity test in lockstep; pre-bump records loud-fail at the read seam and are quarantined/regenerated in-band rather than reinterpreted under new semantics
- Pattern followed for semantics-changing renames that reuse an existing wire token: bump the schema so old records fail loudly instead of silently acquiring the new meaning. reusable
- `parallel-review:<agent>` composes with both primary lanes; the parallel lane inherits the primary resolved mode instead of resolving independently
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-27] SKILL-132 subtask 2 — Remove the dormant review pilot
Areas: runtime-domain/review/context, runtime-application/review, runtime-infra-fs tests, docs, orchestration/review-orchestrator
- Re-tracing the review-context slice against main proved it mostly active: ReviewAssignment, ReviewContextPacket, GovernedReviewLaunch, ReviewEvidenceBroker (+ binding and FS implementation), and the review-context schema family are DI-bound on the live parallel-review path and were kept
- Removed the genuinely dormant auto-eligibility residue: ReviewAutoEligibility, the eligibility parameter on ReviewExecutionModePolicy, the constant-returning resolveAutoByEligibility, and ParallelCodeReviewRunner.HIGH_RISK_SIGNAL; resolved review depth is unchanged in every case
- All nine review_context_budget sub-keys reach an active consumer, so no configuration key was removed; strict/tolerant config behavior is now covered in both directions (accepted keys and rejected unsupported keys)
- Pattern followed: prove reachability per candidate from composition roots before deleting, and record the per-candidate verdict in the spec's evidence-ledger.md rather than in code comments. reusable
- Known limitation: runtime-cli CliCodeReviewParallelRuntimeTest has 8 failures reproducible at the pre-branch commit; they come from install-staging add-on omission and are out of this subtask's scope
Feature flag: N/A
Acceptance criteria: 6/6 implemented
