# SKILL-387 - prose-phase-output-and-spec-handoff

## Mode

single_spec

## Intended Outcome

Every agent phase uses the existing skillbill.agent.model.PhaseOutput, centered on prose in value with optional prompt. Agent responses pass through no strict schema, structural repair, or formatting retry. Plan writes the governed spec artifacts and returns the selected spec path in PhaseOutput.value. Implementation reads that file.

## Overview

One coherent subtask migrates response admission, phase decisions, persisted spec handoff and recovery together. Splitting by module or phase would leave competing live response paths or an unusable intermediate commit.

Keep runtime-owned process and durable-state metadata separate from agent content. Preserve artifact readiness, accepted-step authority, terminal failure precedence, review/audit decisions, command evidence and compatibility. Add no replacement DTO, framework, module or dependency bag.

SKILL-388 owns the prerequisite scanner and application-boundary cleanup. Apply its changes first, then recheck overlapping registrations and review callers. The concurrent CLI SKILL-386 bundle owns its command and contract changes; this bundle does not edit that spec or repeat its findings.

## Acceptance Criteria

1. All agent-content boundaries in ordinary execution, standalone definitions, goal shared-preplan/fan-out, inline/delegated review, claim/finding verification, audit/review repairs, validation/build agent turns, write_history and pr use existing skillbill.agent.model.PhaseOutput with value and optional prompt. AgentPhaseOutput and competing response values are removed or lose their agent-content responsibilities. Process capture and durable metadata remain runtime-owned.
2. Live-agent admission and fallback prompts accept ordinary prose without response-envelope or prose schema validation. Missing contract_version, phase_id, summary or produced_outputs causes no wrapper diagnostic, format correction or agent relaunch. Nested digest/plan/receipt JSON requirements disappear, without a replacement response DTO, phase-output/projection schema or strict prose grammar.
3. The existing complete/block settlement owner admits agent families under current accepted-step/attempt authority. Explicit runtime block/failure, cancellation, timeout, launch/capture failure and terminal disposition retain precedence over exit status and prose. Ephemeral routes retain their settlement owner without creating a workflow solely to accept content.
4. Standalone plan permits only authorized new local bundle artifacts, retains existing atomic writing and persisted readiness/reload checks, and returns its canonical repository-relative parent spec path in PhaseOutput.value. It creates no implementation edits, branch, feature workflow row or checkpoint commit and requires no decomposition_package response.
5. Goal planning keeps its assigned governedSubSpecPath and writes details into that existing subtask spec. Admission validates the persisted file and computes its post-write subSpecHash before checkpointing matching path/descriptor/preparation and hydration. Parent/shared-preplan provenance, manifest order, dependencies and routing remain authoritative, without parent replacement or another planning tree/decomposition.
6. Implementation launch reads the selected governed spec for details. Existing path authorization, real-file/readability, manifest readiness, required acceptance-list and subtask/dependency checks prevent launch for missing, escaped/symlink-escaped, wrong-subtask, partial or acceptance-free artifacts. The response carries no duplicate executable plan.
7. Existing review/finding owners interpret PhaseOutput prose into authoritative decisions. Unresolved Blocker/Major findings block; omitted or ambiguous dispositions imply neither approval nor refutation. Citation/boundary provenance, repair omissions, repeated attempted-unresolved policy, reconciliation and exact reviewed identities remain enforced without response-format gates.
8. Existing audit owners accept ordinary prose identifying remaining production criteria or clearly stating that none remain. They preserve criterion identity, full reinspection and shrinking remaining-set admission; ambiguity blocks. Audit remains read-only, executes no checks and retains its current test-only exclusion.
9. Agent validation keeps every required project-check repair in one session and completes only after checks pass, with block for a concrete external obstacle. Formatting never starts another validation session. Build retains compile-only pack-command ownership and same-session repair. Runtime-generated command/exit/checkpoint evidence retains its authority.
10. Persistence/recovery reads supported historical envelopes through a bounded owner while fresh content bypasses legacy strict validation. Compatible resume preserves accepted identity, attempts, completed steps and finalization effects. Unsupported records or legacy pending plans without ready selected files use existing typed observable recovery before launch, without hidden conversion/replay or blanket version bumps.
11. Production-unused response codecs, structural repair, format-relaunch machinery, response-only schemas/wrappers/prompts/ports and bindings are deleted after consumer classification. Required persisted/domain, manifest, vendor-input and wire/telemetry schemas remain. commit_push remains runtime-only, and leases/fencing, cleanup, checkpoints and finalization ownership remain intact.
12. PhaseOutput remains in domain with current injection/scopes, source-of-truth keys and existing scanners. Domain gains no java.nio or skillbill.ports dependency. No new module, framework, completion interface/callback bag, dependency bag, architecture-test class, baseline expansion or forbidden source comments are introduced.
13. Existing boundary suites contain regressions for the exact wrapper incident and ordinary prose across families/routes, selected-spec admission and denied bad artifacts, post-write hash/restart compatibility, explicit terminal precedence, review/repair/audit decisions and validation's original session. Each obligation names a concrete wrong behavior and asserts outcomes. Governed parity and real regression coverage remain.

## Constraints

- Use existing PhaseOutput as the required common class. Do not leave its selection open or introduce a competing response contract.
- Keep prose free of mandatory JSON, headings, field lists, counts or nested structured-plan requirements.
- Preserve authoritative manifest/runtime/vendor/wire validation, accepted-step identity, terminal semantics, cancellation, cleanup, leases/fencing, transaction ownership and completed effects.
- Add no module, framework, completion interface, callback/dependency bag, architecture-test class, baseline expansion or forbidden source comments.
- This preparation changes only the spec bundle. Implementation and validation remain future work.

## Non-Goals

- Editing SKILL-386 or SKILL-388 bundles or implementing their separate architecture investigations.
- Adding another module, response DTO/schema, completion interface, callback/dependency bag, authoring framework, planning tree, architecture-test class, baseline entry or forbidden Kotlin comments.
- Removing validation of authoritative manifests, runtime state, process evidence, vendor inputs or wire/telemetry data.
- Changing goal dependencies, phase order, review severity/blocking, audit progress/test scope, validation/build ownership, leases/fencing, accepted-step authority or checkpoint/commit/PR policy.
- Replaying completed work or silently converting historical response plans into new artifact authority.
- Implementing, switching branches, running checks, staging, committing, pushing, opening a PR or executing a goal during this preparation.

## Validation Strategy

This preparation executes no compilation, build, tests, generators, installation or full checks. Implement and audit assess source facts without claiming later execution evidence. Audit stays read-only under its current production/test scope.

Validate runs required project checks discovered from repository instructions, build configuration, scripts and CI through the selected strategy. Current Kotlin pack full gates are ./gradlew check --continue --parallel -q --warning-mode none and ./gradlew check --continue --no-build-cache --parallel -q --warning-mode none. Agent validation repairs every failure in its original session. Preserve standalone pack validation and command evidence.

Exercise existing engine attempt/settlement, goal planning/hydration, application review/verification, contract compatibility, filesystem/SQLite recovery, CLI and MCP suites for TO-001 through TO-006. Run existing dependency/capability, wire vocabulary, typed parsing, comment/import/clustering/line-ceiling guards and governed parity. Fixture capture/generation belongs only to validate under existing instructions. Preserve regressions and expand no baselines.

Only a selected build phase runs pack build_command and cache_bypassing_build_command, currently ./gradlew compileKotlin and ./gradlew compileKotlin --no-build-cache. Build supplies compile proof without tests/full checks and retains same-session repair. Constructors, visibility and kotlin-inject generation remain compiler-only unknowns until then.

Review changed tests through skill-bill operation unit-test-value-check at the authorized gate. Changed authored instructions/rendering require docs/skill-source-generation.md and parent-owned installation refresh where authorized. Keep installer/uninstall/install-sync commands out of goal-child plans. The preparation runtime owns schema-valid manifest persistence, atomic writing and reload. Local field/type/dependency preflight passed; full local schema preflight was unavailable. tests_executed remains empty.

Preparation performs read-only goal preflight after bundle corrections. No implementation, compilation, build or project test result is claimed.

## Investigation

This investigation records the consumer map, minimal target design, compatibility policy and retained authority. The parent and ordered subtask share the same implementation acceptance criteria.

### Baseline and coordination

Inspection used base/SKILL-380-phase-slot-strategies at dfb489641f6d67a3e3f45e4a67330a3fff798029. CLAUDE.md points to AGENTS.md. The Design Principles in runtime-kotlin/ARCHITECTURE.md and docs/code-principles.md govern the design. The installed command skill-bill config resolve-spec-type --arg local --repo-root /home/sermilion/StudioProjects/skill-bill returned local. No Linear calls were made.

Two fresh git fetch --all --prune calls completed. The remote base now names bb442107c9d4a50f9b879979109022c97955fe0c, whose subject is SKILL-386: Plan runtime-cli architecture cleanup. Its bundle is .feature-specs/SKILL-386-runtime-cli-architecture. Preserve that bundle and its issue reservation. Its second subtask concerns CLI-facing contract ownership, status enums, mappings and module documentation. Reread overlapping runtime-core bindings, runtime-contracts, CLI consumers and module documentation before implementation; this feature does not redesign CLI composition.

The final active/done directory, all-branch, all-ref commit-history and fetched-base bundle census found SKILL-387 free. Initial status was clean. Final status found a concurrent untracked .feature-specs/SKILL-388-runtime-application-boundary-repairs/ bundle. This agent did not create or edit it. Its subtask covers typed-parse scanner registrations, purpose-built diff queries and moving transport decoding into the existing workflow adapter. Coordinate overlapping response-codec deletions, inventory registrations and application review consumers. No external manifest dependency is added; whichever implementation lands later must use the surviving owners without restoring removed response contracts. The local branch and HEAD remain unchanged.

SKILL-380 already supplies the slot run loop, standalone plan preparation and goal-planning fan-out. SKILL-384 already supplies accepted-step capabilities, required persistence before execution and coupled transition ownership. SKILL-211 through SKILL-214 and SKILL-217 already moved several handoffs to prose. SKILL-224 supplies durable settlement and envelope salvage. SKILL-385 preserves authoring/check ownership and removes audit's compile exception. Keep these landed changes. Superseded decision entries describe earlier validation retries; current AgentValidateStrategy and VALIDATE_VALUE_CONTENT require all project-check repairs in the same agent session.

### Current consumer map

The following paths are under runtime-kotlin. Engine paths are relative to runtime-engine/src/main/kotlin/skillbill/engine unless another module is named.

| Family or route | Current admission | Decision, persistence and downstream launch |
| --- | --- | --- |
| Ordinary ephemeral and durable steps | featuretask/slot/runner/DefaultPhaseRunner captures stdout bytes, termination, file manifests and settlement lookup. PhaseAttemptOnce dispatches the accepted step after required persistence. PhaseStepDescription.phaseEnvelopeDecoder invokes FeatureTaskRuntimePhaseOutputValidator. slot/attempt/PhaseOutputGate decodes stdout or stored settlement. | Strategy hooks and the coupled transition owner persist accepted normalized output. Recorded output reaches FeatureTaskRuntimeHandoffProjectionValueBuilder and the next briefing. Ephemeral state creates no feature workflow row. |
| Preplan | slot/preplan/AgentPreplanStrategy requests preplanning_digest JSON inside value and permits invalid-output relaunch. | Producer planning projections and shared goal-preplan checkpoints still interpret response-derived structure before plan receives the value. |
| Standalone bundle plan | slot/plan/AgentPlanStrategy forbids writes and requests decomposition_package. runloop/planning/PlanDecompositionStop reparses the envelope and delegates its package to FeatureTaskRuntimeDecompositionPlanner. | FeatureSpecPreparationRuntime applies policy. FeatureSpecPreparationWriter validates ordered subtasks, prepares a manifest through DecompositionManifestWriter, writes atomically through the existing store and reloads the manifest. The decomposition terminal stops preparation before implementation. |
| Goal shared preplan and per-subtask plan | GoalPlanFanOutStrategy composes accepted planning steps. goalrunner/planning/attempt/GoalPlanningPhaseAttemptGate validates response envelopes and producer projections and contains schema-retry paths. | GoalPlanningPreparationCheckpoint owns checkpoint writes, digests, provenance and recovery. GoalPlanningSubtaskPlanProduction resolves the assigned spec and records manifest order, governedSubSpecPath and subSpecHash. GoalChildPlanningHydrator imports completed planning records and enters implement. |
| Implement and simplify | slot/implementation/ImplementThenSimplifyStrategy and ImplementationPromptSections request stuffed implementation_receipt and simplification_receipt JSON. Implementation reads executable_plan JSON. | Receipt/reconciliation and repair owners consume response-derived material. Handoff builders combine it with runtime checkpoint facts. Implementation must instead read the selected governed spec. |
| Audit and audit repair | slot/audit/AcceptanceAuditStrategy shares envelope decoding. AcceptanceAuditPromptSections requests remaining criteria and exact empty-list syntax. AcceptanceAuditRemainingCriteriaParser interprets catalog identities. | Existing audit catalog/progress owners retain complete production-criterion reinspection and shrinking remaining-set admission. Audit stays read-only, excludes test-only requirements and runs no commands. audit_implement_fix owns repairs through the implementation model. |
| Inline and delegated review | CodeReviewSlot owns both routes. InlineReviewStrategy and DelegatedReviewPass permit invalid-output relaunch. InlineReviewEnvelope assembles an envelope from prose and measured review facts. CodeReviewStep still rejects missing response verdict/findings fields. | Existing review pass state, merged findings, verdict admission, exact reviewed target/tree and review generation/tombstones own blocking and repair. |
| Claim verification and verify_findings | runtime-application ReviewClaimVerificationRunner uses AgentPhaseOutput for prose capture, but per-finding parseWorkerResult requires a JSON object. VerifyFindingsEvidence extracts structured dispositions and checkpoints. | ReviewClaimVerdictAdmission, finding-verification state and citation/boundary provenance owners decide confirmation, refutation and unresolved findings. Omission or ambiguity must not clear a finding. |
| Review repair and reconciliation | InlineReviewPromptSections requires repair_receipt and reconciled_state. FeatureTaskRuntimeRepairReceiptParser/Coverage, RunLoopRepairReceipt and completed-upstream repair checkpoints consume them. | Carried-finding identities, omitted-finding handling, bounded attempted-unresolved policy and actual reconciliation remain domain decisions. Their agent response wrappers are removed. |
| Agent validation, pack validation and pack build | AgentValidateStrategy already has relaunchOnInvalidOutput=false and singleAgentSession=true. PackValidationStrategy and PackBuildStrategy retain runtime gate cycles. QualityGatePromptDirectives accepts prose repair turns but retains optional structured triage capture. | Agent validation repairs every required project check in the same session. Build remains compile-only. Runtime-generated command identity, exits, checkpoint evidence and receipts remain authoritative execution evidence outside prose admission. |
| write_history and pr | BoundaryHistoryStrategy and PrDescriptionStrategy request prose, still wrapped by common response machinery. Their hooks add measured facts. | Existing boundary-history and PR measurement/side-effect owners remain. Resume cannot replay completed effects. |
| commit_push | RuntimeCommitStrategy invokes the runtime-owned commit cycle without an agent launch. | Existing branch refusal, reviewed identities, staging exclusions, checkpoints, commit/push and manifest commit_sha ownership remain outside the agent-output migration. |

### Numbered findings

1. runtime-domain/src/main/kotlin/skillbill/agent/model/PhaseOutput.kt already contains value: String and prompt: String?. The production Kotlin reference search found only its declaration. Select it as the common agent-content value without relocation. AgentPhaseOutput has production consumers in ReviewClaimVerificationRunner and ReviewStageRunnerOutcomes and should fold into PhaseOutput.

2. PhaseStepOutput combines response-derived status/value/verdict with legitimate process facts. Preserve termination, launch failure, capture bytes/hash/truncation, file manifest and settlement observations as runtime capture. FeatureTaskRuntimePhaseOutput carries phase identity and iteration alongside payload and normalization. Preserve required record metadata while removing its competing agent-content responsibility. NormalizedFeatureTaskRuntimePhaseOutput is canonical JSON plus an envelope map and is unsuitable as the common prose value.

3. FeatureTaskRuntimePhaseOutputSchemaValidator performs structural repair, strict or verifying-specific normalization, nested evidence validation and prose synthesis. Synthesis still parses an object and normalizes a stamped envelope. PhaseOutputGate invokes that decoder for persisted settlement as well as stdout. This explains how the supplied status/summary/value incident could report four wrapper violations and then recover without removing the gate.

4. FeatureTaskPhaseSettlementService already owns complete/block persistence and runtime metadata stamping. The gate prefers the current attempt's settlement. FeatureTaskRuntimePhasePromptSettlementDirectives still requires a strict fallback envelope. Extend the existing common prose settlement path to remaining agent families. Preserve current accepted-attempt admission and persistence ownership; add no completion interface or callback bag.

5. FeatureSpecPreparationWriter's atomic store, ordered-subtask validation, manifest validation/reload and the required acceptance-list reader protect real artifacts. Keep them. FileSystemFeatureTaskRuntimeRunInvariantsSource reads a regular file and GovernedSpecSectionParser extracts acceptance list items. FileSystemFeatureSpecPathResolver performs lookup, but an explicit path alone does not prove authorization or bundle readiness.

6. GoalPlanningSubtaskPlanProduction currently records descriptor.subSpecHash after plan output without recomputing it after possible spec edits. Plan-session authorship requires validating the persisted file and computing its post-write descriptor hash before checkpoint/import admission. Otherwise recovery can reject the runtime's own preparation as stale.

7. Review cannot become nonblank-text success. Per-finding verification currently rejects non-JSON output, and finding verification/repair coverage consumes response fields. Adapt the existing decision owners to PhaseOutput.value and existing authoritative finding/verdict models. Missing or ambiguous semantic evidence retains unresolved findings. Do not impose mandatory prose headings, fields or counts.

8. Audit already has a criterion catalog and progress owner. Exact empty-list and finding-start syntax are response conventions. Accept ordinary prose clearly stating no production criteria remain or identifying remaining criteria. Ambiguity blocks the decision without a schema-correction launch. Preserve complete reinspection, remaining identities, shrinking-set policy and current production/test scope.

9. Current agent validation requires all check repairs in the same session and disables invalid-output relaunch. Preserve that behavior. Historical progress/no_progress verdicts cannot authorize a formatting restart. Build/validation command evidence remains runtime-owned and continues to reject absent or failed required execution proof.

10. FeatureTaskRuntimeHandoffProjectionValueBuilder still obtains an envelope map and can reject a producer that is not an object. Remove response-oriented projections and producer gates that reconstruct deleted phase responses. Retain compact runtime-owned authority projections and their existing budgets.

11. RuntimeFeatureTaskSlotProvides supplies DefaultPhaseRunner; RuntimeFeatureTaskValidatorProvides binds the response validator. Remove response-only bindings after consumers disappear. No relocation is needed. ImplementationOwnershipArchitectureTest scans actual application/domain/ports main-source roots and rejects domain imports of skillbill.ports. StrategyCapabilityBoundaryArchitectureTest scans runtime-kotlin/runtime-engine/src/main/kotlin. ArchitectureScanSupport finds the repository root and fails for missing scan roots. Domain must remain free of java.nio and port dependencies; the import guard proves only its scanned prefixes.

### Minimal target design

Keep PhaseOutput in its existing domain home. Every agent-driven ordinary step, delegated worker, verification, repair, triage and gate-agent result uses it at the agent-content boundary. Existing process capture, terminal status and durable record identity remain separate runtime responsibilities. Agents do not repeat phase, attempt, contract or status metadata inside their content.

Durable complete/block control stays with the existing settlement owner. Failure and cancellation remain in existing process/terminal handling. Ephemeral routes use their current settlement owner. Accept unwrapped content only after existing terminal checks. Explicit block/failure, cancellation, timeout, failed launch or failed capture cannot be overridden by prose or zero exit.

Remove live response schema gating, structural repair and formatting relaunches at ordinary attempts, review, goal sweeps, producer projections and fallback prompts. Strategies interpret prose only for decisions they already own. Existing findings, verdicts, repair outcomes and audit progress remain authoritative records. Unknown or conflicting decisions stay unresolved or blocked, without a response-format correction session.

Standalone plan may author only its authorized new governed local bundle. Retain existing bundle writer/store atomicity and readiness responsibilities, adapting their boundary to plan-authored artifacts rather than a response decomposition package. Acknowledge readiness after persisted parent/subtask/manifest reload. Return the canonical repository-relative parent spec path in PhaseOutput.value without a duplicate executable plan. Standalone plan stops there, creating no implementation edits, branch, feature workflow row or checkpoint commit.

For a goal planning unit, the assigned governedSubSpecPath remains selected by the existing descriptor. Write implementation details into that existing spec_subtask file while retaining scope, acceptance, dependencies, validation strategy and next path. Do not overwrite the parent bundle or create another decomposition or planning tree. Validate the persisted file, recompute its hash, then checkpoint the matching descriptor/path/preparation before hydration. Preserve manifest order, parent provenance and shared-preplan authority. Interrupted preparation uses existing regenerable/incompatible recovery classification rather than silently relabeling a stale hash.

Implementation resolves the selected path through existing repository/spec owners, verifies readiness and authorization and reads that file for implementation details. Runtime metadata may carry the selected artifact path; it is not another agent response DTO or prose projection schema. Missing, escaped, symlink-escaped, wrong-subtask, partial or acceptance-free artifacts cannot launch implementation.

### Retained authority and deletion

Keep authoritative manifests, workflow snapshots, execution identity, settlement records, planning checkpoints/imports, review/finding/repair ledgers, actual vendor-input contracts and wire/telemetry schemas. Preserve required persistence, accepted-step authorization, leases/fencing, process cleanup, review generations, audit progress, compile-only build, same-session validation, checkpoints and commit/PR ownership.

Delete production-unused response normalization/codecs, structural repair, response validators/ports, correction prompts, format-relaunch branches, per-phase response wrappers, response-only schema definitions and DI bindings. Classify every remaining schema consumer as live agent content, historical persistence or authoritative artifact/vendor data before deletion. Historical readers cannot receive fresh agent output. Keep one live PhaseOutput path without a replacement DTO, schema, module, framework or dependency bag.

### Compatibility and resume

Prefer retaining runtime-authored durable envelope shapes where existing authoritative record contracts require them. New agent content bypasses legacy strict response validation. Runtime metadata and PhaseOutput content are persisted by existing owners.

Read supported historical envelopes only at persistence/recovery boundaries through a bounded adapter. Preserve completed steps, attempts, accepted execution identity, settlement precedence and completed history/commit/push/PR effects. Unsupported or incompatible data takes the existing typed observable recovery path before launch or mutation.

If an old pending plan has executable details only in response text while its selected governed spec lacks them, do not declare readiness, silently convert it, create a new decomposition or replay completed work. Use existing typed planning recovery and identify the selected path and missing artifact readiness for explicit replan/recovery. Children already beyond planning retain their completed records and effects. Any actual authoritative persisted-shape change follows its owning YAML/version/keys/parity path and explicit legacy policy; do not bump unrelated contracts.

### Risks, unknowns and preparation evidence

Bounded risks are lost review approval semantics, stale post-write planning hashes, partial bundle admission, terminal failure overridden by content and completed effects replayed on resume. The subtask's test obligations cover these boundaries. Constructor changes, generated kotlin-inject bindings, overloads, visibility and cross-module compilation remain compiler-only unknowns for the authorized build phase.

Preparation used static source inspection and read-only package checks. The phase-plan runtime wrote the parent, ordered subtask and manifest. The parent session removed duplicated intake prose, restored the 13 actual acceptance criteria and normalized the manifest's subtask path for portability. Read-only goal preflight found a ready manifest with one pending runnable subtask and verdict new_work. No implementation, compilation, build, project tests, installation, commit effects or goal execution form part of the preparation evidence. The user separately authorized committing these spec artifacts to main after preparation.


## Next Path

skill-bill goal SKILL-387
