# AGENTS.md

## Project Context

skill-bill governs skill authoring, routing, validation, installation, and measurement. It owns orchestration, telemetry, workflow state, and task shells.

Non-negotiable contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Generated support pointers, provider-specific native-agent outputs, and install staging artifacts are not committed.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, content or required sections, and wrong contract versions fail loudly with typed errors.
- Record every fallback, degradation, or swallowed failure; see `docs/observability-policy.md`.

## Internal Skills

`internal-for: <parent>` in `content.md` installs an unlisted `<skill-name>.md` sidecar read by its parent in-session. Only `skill-bill` is a parent. Pack specialists are its unlisted sidecars and native-agent inputs, never slash commands. Contract: `docs/skill-source-generation.md`.

## Worktree ownership

Each goal owns a runtime worktree at `<state>/goals/<repo>/<goal>/checkout`, removed after recorded completion unless it holds unpublished work. Standalone phases use the caller's checkout. A desktop branch start uses that branch's checkout or a runtime-created sibling, removed after its last run.

## Product Intent

`/skill-bill` is the only listed skill. Read [Runtime command guidance](docs/runtime-command-guidance.md) before invoking, planning, designing, changing, or reviewing runtime commands and workflows. It owns phases, operations, retired-name compatibility, agent execution, and goal commits/checkpoints.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — pack roots for code review and pack `validation_gate` quality-check argv; `addons/` flat pack-owned add-ons. Planning excludes packs. Eligible `agent/history.md`/`agent/decisions.md` supply headings only; bodies arrive for preplanning-selected headings.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral in `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources require `contract_version`; older sources parse for fixture migration. Put extra guidance in `content.md` H2 sections, never sibling org files such as `patterns.md`.

Run `./install.sh` after source-skill, renderer, or support-pointer changes.

## Platform Packs

Packs extend the product; routing and install read manifests, not hard-coded lists. Schema: `orchestration/contracts/platform-pack-schema.yaml`. Schema changes land there first; `ShellContentLoader.buildPack` rejects malformed manifests via `InvalidManifestSchemaError`. Kotlin owns cross-field rules under `x-coherence-checks` when JSON Schema cannot express them.

Per-repo customization: top-level custom fields allowed; runtime-consumed fields use `x-runtime-anchored: true`; non-anchored fields flow to `PlatformManifest.customFields`. Horizontal `skills/skill-bill/` and `.bill-shared` are protected. All `platform-packs/<slug>/`, including shipped `kotlin`/`kmp`, are removable. No paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contracts require Draft 2020-12 YAML → Kotlin `*_CONTRACT_VERSION` → parity test → a failure-code entry in the owner's `RuntimeFailureCode` enum, thrown as `SkillBillRuntimeException` → loud-fail at every parse seam. Detail: `runtime-kotlin/ARCHITECTURE.md`.

Schema bumps loud-fail legacy records; runtime quarantines and regenerates in-band. Phases with bounded planning projections, `preplan`, `plan`, and `implement`, re-enter their own fix loop when completed output violates the projection contract.

## Add-ons

Pack-owned files, not skills, live flat under `platform-packs/<slug>/addons/`, lowercase kebab-case, resolved only after dominant-stack routing. Declare consumers in the pack manifest (`addon_usage` / `feature_addon_usage`); do not hand-author selection tables in `content.md`. Changes need validator and routing-contract coverage.

## Skill Authoring

Scaffold with `skill-bill new` or `--payload <file>`. Author via `skill-bill show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` is one content-managed skill at a time. Kinds: `horizontal`, `platform-pack`, `add-on`. Align payloads with `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Scaffolding rolls back on validator/manifest/install/link failure.

## Adding Platforms

Code review: pack root + conforming manifest/`content.md`, manifest-registered pointers, README catalog, pack tests, validate. Quality-check: packs eligible for dominant-stack routing declare `validation_gate`. Goal build runs that pack's build gate. `skill-bill phase validation` and goal validate share full project validation. Legacy custom packs may parse `declared_quality_check_file`; routing, install, and scaffold do not consume it. Feature-task/verify: stay on horizontal + manifest surfaces — no legacy `skills/<platform>/` overrides.

## Runtime Agent Behavior And Commits

[Runtime command guidance](docs/runtime-command-guidance.md) owns injected agent strategies, build gates, one-commit-per-subtask, and checkpoint rules.

## Writing And Comments

Write direct, active prose. Cut filler, stale wording, praise and repetition; preserve names, numbers and qualifications. Commits/PRs/docs lead with the outcome, what changed and why. Avoid unsupported claims such as "successfully", "perfect", "comprehensive", and "robust". Prefer clear names and small functions. Follow Comments for Kotlin.

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `skill-bill operation unit-test-value-check` is the review gate.

Tests cannot depend on wall-clock time or thread scheduling. Ban real sleeps (`Thread.sleep`, shell `sleep N`) racing timeouts, idle windows or poll intervals; elapsed-time or poll-count assertions; real-time deadline polling; latch or join timeouts; and real `delay` outside `runTest`. Loaded CI runners make these flaky. Never widen sleeps or deadlines. Inject a clock and scheduler (fake `Clock`, `runTest` virtual time or code-signalled latches) to control every transition. Leave real-time-only behavior untested.

## Desktop QA

Desktop refuses a runtime from another commit. Dirty builds also require matching `SKILL_BILL_INSTALL_RUN_ID`. Never use the operator's installed runtime for QA. Run these sequentially from the QA checkout's `desktop/` with one exported run id. Combining both Gradle tasks races: `:app:run` may read the runtime record before `installDist` writes it.

```sh
export SKILL_BILL_INSTALL_RUN_ID=qa-<invocation id>
./gradlew :runtime-kotlin:runtime-cli:installDist
SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db ../runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli stats
SKILL_BILL_RUNTIME_DIR=$PWD/../runtime-kotlin/runtime-cli/build/install SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db JAVA_TOOL_OPTIONS="-Duser.home=<dir>/home -Djava.io.tmpdir=<dir>/tmp" ./gradlew :app:run
```

`stats` creates the isolated store; otherwise the board shows "The selected database does not exist". Use `SKILL_BILL_REVIEW_DB`; `SKILL_BILL_DB` does nothing. An isolated home correctly shows "Not ready: no agent found" on New task.

## Comments

Authored Kotlin under `runtime-kotlin` and
`runtime-kotlin/build-logic` must contain no `//` line comments and no non-KDoc
`/* */` block comments. `/** */` KDoc is allowed only on `interface` declarations
and their members (including nested types inside an interface). Irreducible
why-only rationale belongs in the owning area `agent/decisions.md`, not in source
comments. `CommentAndInterfaceKdocArchitectureTest` enforces this alongside
`PrincipleEnforcementInventory.enforceableRules`.

## Coding Conventions

Before planning, designing, changing, or reviewing `runtime-kotlin`, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md). Apply A1–A12, G1–G7, and P1–P8. Every runtime-kotlin review runs its section 5 checklist and cites rule IDs.

Before designing, changing, or reviewing `runtime-kotlin`, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles). It governs dependencies, state/resource ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs mechanical rules with tests; others require review. Existing violations never authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards. Guards live in `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** At governed payload seams, use owning `*Keys`/`*PayloadKeys` constants in `get`, `put`, `mapOf`, and brackets, not wire string literals. See `runtime-kotlin/ARCHITECTURE.md` Wire vocabulary. Declare each key once. Use `runtime-contracts` only for keys read by multiple production modules or exposed by `runtime-ports`. Owners: `SharedPayloadKeys`, workflow phase-output envelopes; `DecompositionManifestPayloadKeys`/`DecompositionPlanningPayloadKeys`, decomposition; `ReviewVerificationSignalKeys`, its contract family; `TelemetryProxyPayloadKeys`, telemetry proxy; `LifecycleTelemetryPayloadKeys`, telemetry envelope. Single-owner keys stay in that module, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. Never redeclare shared values. Downstream modules use the constants. Use enum `wireValue`; do not repeat tokens in local `setOf`/`mapOf` collections. `WireVocabularyArchitectureTest` enforces governed seams via `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation`, CLI `skill-bill phase validation`, with the same agent strategy as goal validate. Discover and run all required checks from repository instructions, build configuration, scripts, and CI. Repair failures. Compilation alone is insufficient. Bias: stable base commands, platform depth behind routers, explicit gate argv, validator-backed rules, acceptance and rejection tests.
