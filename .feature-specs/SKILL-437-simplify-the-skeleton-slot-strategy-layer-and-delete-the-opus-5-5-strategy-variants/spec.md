# SKILL-437 Simplify phase strategies and preserve recorded execution

## Mode

decomposed

## Intended outcome

Keep immutable execution plans and accepted-step authority while deleting model-specific strategy identities, unreachable registrations, runtime binding downcasts and optional members from the strategy core. Close the monitor publication defect before removing its wrapper. The supplied preplan digest is the sole repository evidence for this bundle.

## Scope

Six dependency-ordered subtasks implement R4, R1/R2/R3 together, R5, R6, R7 and R8. Each is a coherent production change with its own later runtime-owned review, validation and commit. This parent replaces the duplicated runtime-seeded intake, rather than an operator-authored spec.

The affected owners are engine featuretask slots, accepted binding construction, prompt composition, recorded-plan lookup and compatibility, core DI, the domain profile suffix declaration and the named documentation. File paths in subtasks are an evidence-based starting point, not a prohibition on necessary wiring, test setup, formatting or lint repairs. Preserve existing workspace changes, particularly FeatureTaskRuntimeRunLoopStepBindings.kt. Do not modify the unrelated SKILL-433 bundle.

## Acceptance Criteria

1. Model-specific behavior is a default-on prompt supplement driven by recorded step profiles, with canonical strategy identities and durable aliases that preserve historical execution-plan admission and resume semantics.
2. Monitor repair completion retains the publication hook that commits and pushes a completed repair before CI is checked again, including an opus-5-5 launch assignment.
3. The production registry contains exactly the live selectable canonical strategies, with one registration list and one shared stateless DefaultPhaseRunner across registrations.
4. Strategy-owned typed binding families preserve accepted-step authority, and PhaseStrategy has the twelve-member core with optional behavior on capability interfaces.
5. Model extension and strategy architecture documentation describes the new ownership and compatibility rules, with decisions superseding SKILL-403 and declining delegated full-run review.

## Requirement coverage

| Intake requirement or outcome | Owning subtask |
| --- | --- |
| R4, AC4, immediate defect closure and temporary forwarding proof | 1 |
| R1, R2, R3, AC1 removal portion, AC2, AC3 | 2 |
| R5, AC1 registration portion, AC7 | 3 |
| R6, AC5, AC8 authority enforcement | 4 |
| R7, AC6, continued AC8 enforcement | 5 |
| R8, AC9 | 6 |

Every subtask retains the immutable plan, codec, admission, digest, architecture and behavior constraints. AC8's guard execution is validation work, not a production acceptance criterion.

## Decisions and assumptions

The digest settles the following conflicts. These are explicit planning deviations from literal intake wording, not claims that those words were satisfied.

- AC1's literal total of twelve conflicts with the seventeen live selectable canonical registrations established by preplan. Use the twelve base registrations plus five live standalone-review, standalone-monitor and QA registrations, for seventeen overall. Preserve supported definitions and the universal reachability invariant. Remove the also-unreachable AgentPlanStrategy registry entry, but retain its class as PlanFanOutStrategy's helper. Implement confirms this evidence against live selection; it must not delete live behavior or claim twelve to force a count.
- R4's blanket equality cannot hold for deliberately different strategy IDs, supplemented prompts or identity strings. Compare every open member against the wrapper's actual composed canonical instance with explicit exceptions for those intentional differences. Compare stable hooks, loop rules and resume rules by identity. Verdict factories receive the same diagnostics and are compared by observable decision behavior where fresh instances make identity impossible. Do not change production diagnostic ownership merely to make a test use identity. Keep the exceptions explicit and bounded; new open members must demand a comparison rule.
- AC3 names an infra contract test that cannot depend on engine admission. Put immutable byte decode and coherence proof in the named infra owner and admission/resume proof in engine admission coverage. Do not introduce a reverse module dependency.
- Keep the twelve named core members exactly. Hooks become a typed optional capability. Policy and resume identity calculations become non-overridable helpers with explicit recorded-alias interpretation.
- Use a strategy-owned typed per-step execution descriptor to capture the family, hooks and PhaseStrategy<B> execution together. A generic runtime operation opens that capture and constructs only its B. Multi-role strategies compose distinct typed step executions rather than receiving a union of capabilities. Registry heterogeneity never licenses unchecked casts.
- Resolve SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS at core DI into one immutable setting shared by durable and in-memory launch factories. Omission and true enable; false disables. Invalid values must fail through the existing typed configuration/diagnostics policy, with no silent fallback. Implement confirms the existing error owner before choosing the concrete error type. Slot and composer code never read ambient environment.
- The digest does not establish the repository default branch. The manifest assumes main. Implement confirms it through its authorized discovery before any later branch action. The feature branch is feat/SKILL-437-simplify-the-skeleton-slot-strategy-layer-and-delete-the-opus-5-5-strategy-variants. This planning phase does not inspect git.
- No durable field change is needed. Preserve execution-plan contract 0.2. An unavoidable field change requires schema-first evolution, parity coverage and a historical reader, never an opportunistic workflow rewrite.

## Constraints

Preserve resolved plans, recorded launch assignments, participating-step filtering, assignment-sensitive caching, admission and descriptor checks, semantic revisions, policy/resume identities and digest verification. Never reselect a recorded plan from current configuration or rewrite workflow rows to canonicalize identities. Alias interpretation is separate from phase-output migration and profile classification.

Follow the architecture and design rules summarized in preplan. Later implementation must read docs/architecture-guidelines.md, runtime-kotlin/ARCHITECTURE.md Design Principles and docs/code-principles.md before touching runtime code. Apply A1/A2 dependency ownership, A3 composition ownership, A5 deletion rather than relocation, A6 owning wire keys, A7 typed refusal and recorded degradation, A8 injected environment and the remaining section 5 review checklist. No new Kotlin line or block comments, KDoc only on interfaces and their members. No new architecture baseline or exemption rows. StrategyCapabilitySourceCatalog must shrink or remain unchanged; retain its parser and unrelated writer protections.

## Non-goals

Do not move slot/attempt or slot/state into runloop, rename hooks, move SkeletonStrategyBindings into definitions, add a strategy revision migration table or move review members off PhaseRunState. Do not change directive bytes, classifier model identities or launched-model telemetry columns. Desktop, IDE and CLI changes are limited to necessary compile fixes. Preserve delegated standalone review while deleting delegated full-run review.

## Dependency notes

Subtasks form the strict chain 1 -> 2 -> 3 -> 4 -> 5 -> 6. The first fix remains independently useful if later work slips. Wrapper deletion, central supplements and aliases must land together. Preserve pre-change archival bytes while the old strategies still exist, before the removal subtask. Never canonicalize archived descriptor bytes or regenerate them from the replacement registry.

## Test Obligations

Each subtask names a realistic bug and the narrow boundary coverage that earns its cost. Retain existing coverage for real publication, persistence, authority and recovery bugs. Keep governed parity and synthetic guard rejection coverage. Prefer one strong scenario per branch, table-driven where it avoids duplicate setup. No test-only subtask is planned.

## Validation Strategy

This phase executes no builds, tests or generators. tests_executed is empty. Implement authors repository changes and audit inspects them. Only build owns the pack build command. Only validate runs the full pack validation gate and all required project checks discovered from repository guidance, build configuration and CI. Validation also owns test execution, fixture-generation commands and their evidence. Do not substitute compilation for full validation. Runtime-owned review, history, commit, push, PR and monitor actions remain with their phases. Acceptance criteria require readable repository end states, never a later phase receipt.

Later validate must cover the focused obligations in all six specs and the existing repoTest architecture guards without relaxing baselines, exemptions, assertions or typed boundaries. Necessary repairs remain in scope across affected wiring and test setup.

## Next path

The runtime next plans subtask execution through plan_subtasks. This document does not supply Implementation Details or launch another workflow. The prepared goal reference is `skill-bill goal SKILL-437`; only the parent runtime decides when to use it.
