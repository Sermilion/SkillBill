# SKILL-403 subtask 1. Automatic Opus 5.5 selection and directive delivery

## Scope

Deliver the complete automatic-selection feature in one reviewable commit on the required implementation base `base/SKILL-380-phase-slot-strategies`. Existing model configuration is the only operator input. This subtask owns effective launch resolution, exact classification, substantive strategy registrations, per-step selection, durable compatibility, cache identity, child propagation, reported identity evidence, private tracking, diagnostics, and their behavioral coverage.

The parent `spec.md` defines product policy, slot and definition matrices, assumptions, non-goals, and validation ownership. This subtask repeats the executable requirements below so no implementation step depends on undisclosed preparation artifacts. The digest is planning evidence; implementation confirms owner details through its own authorized discovery.

Preserve existing unrelated dirty work, especially checkpoint recovery and domain decoding. Planned paths are ownership guidance. Necessary production wiring, test setup, lint, formatting, and later review/validation repairs remain authorized within the feature and architecture constraints. No blanket file or test-body restriction prevents required repairs.

## Acceptance Criteria

1. S1-001, parent AC-002 and AC-003. The existing launch boundary has a typed prelaunch model-resolution operation. Provider remapping and demonstrably pinned alias resolution remain adapter-owned; the launcher consumes the same resolved assignment selection uses. Existing precedence and explicit effort remain intact. One classifier recognizes only exact documented supported Opus 5.5 identities. Older, unknown, unrelated, unversioned, arbitrary deployment, and substring cases remain canonical. Existing resolver and command-builder tests assert precedence, remapping parity, and exact recognition/rejection.
2. S1-002, parent AC-001 and AC-005. Runtime-core registers versioned, substantive Opus variants for AgentPreplanStrategy, AgentPlanStrategy, GoalPlanFanOutStrategy, ImplementThenSimplifyStrategy, AcceptanceAuditStrategy, InlineReviewStrategy, DelegatedReviewStrategy, InlineStandaloneReviewStrategy, DelegatedStandaloneReviewStrategy, PackBuildStrategy, PackValidationStrategy, AgentValidateStrategy, BoundaryHistoryStrategy, and PrDescriptionStrategy. All ten slots have explicit policies, including unchanged RuntimeCommitStrategy. Registry, composition, traversal, and capability-boundary tests assert coverage and preserved definition/mode/gate membership.
3. S1-003, parent AC-004. Typed participating-step assignments select an Opus-capable slot if any participating step qualifies, while only qualifying steps receive Opus directives. Optional-step absence cannot select a profile; selected loop-only repairs retain recorded assignments. Child overrides and supported inheritance remain explicit. Composition and captured-launch tests assert mixed-model audit/repair, selected gate membership, and independently classified child behavior.
4. S1-004, parent AC-006. The canonical execution-plan YAML contract and matching Kotlin constants/codecs/validators contain bounded model/profile inputs and immutable selected strategy identities. A narrow validated historical reader preserves version 0.1 admitted canonical behavior. Reconstruction and compatibility use recorded inputs, and incompatible/corrupt inputs have typed refusal before workflow/parent/child/lease mutation. Existing creation, reconstruction, admission, historical-audit, and schema parity tests assert accepted and rejected records.
5. S1-005, parent AC-004 and AC-007. Plan cache identity copies canonical immutable per-step and relevant selected-child inputs. In-memory plan/review/validation/PR and goal planning use the same resolution/selection semantics without new workflow rows. Cache-isolation and existing phase/goal fixtures assert different assignments cannot reuse the wrong plan and that planning fan-out retains its model/effort assignments.
6. S1-006, parent AC-008 and AC-009. Accepted runner and direct-launch requests deliver substantive Opus instructions only for qualifying assignments, including gate triage/repair, planning units, and delegated review lanes. Existing rejection/correction, repair ordering, caps, checkpoint, and audit-history behavior remains covered. Standalone inline/delegated review remains read-only and report-only, admits both valid verdicts, blocks invalid reports with findings retained, and preserves dirty index/worktree state.
7. S1-007, parent AC-010. Build and validation retain pack-owned command families, receipts, evidence, policies, and budgets. Build remains one session without delegated workers. RuntimeCommitStrategy retains `runtime-commit` and launches no agent. Existing checkpoint recovery uses the preceding accepted step's recorded assignment and retains its two-attempt ledger, persistence-before-launch, index restoration, branch/HEAD checks, and hooks. Effective-policy, gate-progress, commit-cycle, and recovery tests assert these outcomes.
8. S1-008, parent AC-011. Launch evidence distinguishes requested, effective prelaunch, and bounded provider-reported identities, including multiple identities and explicit unavailable actual identity. Documented structured decoder fields supply reported evidence; deviation records use existing observability and cannot reselect an accepted attempt. Existing decoder/launch tests assert fallback evidence, malformed-field handling, and preserved output admission without raw payload persistence.
9. S1-009, parent AC-012. Implementation and simplify guidance use a runtime-supplied, resumable, private run/subtask checklist address. Adapter-owned projection cannot replace authoritative workflow state or mark completion on missing/corrupt/unwritable data. Existing diagnostics record failures, staging retains `../../../.skill-bill/config.yaml` and existing active evidence boundaries, and no default committed TASKS.md exists. Failure and staging tests assert these states.
10. S1-010, parent AC-010 through AC-013. Diagnostics and documentation explain bounded safe model/profile decisions, unknown reasons, fallback limits, and all slot policies. History/PR guidance retains demonstrated evidence, existing formats, and runtime authority. Ownership follows A1-A12, G1-G7, Design Principles, and code principles with no dependency bags, forwarding profile wrappers, second composition root, duplicate wire vocabulary, prohibited comments, unowned state, or wider baselines. Existing architecture guards retain their checks; rationale and prose quality remain review-only where no mechanical guard proves them.

11. S1-011, parent AC-014. `../../../docs/model-specific-phase-strategies.md` contains verified Opus registration/configuration examples and a worked future-model example covering detection, slot variants, mixed-model behavior, authority, immutable admission, diagnostics, and required tests. Its links name the actual production and test owners. Documentation review verifies the examples against landed code; prose wording is not a test contract.

## Implementation requirements

### 1. Establish the effective launch assignment

Extend `../../../runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/agentrun/AgentRunLauncher.kt` through a purpose-built resolution operation on the existing launch boundary. Use typed request/result values in its owning model area. This is an operation needed for prelaunch admission, not a pass-through interface or a new composition root.

Preserve model precedence in `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/core/FeatureTaskRuntimeModelResolver.kt` and `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/config/model/ExecutionMatrixModels.kt`. Explicit phase directives win over matrix assignments; agent phase overrides win over tier defaults. Preserve supplied effort.

Keep provider configuration and command semantics in `../../../runtime-kotlin/runtime-infra/launcher/src/main/kotlin/skillbill/infrastructure/launcher/agentrun/AgentRunCommandBuilders.kt`. Extract the existing remapping mechanism for early adapter resolution and eventual command consumption without engine imports of infrastructure or environment reads. A later environment/config difference must not silently reinterpret the admitted assignment. Refuse incompatible changes through the existing typed admission/recovery seam rather than launching a different profile.

Keep requested identity, effective identity, explicit effort, provenance, and unknown reason separate. Exact supported identity recognition has one owner at the pure selection boundary. Strategies consume a typed profile. Confirm provisional identifiers `claude-opus-5-5` and `anthropic.claude-opus-5-5` against current primary documentation before adding recognition cases. Do not manufacture regional identifiers. Alias `opus` qualifies only if authoritative supported configuration pins it to the exact version. The agent name and unpinned default prove nothing. A requested Opus remapped to `deepseek-v4-flash` stays canonical. Flag-free launch retains unknown if no authoritative default exists.

The source recommendations are [Getting the most out of Opus 5.5](https://claude.dev/blog/getting-the-most-out-of-opus-5-5/). Identifier confirmation uses [Claude model documentation](https://platform.claude.com/docs/en/models/overview); alias/default confirmation uses [Claude Code model configuration](https://code.claude.com/docs/en/model-config). The digest already supplies the article's relevant guidance. Implementation only needs to verify provider facts that define wire recognition and adapter behavior.

### 2. Supply substantive slot variants

Use owning slot behavior and `PhaseStrategy.promptSections`/accepted `runStep` from `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/PhaseStrategy.kt`. Register through `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/featuretask/RuntimeFeatureTaskSlotProvides.kt`. Variants have stable distinct IDs and semantic revisions consistent with existing strategy identity conventions. No class whose only purpose is forwarding qualifies.

Apply outcome-based instructions, phase-specific stopping conditions, work within existing authorization, concise decision explanations, checked delegated evidence, and explicit uncertainty. Remove generic thinking exhortations and never request private reasoning. These are article recommendations. Per-step specialization, private tracking, immutable profiles, and runtime-only commit are project decisions.

- Preplan stops at relevant repository evidence and its bounded projection, naming missing facts.
- Ordinary plan produces executable acceptance-based tasks. Goal plan retains bounded fan-out waves, unit settlement, and evidence checking.
- Implementation completes implement and simplify with their existing different session policies and runtime-private tracking.
- Audit retains read-only inspection, persisted `audit_plan_fix`, ordered `audit_implement_fix`, criterion monotonicity, initial full coverage, later unresolved coverage, receipts, and repair caps. Keep supported AuditPlanningExecutionPlanMapping history.
- Full review retains finding taxonomy, `verify_findings`, authorized `implement_fix`, checkpoints, concrete file/line evidence, and uncertainty. Register both inline and compatible delegated variants while preserving full-run binding to inline for all current review modes.
- Standalone inline/delegated review remains report-only, with read-only specialists, validated findings register, both valid verdicts, retained invalid-output findings, and no staging or repairs.
- Pack build, pack validation, and agent validation retain command family, command evidence, receipts, timeout, retries, and ordinary/triage/repair guidance. Build has one session, no delegated workers, and only pack build commands. Validation remains full project validation.
- History records demonstrated changes, decisions, actual validation, and unresolved facts in its existing format.
- Runtime commit retains the same ID and agent-free accepted binding. It gets an explicit canonical policy, never a forwarding Opus variant.
- PR describes resulting behavior, actual validation, and material unresolved facts, with existing template/readiness and runtime prelaunch push/branch ownership.

Relevant owners include `slot/implementation/ImplementThenSimplifyStrategy.kt`, `ImplementationPromptSections.kt`, `slot/audit/AcceptanceAuditStrategy.kt`, `AcceptanceAuditPromptSections.kt`, `AcceptanceAuditProgress.kt`, `slot/audit/planning/AuditPlanFixPromptSections.kt`, `slot/codereview/InlineReviewPromptSections.kt`, `DelegatedReviewStrategy.kt`, `slot/standalonereview/StandaloneReviewStrategies.kt`, `slot/qualitygate/QualityGatePromptDirectives.kt`, `slot/writehistory/BoundaryHistoryStrategy.kt`, `slot/commitpush/RuntimeCommitStrategy.kt`, and `slot/pullrequest/PrDescriptionStrategy.kt` under the engine featuretask area.

### 3. Integrate selection with immutable admission

Extend `PhaseStrategySelectionFacts` and the existing selection owner in `slot/PhaseStrategySelection.kt` with typed per-step model inputs. Do not add a global enum flag to the existing `values` set. Keep `SkeletonStrategyBindings.kt` responsible for the definition matrix. Determine canonical definition, mode, gate, selected steps, and optional-step rules before resolving participation.

STANDALONE retains shared slots/agent validation/PR. GOAL_CHILD retains its selected build or agent-validation gate. REVIEW retains delegated standalone only for explicit delegated mode, inline for AUTO/INLINE. VALIDATION retains pack validation. PLAN retains preplan/ordinary plan. GOAL_PLANNING retains preplan/fan-out. PR retains runtime commit/PR description. Never activate new full-run delegated routing as a side effect.

A participating Opus step selects a capable slot variant, then only that step's directives specialize. Record selected loop-only repair assignments before admission. Absent optional steps and unselected gate members do not influence profile choice. Contradictory facts and missing required variants fail loudly rather than assembling a partial profile.

Add effective assignments to new-plan creation in `lifecycle/execution/FeatureTaskRuntimeExecutionPlanResolver.kt` and `model/execution/FeatureTaskRuntimeExecutionPlanCreationRequest.kt`. Evolve `../../../orchestration/contracts/feature-task-runtime-execution-plan.yaml` before version constants/wire keys in `runtime-contracts/.../FeatureTaskRuntimeExecutionPlanContract.kt`, codec/decode owners in the execution package, and infrastructure schema/coherence validation. The digest reports current version 0.1. Choose the next supported version under owner conventions and retain an explicit validated historical reader for supported 0.1 plans. Historical absence of profiles means admitted canonical behavior. Unsupported/corrupt records retain evidence and refuse; never regenerate to specialize.

Pure immutable facts belong in domain, including additions to `ResolvedPhaseExecutionPlan.kt`. Preserve them in `withTraversal` and `withEffectivePolicies`. Shared wire keys live in their existing runtime-contracts owner when consumed by multiple production modules or ports; otherwise keep keys with their single owner. Use enum `wireValue`, centralized keys, owner-declared failure codes, parity tests, and loud parse seams. Maintain bounded strings/collections, canonical named ordering, traversal order, and coherence checks.

Update `PhaseStrategyLookup.kt` cache identity, recorded-selection matching, and execution-plan mapping with immutable copied canonical model/profile inputs and relevant child assignments. Include provenance that changes launch meaning, keep effort recorded, exclude secrets and raw payloads. `FeatureTaskRuntimeExecutionPlanCompatibility.kt` reconstructs recorded identities rather than resolving current aliases. Incompatible input refuses before workflow advance, parent/child control, and lease mutation. Accepted strategy identity cannot change during an attempt.

### 4. Carry facts and directives through all execution paths

Use `slot/attempt/PhaseStrategySteps.kt`, accepted `PhaseAgentExecution`, and `slot/runner/DefaultPhaseRunner.kt` as the main delivery boundary. Captured actual launch requests must show guidance, model, and effort together. A unit test of `directiveFor` alone cannot prove delivery.

Update `phaserun/PhaseRunEntry.kt` to use the shared selection semantics without workflow rows. Apply it to plan, review, validation, and PR. Preserve shared run-loop ownership.

Goal planning owners are `../../../runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/planning/state/GoalPlanningRunFacts.kt` and `planning/attempt/GoalPlanningPhaseAttemptGateLaunch.kt`. Replace the empty assignment/null launch overrides with admitted plan-step assignments where supported. Keep compose/briefing order, wave caps, pending-unit chunking, settlement, and binding release. Each child with no explicit override inherits only through the authoritative boundary or remains unknown.

Delegated review model propagation belongs to the application review request/preparation/launch chain, including `../../../runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/review/parallel/runner/ParallelCodeReviewRunnerLaneLaunch.kt`. Explicit native-child settings win. Supported inheriting children consume recorded assignments; unknown/non-Opus children keep canonical directives. Preserve native preflight, governed evidence tools, read-only report contracts, and delegation budgets. No new delegation is authorized for build.

Adapt existing dirty `runloop/durable/CheckpointCommitRepair.kt` at its recorded-assignment seam without reverting user work. Recovery takes the preceding accepted step's effective assignment rather than recomputing current config. Preserve its two-attempt ledger, persistence-before-launch, index restoration, branch/HEAD guards, and hook policy. `commit_push` itself remains agent-free.

### 5. Retain bounded reported identity and observability

Extend launch evidence in `../../../runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/agentrun/model/AgentRunLauncherModels.kt` and documented buffered/streaming decoder handling in `runtime-kotlin/runtime-infra/launcher/src/main/kotlin/skillbill/infrastructure/launcher/agentrun/AgentRunAdapters.kt`. Retain a bounded set of reported identities or explicit unavailable status, separately from requested/effective assignments. Multiple reported models can represent fallback; one terminal identity does not prove the whole session. Do not restore removed token-accounting behavior.

Use documented structured fields only. Malformed identity evidence emits attributed bounded degradation without invalidating otherwise valid ordinary output. Reported changes emit existing records and do not reselect an accepted strategy. Provider-hidden changes remain a documented limit. No billed probe, interactive UI scraping, or raw payload logging.

Existing RuntimeDiagnostics/run observability records include slot, strategy ID/revision, profile, safe identities, reason, seam, expected value, and used value. Ordinary known canonical selection is normal. Unknown resolution, tracking failure, malformed identity evidence, and reported deviations use the existing degradation policy. Bound identity values and redact or omit secrets. Do not create a second telemetry store or log prompts, credentials, endpoints, or provider payloads.

### 6. Add nonauthoritative tracking and close documentation

Use an existing runtime-private `../../../.skill-bill` location for a runtime-supplied run/subtask checklist path. Adapter-owned filesystem access seeds and reconciles the projection from authoritative plan tasks and existing implementation continuation evidence. Tracking cannot advance authoritative state or prove task completion. Missing/corrupt/unwritable projection emits existing degradation and never marks tasks done.

Preserve `lifecycle/checkpoint/FeatureTaskRuntimeCheckpointScope.kt` exclusions, including trackable config, `runloop/state/FeatureTaskRuntimeRunEvidenceOwnership.kt` active-workflow attribution, and domain `FeatureTaskRuntimeRunEvidenceAddress.kt` ownership. Do not widen the entire evidence-directory exclusion or commit default TASKS.md. Confirm exact private address shape against the owner during implementation.

Document operator-visible automatic selection, exact/pinned/unknown identity rules, effective/requested/reported distinctions, immutable resume, fallback limits, mixed-model/child rules, and the runtime-only commit exception. Keep diagnostics bounded and describe actual behavior rather than aspirational guarantees. History and PR guidance use existing formats. Source skills remain content.md; generated SKILL.md and support pointers are not authored or committed.

Maintain `../../../docs/model-specific-phase-strategies.md` as the reusable authoring guide. Replace its planned-work status only after its examples match the landed model resolution and selection APIs. Document supported exact identities and alias rules, configuration precedence, provider remapping, slot/definition registration, per-step directives, child inheritance, runtime-only slots, revision/cache/admission rules, resume refusals, observability, and the behavioral tests future authors must extend. Include an Opus registration/configuration example and a future-model example without adding a manual strategy toggle. Link this guide from the runtime slot architecture and runtime command guidance. This documentation is part of the same subtask, not a later optional task.

## Architecture and evidence ownership

Apply `../../../docs/architecture-guidelines.md` A1-A12 and G1-G7, Design Principles in `runtime-kotlin/ARCHITECTURE.md`, and `docs/code-principles.md`. Runtime review uses the section 5 checklist with rule IDs. Provider I/O stays in adapters, selection in engine, immutable facts in domain, and DI in runtime-core. Avoid dependency bags, pass-through profile wrappers, duplicate model classifiers, duplicate wire strings, second composition roots, and unowned state. No new line/block comments in authored Kotlin; KDoc only on interfaces and their members.

Retain architecture guards for layering, composition-only rules, port declarations, raw-map boundaries, wire vocabulary, typed failures/failure-code totality, ambient effects, package cycles/clustering, comment/KDoc rules, and file ceilings. New guard coverage, only where needed, uses catalog-driven scope, declared build inputs, nonempty discovery, and rejecting synthetic fixtures. Baselines/exemptions cannot widen. Behavioral ownership, smallest necessary abstraction, prose quality, and A2/A5/A11 judgment remain review-only where guards cannot establish them.

Respect the digest's accepted-step authority, one transition owner, admission-before-mutation, report-only ownership, verdict-independent admission, immutable restored plans, separate audit assignments, runtime-only commit, active evidence attribution, and unresolved-criterion progression decisions. Older compile-in-audit and stricter shrinking notes are superseded.

## Test obligations

Each obligation names a realistic regression and an observable boundary. Prefer extending the named existing fixtures; test names below identify owners rather than promise new test files. A small parameter table may cover disjoint recognition or definition cases without separate sibling tests. Do not mirror implementation branches or assert call order in place of outcomes.

| Obligation and criteria | Realistic bug | Observable assertion and existing owner |
| --- | --- | --- |
| T1, S1-001 | An unpinned opus alias or older model specializes, or remapped non-Opus receives Opus guidance. | Resolution/command tests contrast documented exact versions, pinned and unpinned aliases, older/unknown/unrelated values, and nonofficial remapping to deepseek. Builder output matches the resolved assignment, preserving flag-free inheritance and effort. Extend AgentRunCommandBuildersTest and FeatureTaskRuntimeModelResolverTest; pure classifier cases live beside selection. |
| T2, S1-001 | A tier default overrides an explicit phase directive or effort disappears after resolution. | Existing model resolver fixture asserts per-phase/matrix and override/tier precedence with unchanged explicit effort in captured assignment. No duplicate precedence owner. |
| T3, S1-002 and S1-003 | A declared but unselected validate step specializes a build plan, or Opus repair changes non-Opus audit instructions. | Extend PhaseStrategyCompositionTest/TraversalTest with definition, gate, mode, mixed selected repairs, and optional-step cases. Assert selected identities/steps and per-step delivered directives, retaining fan-out and standalone mode. Registration coverage includes all compatible families and canonical runtime commit. |
| T4, S1-005 | Cached plan for one per-phase/child assignment is reused for another. | Lookup tests resolve differing assignments and assert differing profile/strategy/directive results. Mutating caller input after resolution cannot change a recorded/cached plan. Effort/provenance survive where relevant. |
| T5, S1-004 | Resume upgrades a historical canonical plan after current alias changes, or mutates workflow/lease before rejecting a conflicting profile. | Extend FeatureTaskExecutionPlanCreationTest and FeatureTaskAdmittedRunnerReconstructionTest with ExecutionPlanAdmissionFixture. Assert immutable recorded identities and unchanged workflow/lease snapshots on refusal. Retain AuditPlanningExecutionPlanMappingTest historical cases. |
| T6, S1-004 | Decoder accepts oversized/contradictory profile facts or schema/code version drift blocks all new plans. | Extend execution-plan schema tests with valid/rejected fields, bounds, canonical ordering/coherence, historical reader, version parity, and typed loud failures. Preserve FeatureTaskRuntimeExecutionPlanSchemaContractVersionTest and owner failure-code obligations. |
| T7, S1-005 and S1-006 | Direct fan-out, delegated launch, or gate repair drops guidance/model overrides even though directiveFor looks correct. | Capture actual accepted launch requests in DefaultPhaseRunnerTest and existing direct review, goal planning, and gate fixtures. Assert qualifying guidance/model/effort reaches execution and nonqualifying specialists retain canonical instructions. Phase/goal fixtures assert zero new workflow rows. Extend InlineReviewLaunchParityTest, DelegatedReviewRequestTest, and DelegatedReviewRunLoopTest using existing scripted support. |
| T8, S1-006 | Specialized review/audit bypasses invalid output, retry limits, ordered repairs, or standalone dirty-state protection. | Retain existing prompt-retry and audit regression cases, using a qualifying profile only where needed to prove admission remains shared. Existing StandaloneReviewReportAdmissionTest/phase review fixtures assert both valid verdicts, retained invalid findings, unchanged dirty index/worktree, and no repair/staging in inline/delegated paths. |
| T9, S1-007 | Opus gates bypass pack commands or commit launches an extra agent; recovery recomputes a new model after configuration changes. | Extend effective-policy/gate-progress tests and FeatureTaskRuntimeCommitPushCycleTest. Assert unchanged gate evidence/policies, agent-free commit outcomes, and recovery's recorded model plus unchanged ledger budget and safety behavior using its existing fixtures. |
| T10, S1-008 | Decoder labels requested identity actual, discards multiple fallback identities, or a deviation changes admitted strategy. | Existing launcher buffered/streaming and launch-evidence tests assert distinct bounded reported identities, unavailable/malformed cases, normal output preservation, bounded attributed fallback records, and unchanged admitted strategy. |
| T11, S1-009 | Tracking corruption marks unfinished work complete or checkpoint staging includes private tracking and excludes config. | Adapter projection failure fixture asserts authoritative state unchanged with degradation. Existing checkpoint scope/staging fixture asserts private checklist exclusion, trackable config, and unchanged active evidence attribution. |

Existing audit regression owners include FeatureTaskRuntimeAuditFixPlanningTest, FeatureTaskRuntimeAuditAcListRetryTest, FeatureTaskRuntimeAuditProgressRegressionTest, and FeatureTaskRuntimeAuditSessionResumeTest. Preserve their real bug assertions. Do not add tests solely for trivial registration forwarding or prose wording; registration completeness, wire parity, authority guards, and validator rejection remain governed requirements.

## Implementation Details

This is the ordered plan for the single commit. It comes from the preplan digest. Paths are relative to `../../../runtime-kotlin` unless they start with `orchestration/` or `docs/`. Symbols the digest proposed but did not observe are marked "new". Implement confirms each owner's shape before editing it. Only validate runs tests, checks, or `./gradlew`, and only build runs the pack build command. Implement writes the tests listed below but does not execute them. No step runs `./install.sh`, because no skill source, renderer, or support pointer changes.

### Settled decisions

These settle the digest's five open questions and the shape gaps it left.

- **D1, resume drift (adopted).** On resume, the run launches the recorded assignment for the remaining steps. If current configuration resolves differently, it emits a `RuntimeDiagnostics` warning (`seam=execution_plan_resume value_expected=<recorded> value_used=<recorded> current=<resolved>`). It refuses only when the recorded strategy ID or revision is no longer registered, or the recorded record fails validation. The refusal goes through the existing incompatible-descriptor admission error, before any workflow, parent/child, or lease mutation. This satisfies "never silently reinterpret": the drift is recorded, and the profile stays immutable.
- **D2, goal parent matrix (adopted).** `GoalRunnerSubtaskLaunchPrepare` pre-creates the GOAL_CHILD descriptor. It resolves assignments from `ConfigResolutionService.resolveExecutionMatrix()` (injected in its runtime-core provider), using the child's agent resolution and no per-phase directives. That is exactly what the child CLI reproduces, because `goalContinuationCommand` does not forward `--phase-model`. Byte-equality admission stays as it is.
- **D3, environment-pinned `opus` alias (adopted).** The alias qualifies only for launches that inherit the process environment, and only when `ANTHROPIC_DEFAULT_OPUS_MODEL` is an exact recognized ID. Provenance is then `alias_pinned_by_environment`, and the builder passes that exact ID as `--model`. Governed review child launches, whose environment excludes those keys under `CLAUDE_PROVIDER_PASSTHROUGH_KEYS`, resolve as unknown (`environment_not_inherited`). The passthrough keys are not widened.
- **D4, flag-free launches with `ANTHROPIC_MODEL` set (adopted).** These resolve as unknown (`flag_free_default`), because settings and `availableModels` can outrank or substitute the model outside the adapter's view.
- **D5, checkpoint recovery (adjusted).** `CheckpointCommitRepair.kt` is uncommitted work in the base worktree and is absent from this checkout. Implement does not copy or recreate it. Implement routes the checkpoint-recovery agent launch that exists in this checkout, believed to be `runloop/.../FeatureTaskRuntimeRunLoopCheckpointRemediation.kt` (implement confirms), through the preceding accepted step's recorded assignment. Implement also records in its phase output that the base-worktree repair must take the same seam when it lands. If no recovery agent launch exists in this checkout, implement states that in its output, and S1-007's recovery clause is met by keeping the recorded assignment in reach of the remediation seam.
- **D6, assignment schema shape.** Execution plan contract 0.2 adds a top-level `step_launch_assignments` list. Each entry is closed and has these fields:
  - `step_id`;
  - `agent_id`;
  - `requested_model` and `requested_effort`, both nullable;
  - `effective_model`, nullable;
  - `provider_namespace`, one of `anthropic_api`, `google`, `claude_platform_aws`, `bedrock`, `opaque`;
  - `provenance`, one of `requested_exact`, `provider_remapped`, `alias_pinned_by_environment`, `unresolved`;
  - `unknown_reason`, nullable, one of `flag_free_default`, `unpinned_alias`, `opaque_provider`, `environment_not_inherited`, `unsupported_agent`;
  - `profile`, one of `canonical`, `opus-5-5`.

  Model strings use a dedicated bounded token pattern that admits Cursor's `[effort=…]` suffix (implement picks a pattern of the form `^[A-Za-z0-9._:/@=\[\]-]{1,128}$`), with `maxItems` equal to the existing 128-step cap. Entries appear only for agent-launching participating steps, in traversal order. The existing per-slot strategy records carry `strategy_id` and semantic revision. If revision is not already recorded, 0.2 adds it. `x-coherence-checks` require that every `step_id` exists in the traversal, that there are no duplicates, that `profile` equals the classifier result for `(provider_namespace, effective_model)`, and that a slot's strategy is an Opus variant if and only if at least one participating step is `opus-5-5`.
- **D7, descriptor size.** Assumption for implement to confirm: the worst case (32 strategies and 128 assignment entries at maximum token length) fits under `encoded_byte_limit` 65536. If it does not, shorten entry keys, and drop null fields from the encoding if the codec's existing conventions allow it. Never raise the limit.
- **D8, directive resources.** Opus directive text lives at `runtime-engine/src/main/resources/skillbill/engine/featuretask/slot/<slot>/opus-5-5-<family>.md`, loaded by the variant through the existing resource-loading convention its slot uses (implement confirms).
- **D9, checklist format and port.** The checklist lives at `.skill-bill/feature-task-tracking/<FeatureTaskRuntimeRunEvidenceAddress.pathSegment(workflowId)>/checklist.md`. It is Markdown with these parts:
  - a header naming the workflow ID and the accepted plan record digest;
  - one `- [ ] <task-key> <title>` line per task, seeded from the accepted plan record's ordered tasks plus `implementationContinuationFor` segments.

  The agent may tick boxes. The runtime never reads ticks as completion. A new engine-facing port lives in `runtime-ports` (implement picks a name of the form `FeatureTaskImplementationChecklistStore`) and exposes `prepare(workflowId, seed): ChecklistAddress | Degraded`. Its filesystem adapter sits next to `FileSystemFeatureTaskRuntimeSharedEvidenceStore` in `runtime-infra/workflow` and is wired in runtime-core.

### Ordered tasks

1. **Contract 0.2 and historical 0.1 reader** (S1-004, S1-010).
   - **Touches:**
     - `../../../orchestration/contracts/feature-task-runtime-execution-plan.yaml`: bump to 0.2 with the D6 block and coherence checks;
     - a new frozen `../../../orchestration/contracts/feature-task-runtime-execution-plan-0.1.yaml`;
     - `runtime-contracts/.../FeatureTaskRuntimeExecutionPlanContract.kt`: `FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION = "0.2"` plus a previous-version constant;
     - `FeatureTaskRuntimeExecutionPlanKeys` for every new key, and `wireValue` enums for namespace, provenance, reason, and profile;
     - `FeatureTaskRuntimeExecutionPlanSchemaPaths.HISTORICAL_0_1`;
     - `ClasspathPackagedContractInspector` registration;
     - `FeatureTaskRuntimeExecutionPlanSchemaValidator`, which dispatches by version, decodes 0.1 with every step canonical and no assignments, and never upgrades or rewrites it;
     - new owner `RuntimeFailureCode` entries for malformed assignments, coherence violations, and an unsupported historical version, thrown as `SkillBillRuntimeException`.
   - **Tests (T6):** extend `FeatureTaskRuntimeExecutionPlanSchemaContractVersionTest` and the infra schema/coherence tests with these records:
     - valid 0.2;
     - rejected 0.2: oversized token, duplicate or unknown `step_id`, a profile that contradicts the classifier, and an Opus strategy with no Opus step;
     - valid 0.1 read as canonical;
     - an unknown version rejected with a typed code.

     Parity tests cover both schema files.
2. **Domain facts and classifier** (S1-001, S1-003, S1-004).
   - **Touches:** in `runtime-domain`, next to `ResolvedPhaseExecutionPlan`:
     - new `PhaseModelProfile`;
     - new `EffectiveLaunchModel`, holding requested model and effort, effective model, namespace, provenance, and unknown reason;
     - new `StepLaunchAssignment`;
     - a single pure classifier (new, for example `PhaseModelProfileClassifier.classify(namespace, effectiveModel)`) that returns `opus-5-5` only for (`anthropic_api` | `google` | `claude_platform_aws`, `claude-opus-5-5`) or (`bedrock`, `anthropic.claude-opus-5-5`), and `canonical` otherwise;
     - `ResolvedPhaseExecutionPlan` gains immutable per-step assignments, preserved by `withTraversal` and `withEffectivePolicies` through a positional `with…` copy, with `require` that every key is in the traversal.
   - **Constraint:** implement confirms both IDs against the Claude model documentation before adding recognition cases. Regional Bedrock prefixes, Foundry names, `opus`, `opus[1m]`, `opusplan`, `best`, `default`, `claude`, `claude-opus-5`, `claude-opus-4-6`, and substring forms stay canonical.
   - **Tests (T1, classifier half):** one parameter table beside selection covering exact accepts and each rejection family.
3. **Launcher resolution port, adapter, and builder parity** (S1-001).
   - **Touches:**
     - `runtime-ports/.../agentrun/AgentRunLauncher.kt`: a new abstract `resolveLaunchModel(request)` with no default body;
     - request and result DTOs in `AgentRunLauncherModels.kt`, where the request carries agent ID, requested model, effort, and launch environment kind (`inherited` or `governed_child`);
     - `runtime-infra/launcher/.../AgentRunCommandBuilders.kt`: extract `resolveClaudeModelDirective` into one launcher-owned resolution function over the injected `providerEnvironment` (A8), applying remapping first, then the D3 and D4 rules, then the classifier;
     - builders take the resolved value and stop re-reading the environment;
     - Cursor, Codex, and Junie always resolve `opaque` or `unsupported_agent`, and their command shape is unchanged;
     - `FileSystemAgentRunLauncher` implements the port method;
     - `AgentRunService` exposes resolution through its single `override ?: invoked` effective-agent rule;
     - all about five `AgentRunLauncher` test fakes implement the method.
   - **Tests:**
     - T1: extend `AgentRunCommandBuildersTest`. A non-official `ANTHROPIC_BASE_URL` remaps `claude-opus-5-5` to `deepseek-v4-flash`, which resolves canonical, and `--model` equals the resolved effective model. Pinned and unpinned `opus`, the governed-child environment as unknown, and flag-free inheritance with effort preserved are covered too.
     - T2: extend `FeatureTaskRuntimeModelResolverTest` for per-phase over matrix and override over tier, with effort unchanged in the captured assignment.
4. **Reported identity evidence** (S1-008).
   - **Touches:**
     - `AgentRunLaunchFacts` gains a reported-models value: `Reported(set ≤ 8 bounded tokens)` or `Unavailable(reason)`, where the reason is `not_reported`, `malformed`, or `unsupported_agent`;
     - `decodeClaudeJson` and `decodeClaudeStreamJson` in `AgentRunAdapters.kt` read the `modelUsage` keys of the result message for identity only, never tokens or cost;
     - a malformed field yields `Unavailable(malformed)` plus a degradation record and leaves the result text admitted;
     - Codex, Cursor, and Junie report `Unavailable(unsupported_agent)`;
     - nothing raw is persisted, and `launchedModel`/`launchedEffort` stay paired from the recorded effective model.
   - **Tests (T10):** the decoder tests next to `AgentRunAdapters.kt` cover:
     - two reported models after a fallback;
     - a missing field reported as unavailable;
     - a malformed field that degrades while the output is still admitted;
     - a deviation from the effective model emitting a warning while the admitted strategy and output stay unchanged.
5. **Typed selection, bindings, cache, compatibility, creation** (S1-002, S1-003, S1-004, S1-005).
   - **Touches:**
     - `slot/PhaseStrategySelection.kt`: `PhaseStrategySelectionFacts` gains a typed per-step assignment map, not an enum in `values`;
     - a new profile-aware `PhaseStrategyBinding` form next to `Fixed` and `ByFact`, mapping `(slot, profile)` to a strategy ID after definition, mode, gate, selected steps, and optional participation resolve;
     - `SkeletonStrategyBindings.kt` keeps the definition matrix, with full-run CODE_REVIEW still inline for every mode;
     - `PhaseStrategyLookup.PlanKey` includes a defensive immutable copy of the per-step assignments and the selected child assignments, including provenance;
     - `matchesRecordedSelection` and `FeatureTaskRuntimeExecutionPlanCompatibility.requireSupportedComposition` re-derive facts from recorded assignments, applying D1;
     - `lifecycle/execution/FeatureTaskRuntimeExecutionPlanResolver.resolveCreation` and `FeatureTaskRuntimeExecutionPlanCreationRequest` take the assignments;
     - the plan mapping copies them;
     - missing variant registrations or contradictory facts fail loudly before mutation;
     - selected loop-only repair steps (`audit_plan_fix`, `audit_implement_fix`, `implement_fix`) get assignments at creation.
   - **Tests:**
     - T3: `PhaseStrategyCompositionTest` and `PhaseStrategyTraversalTest` table rows cover:
       - a GOAL_CHILD build gate with an unselected Opus `validate` that stays canonical;
       - a mixed audit (non-Opus `audit`, Opus `audit_implement_fix`) that selects the variant, with only the repair step specialized;
       - an absent optional step;
       - REVIEW delegated versus inline;
       - GOAL_PLANNING fan-out retained.
     - T4: lookup cache isolation for two assignments, plus mutation of the caller's map after resolution.
     - T5: extend `FeatureTaskExecutionPlanCreationTest` and `FeatureTaskAdmittedRunnerReconstructionTest` with `ExecutionPlanAdmissionFixture`. A 0.1 plan stays canonical after the current config changes to Opus, and a conflicting or unregistered profile refuses with workflow and lease snapshots unchanged. Keep `AuditPlanningExecutionPlanMappingTest` historical cases.
6. **Substantive variants and registration** (S1-002, S1-006, S1-007, S1-010).
   - **Touches:** the 14 new variant classes, one per listed family, each in its slot package (for example `slot/preplan/AgentPreplanOpus55Strategy.kt`). Each:
     - has a distinct stable ID `<canonical-id>-opus-5-5` and revision 1;
     - composes its canonical strategy the way `GoalPlanFanOutStrategy` composes `AgentPlanStrategy`;
     - overrides `promptSections` to append the D8 directive only when the step's recorded profile is `opus-5-5`, branching on the profile, never on phase IDs or `SkeletonDefinition`, per `FeatureTaskSlotBoundaryScans`;
     - keeps steps, policies, loops, validators, and gate command families.

     The other touches:
     - quality-gate variants carry the directive into ordinary, triage, and repair turns through `QualityGatePromptDirectives`;
     - `RuntimeCommitStrategy` gets an explicit canonical-only binding;
     - registration goes in `runtime-core/.../RuntimeFeatureTaskSlotProvides.kt` and the bindings in `SkeletonStrategyBindings.kt`, both under 1200 lines;
     - the duplicate in `TestPhaseStrategies.kt` is updated.
   - **Directive content:** follows the parent slot matrix: outcome-based goals, slot-specific stopping conditions, work within existing authorization, checked delegated evidence, concise decision explanations, and explicit uncertainty. It contains no thinking exhortations, no request for private reasoning, no compile or test proof in authoring phases, and no severity change.
   - **Tests:** `PhaseStrategyRegistryTest` checks that all 14 variants are registered with distinct IDs and that runtime commit is canonical-only. `StrategyCapabilityBoundaryArchitectureTest` must discover the new files, so its discovery count grows and no exemption is added.
7. **Delivery through every launch path** (S1-003, S1-005, S1-006, S1-007).
   - **Touches:**
     - `runloop/core/FeatureTaskRuntimeRunLoopPlanningBranch.kt`: `buildPhaseRun` (line 81) and `capExhaustionPhaseRun` (line 145) take the recorded assignment instead of calling `FeatureTaskRuntimeModelResolver`. The resolver is then used only at creation.
     - `FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective` keeps Cursor's effort merge from the recorded value.
     - `PhaseLaunchPreparation.composeLaunchPromptInputs` adds the step profile to `FeatureTaskRuntimePhasePromptComposeInputs`.
     - `phaserun/PhaseRunEntry.kt` builds its in-memory plan from the same resolution and selection, with no workflow rows.
     - `GoalPlanningRunFacts` and `GoalPlanningPhaseAttemptGateLaunch` replace null overrides with the plan-step assignment when a model is configured, so every fan-out unit carries that model and effort. They stay canonical when no model is configured.
     - `GoalRunnerSubtaskLaunchPrepare` applies D2.
     - `ParallelCodeReviewRunnerLaneLaunch` passes the recorded code-review assignment to `launchParentLane` instead of `modelOverride = null`. Explicit native-child overrides win, and inheriting children are classified independently (unknown under D3).
     - The checkpoint-recovery launch applies D5.
   - **Tests:**
     - T7: extend `DefaultPhaseRunnerTest`, `InlineReviewLaunchParityTest`, `DelegatedReviewRequestTest`, `DelegatedReviewRunLoopTest`, and the goal-planning and gate fixtures. The captured accepted request carries directive, model, and effort together for a qualifying step and the canonical text for a non-qualifying one. Phase and goal fixtures assert zero new workflow rows.
     - T8: keep the existing retry, audit, and standalone admission regressions (`StandaloneReviewReportAdmissionTest`, plus the audit owners above), adding a qualifying profile to one standalone case to prove admission, dirty-state preservation, and both verdicts stay shared.
     - T9: extend the effective-policy and gate-progress tests, `FeatureTaskRuntimeCommitPushCycleTest`, and the recovery fixture. Recovery launches with the recorded model after a config change, and its ledger, budget, and safety outcomes are unchanged.
8. **Diagnostics** (S1-008, S1-010).
   - **Touches:**
     - one engine-owned emission at plan creation through `RuntimeDiagnostics`, carrying slot, strategy ID and revision, profile, bounded safe identities, and the reason;
     - known canonical selection logs at info level;
     - unknown resolution, D1 drift, malformed reported identity, reported deviation, and checklist failure use the existing degradation format `seam=… value_expected=… value_used=…`;
     - model strings are passed through the token pattern or replaced by `<redacted:length>`;
     - no prompts, URLs, or credentials are logged.
   - **Tests:** covered by T7 and T10 assertions. No separate test for emission glue.
9. **Runtime-private checklist** (S1-009).
   - **Touches:**
     - the D9 port, adapter, and runtime-core wiring;
     - the implementation strategy and its Opus variant pass the address into implement and simplify prompts through `ImplementationPromptSections`. Simplify gets read and reconcile guidance only, matching its session policy;
     - when the store is missing, corrupt, or unwritable, the adapter emits degradation, re-seeds, never marks done, and leaves workflow state untouched;
     - `FeatureTaskRuntimeCheckpointScope.isRuntimePrivatePath` and `../../../.gitignore` already cover the path, so neither changes;
     - no `TASKS.md`.
   - **Tests (T11):** an adapter failure fixture with corrupt and unwritable cases, where authoritative state is unchanged and degradation is recorded. The existing checkpoint scope fixture gains a checklist path row (excluded) next to `config.yaml` (trackable).
10. **Documentation** (S1-010, S1-011).
    - **Touches:**
      - `../../../docs/model-specific-phase-strategies.md`: replace the planned status with the landed rule, an Opus registration and configuration example, and a worked future-model example. The example covers classifier case, variant classes, binding row, directive resources, contract tokens, diagnostics, and the tests to extend. It links the real production and test owners named above.
      - `../../../runtime-kotlin/ARCHITECTURE.md` § "Phase slots and strategies" gets a link and summary.
      - `../../../docs/runtime-command-guidance.md` gets the launch-behavior notes: immutable resume (D1), the pinned-alias rule (D3), and the runtime-only commit exception.
      - Record the D1, D3, and D5 rationale in the owning `../../../agent/decisions.md` only if the history phase does not.
    - **Tests:** none. This is review-only.

### Constraints carried into implement

- A1, A2, A5, A6, A7, A8, and G7 apply as follows:
  - the domain stays pure;
  - the port has no default body;
  - the engine imports no infrastructure;
  - there is one classifier and one effective-agent owner;
  - there are no forwarding-only variants;
  - keys live only in `FeatureTaskRuntimeExecutionPlanKeys`, and enums use `wireValue`;
  - failures are typed;
  - the environment is injected;
  - no new exemptions or wider baselines.
- No `//` comments in authored Kotlin, and KDoc only on interfaces.
- File ceiling 1200 lines.
- Preserve unrelated dirty work, and do not import base-worktree uncommitted files.
- Do not activate delegated full-run routing, change effort, add fast mode, or add a strategy flag.

## Dependency notes and assumptions

There are no preceding subtasks. All dependencies are existing owners named by preplan. This feature does not need parallel work or newly activated delegation. The implementation worker confirms exact source shapes through its own preplan/authorized discovery instead of treating proposed symbols as guaranteed existing code.

The implementation base is explicitly required. The manifest records it in `base_branch` because no repository default branch was supplied. It is an implementation-base override, not a guessed default. Preserve current unrelated dirty edits and apply integrations around them.

Provider documents may change. Only verified exact supported identifiers enter recognition; unresolved aliases/defaults remain unknown. Concrete descriptor version/bounds follow the owner's conventions, with schema parity and rejecting tests. The historical reader, compatible adapters' inheritance, and private checklist address need implementation confirmation. Their decisions are settled here: historical plans never gain profiles, unsupported inheritance stays unknown, authoritative conflict refuses before mutation, and checklist failures degrade tracking only. No user clarification is required.

## Non-goals

No standalone command, strategy toggle, automatic model upgrade, paid fast mode, effort/cost tuning, platform-specific hard-coded strategy routing, global rewrite of user instructions, changed findings taxonomy/severity, new full-run delegated routing, checklist replacement of workflow state, billed model probe, UI scraping, second telemetry system, or source comments prohibited by AGENTS.md. Do not regenerate old plans to obtain specialization or author generated install outputs.

## Validation Strategy

Implementation and audit author/inspect repository end states only. They do not run tests, compile, build, generators, full checks, or install as proof. Required validation execution is deferred to validate; build commands stay with build. This corrects the digest's suggestion to run repository validation during implementation and preserves the supplied phase authority. Receipts produced outside validate leave tests_executed empty.

Validate discovers and executes the repository-required pack collect-all validation command and checks required by CI, including runtime Gradle checks, strict agnix validation, and scripts/validate_agent_configs. Compilation alone does not satisfy validation. The exact commands come from the manifest/build/CI owners during that phase, not invented argv in this plan. It executes the relevant existing behavioral, schema, and architecture coverage and repairs required defects within these constraints.

Review covers the branch diff, answers the architecture section 5 checklist with A1-A12/G1-G7 IDs, and inspects all substantive variants and model/profile owner boundaries. Audit retains full initial per-criterion coverage and later unresolved coverage. Do not create a parallel test harness, remove past-bug tests, weaken parity/validator rules, or widen guards/baselines/exemptions.

Install refresh belongs to the owner-authorized later phase or parent runtime and is conditional on changes to authored skills, rendering, or support pointers. Engine prompt changes alone do not require it. History, commit/push, and PR execution stay with those phases; their artifacts are not implement/audit acceptance criteria. New checkout feature contracts do not replace this run's installed output/settlement contract.

## Next path

The runtime executes this single subtask through its owned loop and commits the coherent change under its one-commit-per-subtask rules. The parent and manifest are `spec.md` and ``. Future entry is `skill-bill goal SKILL-403`; this planning invocation does not execute it or continue another workflow.
