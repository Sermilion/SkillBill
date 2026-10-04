# AGENTS.md

## Project Context

skill-bill governs authoring, routing, validation, installation, and measurement of agent skills. It ships shared orchestration, tooling, telemetry, workflow state, and shells for review, quality checks, feature work, verification, and PRs.

Non-negotiable contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Generated support pointers, provider-specific native-agent outputs, and install staging artifacts are not committed.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, wrong contract versions, missing content, and missing required sections fail loudly with typed errors.
- Every fallback, degradation, or swallowed failure emits a record; see `docs/observability-policy.md`.

## Internal Skills

`internal-for: <parent>` in `content.md` frontmatter installs as `<skill-name>.md` sidecar in the parent directory (not listed); parent reads the sibling in-session. The only parent is `skill-bill`; pack specialists install as its unlisted sidecars and native-agent inputs, never as slash commands. Contract: `docs/skill-source-generation.md`.

## Active Fix Branch

Until `base/SKILL-380-phase-slot-strategies` is merged, make all fixes on that
branch. Use its worktree when a feature branch has implementation work in progress.

## Product Intent

`/skill-bill` is the only listed skill. Read [Runtime command guidance](docs/runtime-command-guidance.md) before invoking, planning, designing, changing, or reviewing runtime commands and workflows. It owns phase and operation behavior, retired-name compatibility, agent execution, and goal commit/checkpoint rules.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — pack roots for code review and pack `validation_gate` quality-check argv; `addons/` flat pack-owned add-ons. Packs are excluded from goal-planning discovery; eligible `agent/history.md` and `agent/decisions.md` reach planning as a heading catalog only — bodies arrive for headings preplanning selected.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral under `native-agents/agents.yaml` or `native-agents/<name>.md` with `contract_version` on new/rendered sources (older sources still parse for fixture migration). Extra authored guidance goes in `content.md` H2 sections — not sibling org files like `patterns.md` under `skills/<skill>/`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs are the extension surface; routing and install read manifests, not hard-coded platform lists. Canonical shape: `orchestration/contracts/platform-pack-schema.yaml`. Schema changes land there first; `ShellContentLoader.buildPack` rejects malformed manifests via `InvalidManifestSchemaError`. Cross-field rules JSON Schema cannot express live in Kotlin under `x-coherence-checks`.

Per-repo customization: top-level custom fields allowed; runtime-consumed fields use `x-runtime-anchored: true`; non-anchored fields flow to `PlatformManifest.customFields`. Product vs extension: horizontal `skills/skill-bill/` and `.bill-shared` are protected; `platform-packs/<slug>/` (including shipped `kotlin`/`kmp`) are removable — no paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contracts: Draft 2020-12 schema in YAML → Kotlin `*_CONTRACT_VERSION` → parity test → a failure-code entry in the owner's `RuntimeFailureCode` enum, thrown as `SkillBillRuntimeException` → loud-fail at every parse seam. Detail: `runtime-kotlin/ARCHITECTURE.md`.

Schema bumps loud-fail legacy records; runtime quarantines and regenerates in-band. Producer-side gate: feature-task phases owning a bounded planning projection (`preplan`, `plan`, `implement`) re-enter their own fix loop when completed output fails the projection contract.

## Add-ons

Pack-owned files (not skills): flat under `platform-packs/<slug>/addons/`, lowercase kebab-case, resolved only after dominant-stack routing. Declare consumers in the pack manifest (`addon_usage` / `feature_addon_usage`); do not hand-author selection tables in `content.md`. Changes need validator and routing-contract coverage.

## Skill Authoring

Scaffold with `skill-bill new` (or `--payload <file>`). Author via `skill-bill show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` is one content-managed skill at a time. Kinds: `horizontal`, `platform-pack`, `add-on`. Align payloads with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Scaffolding is atomic (validator/manifest/install/link failures roll back).

## Adding Platforms

Code review: pack root + conforming manifest/`content.md`, manifest-registered pointers, README catalog, pack tests, validate. Quality-check: declare `validation_gate` on packs that can win dominant-stack routing; goal build runs the dominant pack's build gate; `skill-bill phase validation` uses the same full project validation strategy as goal validate (optional `declared_quality_check_file` may still parse on leftover custom packs but routing, install, and scaffold do not consume it). Feature-task/verify: stay on horizontal + manifest surfaces — no legacy `skills/<platform>/` overrides.

## Runtime Agent Behavior

Read [Runtime command guidance](docs/runtime-command-guidance.md) for injected agent strategies and build-gate rules.

## Commit Structure (feature-task / goal subtasks)

Read [Runtime command guidance](docs/runtime-command-guidance.md) for one-commit-per-subtask and checkpoint rules.

## Writing And Comments

Write direct, active prose; drop filler, stale phrases, praise, and repetition, but preserve names, numbers, and qualifications. Commits/PRs/docs: lead with the outcome, state what changed and why, and avoid unsupported terms such as "successfully", "perfect", "comprehensive", and "robust". Prefer clear names and small functions over comments; do not add `//` or block comments in scoped Kotlin, and keep KDoc only on interfaces and their members (see Comments).

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `skill-bill operation unit-test-value-check` is the review gate.

## Comments

Authored Kotlin under `runtime-kotlin`, `intellij-plugin`, and
`runtime-kotlin/build-logic` must contain no `//` line comments and no non-KDoc
`/* */` block comments. `/** */` KDoc is allowed only on `interface` declarations
and their members (including nested types inside an interface). Irreducible
why-only rationale belongs in the owning area `agent/decisions.md`, not in source
comments. `CommentAndInterfaceKdocArchitectureTest` enforces this alongside
`PrincipleEnforcementInventory.enforceableRules`.

## Coding Conventions

**Required reading.** Before planning, designing, changing, or reviewing anything under `runtime-kotlin`, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md). It sets the rules that regressed in earlier refactor rounds (A1–A12), the enforcement contract for architecture guards (G1–G7), and the change process (P1–P8): acceptance criteria state end states, fixes delete instead of moving, landed specs are verified, and guards, baselines, and exemptions only tighten. Every runtime-kotlin review runs its section 5 checklist and cites rule IDs.

Before designing, changing, or reviewing `runtime-kotlin`, read and apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles). That section owns requirements for dependency direction, state and resource ownership, persistence, contract enforcement, simplicity, and test value. Its enforcement status distinguishes mechanically checked rules, each paired with its proving test in `PrincipleEnforcementInventory.enforceableRules`, from the requirements that stay review-only. Existing violations do not authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards. Mechanical enforcement lives under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/` (`CommentAndInterfaceKdocArchitectureTest`, `InlineFqnArchitectureTest`, `ProductionFileLineCeilingArchitectureTest`, `PrincipleEnforcementInventory`, and siblings).

**Wire and payload keys.** Never inline contract or wire map keys as string literals in `get`, `put`, `mapOf("key" to …)`, or bracket access at governed payload seams (see `runtime-kotlin/ARCHITECTURE.md` Wire vocabulary). Declare each key once in an owning `*Keys` or `*PayloadKeys` object. A key object lives in `runtime-contracts` only when two or more production modules read it or a `runtime-ports` signature exposes it (`SharedPayloadKeys` for workflow phase-output envelope keys; `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys` for decomposition manifests; area-owned keys such as `ReviewVerificationSignalKeys` beside their contract family; `TelemetryProxyPayloadKeys` for the telemetry proxy wire vocabulary; `LifecycleTelemetryPayloadKeys` for the telemetry envelope). A key object with a single owner lives in that owner's module instead — for example `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli` — and it must not restate a value one of the shared owners already declares. Downstream modules reference those constants; they do not restate wire strings. Enum wire tokens use `wireValue` on the owning enum; do not restate them in local `setOf`/`mapOf` collections. `WireVocabularyArchitectureTest` enforces governed seams via `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation` (`skill-bill phase validation`); it uses the same agent validation strategy as goal validate. The agent discovers and runs all required project checks from repository instructions, build configuration, scripts, and CI, then repairs failures. Compilation alone does not complete validation. Bias: stable base commands, platform depth behind routers, explicit gate argv, validator-backed rules, acceptance and rejection tests.
