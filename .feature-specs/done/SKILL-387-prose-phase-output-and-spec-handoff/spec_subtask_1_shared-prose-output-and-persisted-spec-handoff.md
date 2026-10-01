# SKILL-387 Subtask 1 - shared prose output and persisted spec handoff

Parent spec: [.feature-specs/SKILL-387-prose-phase-output-and-spec-handoff/spec.md](spec.md)
Issue key: SKILL-387

## Scope

Implement the common prose output, phase-decision migration, selected-spec handoff and historical compatibility as one coherent change. Read the parent Investigation, current Design Principles and code-principles.md before design. Reread current source and overlapping SKILL-386/SKILL-388 ownership before edits.

Use existing runtime-domain agent/model/PhaseOutput.kt as the sole agent-content class. Retarget AgentPhaseOutput consumers. Preserve runtime-owned process capture, terminal status and record metadata separately. Do not move filesystem or port-dependent code into domain.

Trace and update accepted-step launch, DefaultPhaseRunner capture, PhaseStepDescription decoding, PhaseOutputGate and settlement hooks, persisted records, FeatureTaskRuntimeHandoffProjectionValueBuilder and downstream briefing. Remove response schema validation, structural repair and formatting relaunch. Extend FeatureTaskPhaseSettlementService and existing directives across agent families without a new completion interface. Keep accepted identity, required persistence, terminal precedence, coupled state, leases/fencing and cleanup.

Retarget preplan, standalone/goal plan, implement/simplify, audit/audit_implement_fix, inline/delegated review, claim verification, verify_findings, implement_fix, agent validation, pack build/validation agent turns and triage/repair, write_history and pr. Remove stuffed digest/plan/receipt JSON requirements. Keep commit_push runtime-only. Semantic interpretation belongs to existing phase decision owners and authoritative ledgers; ambiguity does not manufacture completion or approval.

Change standalone plan to authorized bundle authorship and canonical parent-spec path output. Retain existing preparation writer/store atomicity, manifest readiness and reload responsibilities. Preserve no implementation, branch, workflow or checkpoint behavior. A goal unit writes planned details into its assigned existing subtask spec, retaining scope/acceptance/dependency meaning. Validate the post-write artifact, recompute its hash and persist the matching descriptor/path/preparation through GoalPlanningPreparationCheckpoint before hydration. Adapt goal sweep gates, producer projections, checkpoint/import readers and resume in the same change. No second planning tree, parent overwrite or duplicate decomposition.

Implementation resolves and reads the selected spec through existing repository/spec owners. Retain real-path authorization, manifest readiness, regular-file/readability, required acceptance-list, subtask/dependency selection, provenance and partial-write recovery. Returned path prose alone does not admit implementation.

Adapt review semantic owners together: ReviewClaimVerificationRunner and parseWorkerResult, ReviewClaimVerdictAdmission, CodeReviewStep, InlineReviewEnvelope's response role, VerifyFindingsEvidence, disposition/checkpoint owners, repair parser/coverage and reconciliation consumers. Preserve finding identity, citation/boundary provenance, unresolved blocking, bounded repair policy and exact reviewed target/tree. Audit accepts ordinary prose through its current catalog/progress owner without mandatory response syntax. Validation retains all-check repairs in its original session; build/validation execution receipts remain runtime-owned.

Keep runtime-authored durable shapes where authoritative contracts require them. Historical extraction belongs only at persistence/recovery boundaries. Preserve completed effects and attempt accounting. Legacy pending plans lacking ready selected files use existing typed recovery, without automatic JSON-to-file conversion or hidden replay. Govern any actual persisted-shape change through its owning schema/version/keys/parity and compatibility policy.

Delete response-only codecs, normalization, structural repair, schemas, prompts, ports and bindings after tracing consumers. Keep authoritative artifact/vendor/runtime/telemetry validation. Update changed authored instructions under source-generation rules. Use current injection/scopes, key owners and existing scanners. Add no module, framework, dependency/callback bag, architecture-test class, baseline entry or forbidden source comments.

### Test obligations

- TO-001 ties to AC-001, AC-002 and AC-003. Catch the exact status/summary/value incident or ordinary non-JSON prose being rejected or relaunched because its phase is outside the current prose allowlist. Extend existing attempt/settlement and goal-planning behavioral fixtures with the incident and a parameterized phase-family/route matrix. Assert preserved content, admitted next state or semantic decision and original accepted-session identity without a schema-correction launch.

- TO-002 ties to AC-004, AC-005 and AC-006. Catch implementation consuming duplicate response-plan text, the wrong subtask file or an unready bundle, and hydration using a pre-write hash. Extend existing filesystem preparation and goal-planning fixtures with persisted plan details, post-write hashing and interruption before checkpoint acknowledgement. Assert selected-file details reach implementation, parent/sibling artifacts remain unchanged, and missing, escaped/symlink-escaped, wrong-subtask, partial and acceptance-free artifacts prevent launch.

- TO-003 ties to AC-003, AC-010 and AC-011. Catch prose or zero exit overriding explicit block/failure, cancellation, timeout or process/capture failure, and resume replaying completed effects. Extend existing terminal failure-injection and SQLite recovery fixtures. Assert terminal disposition, accepted identity/attempt accounting, cleanup and no downstream mutation. Reopen supported historical records with completed finalization and assert their effects remain completed. Cover the existing typed incompatible pending-plan path.

- TO-004 ties to AC-007. Catch prose verification approving an unresolved Blocker/Major, refuting it without admitted citation evidence or losing a carried finding omitted by repair. Adapt existing review/disposition/repair suites at ledger and next-state boundaries. Assert confirmed/refuted/unresolved decisions, citation/provenance admission, owed findings and bounded repeated-unresolved behavior. Do not pin prose headings or JSON fields.

- TO-005 ties to AC-008. Catch ordinary prose stating no criteria remain failing audit completion, or changed identities without a smaller remaining set incorrectly permitting another repair. Adapt existing audit decision/progress tests using the current catalog. Assert initial gaps, complete reinspection, clear completion and stalled/ambiguous blocking.

- TO-006 ties to AC-009. Catch formatting restarting validation and losing its repair session, or agent words replacing failed runtime command evidence. Extend existing gate/session fixtures. Assert completion after repairs in the original session, external-obstacle blocking, no formatting restart and refusal to pass absent/failed required execution evidence. Preserve build-only ownership regressions.

Extend existing suites where possible. No test is owed for trivial forwarding, incidental prose, field reflection or counts of stateless instances. Preserve governed parity and regression coverage tied to real bugs.

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

## Non-Goals

- Editing SKILL-386 or SKILL-388 bundles or implementing their separate architecture investigations.
- Adding another module, response DTO/schema, completion interface, callback/dependency bag, authoring framework, planning tree, architecture-test class, baseline entry or forbidden Kotlin comments.
- Removing validation of authoritative manifests, runtime state, process evidence, vendor inputs or wire/telemetry data.
- Changing goal dependencies, phase order, review severity/blocking, audit progress/test scope, validation/build ownership, leases/fencing, accepted-step authority or checkpoint/commit/PR policy.
- Replaying completed work or silently converting historical response plans into new artifact authority.
- Implementing, switching branches, running checks, staging, committing, pushing, opening a PR or executing a goal during this preparation.

## Dependency Notes

Depends on: none
No earlier subtask. SKILL-380's shared slots and SKILL-384's accepted-step/state ownership are landed requirements. SKILL-386 is reserved for the separate CLI investigation; reread overlapping bindings/contracts without changing its bundle. The concurrent SKILL-388 bundle covers typed-parse registrations, diff queries and transport decoding that can overlap response deletions and application review consumers. Sequence after SKILL-388's scanner and application-boundary cleanup, then recheck the surviving inventory and review consumers. This external sequencing adds no predecessor subtask to this manifest. This phase creates or switches no branch. Later authorized fixes must follow AGENTS.md's active base/SKILL-380-phase-slot-strategies worktree rule until that branch merges.

## Validation Strategy

This preparation executes no compilation, build, tests, generators, installation or full checks. Implement and audit assess source facts without claiming later execution evidence. Audit stays read-only under its current production/test scope.

Validate runs required project checks discovered from repository instructions, build configuration, scripts and CI through the selected strategy. Current Kotlin pack full gates are ./gradlew check --continue --parallel -q --warning-mode none and ./gradlew check --continue --no-build-cache --parallel -q --warning-mode none. Agent validation repairs every failure in its original session. Preserve standalone pack validation and command evidence.

Exercise existing engine attempt/settlement, goal planning/hydration, application review/verification, contract compatibility, filesystem/SQLite recovery, CLI and MCP suites for TO-001 through TO-006. Run existing dependency/capability, wire vocabulary, typed parsing, comment/import/clustering/line-ceiling guards and governed parity. Fixture capture/generation belongs only to validate under existing instructions. Preserve regressions and expand no baselines.

Only a selected build phase runs pack build_command and cache_bypassing_build_command, currently ./gradlew compileKotlin and ./gradlew compileKotlin --no-build-cache. Build supplies compile proof without tests/full checks and retains same-session repair. Constructors, visibility and kotlin-inject generation remain compiler-only unknowns until then.

Review changed tests through skill-bill operation unit-test-value-check at the authorized gate. Changed authored instructions/rendering require docs/skill-source-generation.md and parent-owned installation refresh where authorized. Keep installer/uninstall/install-sync commands out of goal-child plans. The preparation runtime owns schema-valid manifest persistence, atomic writing and reload. Local field/type/dependency preflight passed; full local schema preflight was unavailable. tests_executed remains empty.

## Next Path

skill-bill goal SKILL-387

## Spec Path

.feature-specs/SKILL-387-prose-phase-output-and-spec-handoff/spec_subtask_1_shared-prose-output-and-persisted-spec-handoff.md
