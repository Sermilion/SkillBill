# AGENTS.md

## Project Context

skill-bill owns skill authoring, routing, validation, installation, measurement, orchestration, telemetry, workflow state, and shells.

Required contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Keep generated support pointers, provider-specific native-agent outputs, and staging artifacts uncommitted.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, content or required sections, and wrong contract versions fail loudly with typed errors.
- Record every fallback, degradation, and swallowed failure; see `docs/observability-policy.md`.

## Runtime Contract Schemas

Every `orchestration/contracts/` YAML is a runtime contract. New contracts need Draft 2020-12 YAML, Kotlin `*_CONTRACT_VERSION`, a parity test, an owner `RuntimeFailureCode` thrown as `SkillBillRuntimeException`, and loud failure at every parse seam. See `runtime-kotlin/ARCHITECTURE.md`.

Every schema change needs a persisted-data migration path. Prove compatible changes remain readable; give explicit steps for version bumps or incompatible changes. Validate source and migrated records. Apply coupled updates atomically and repeatably. Preserve run identity, completed work, commits, checkpoints, and history. Test the previous supported version and retain migration steps for older supported records.

Installations and upgrades must preserve all runs and persisted user and runtime data. Migrate before reading or resuming data. On failure, keep the original data and a usable recovery path. Never reset or replace data with defaults. Verify upgrades on populated previous-version stores, including interrupted migrations and repeated installs.

Run migrations before strict current-version validation or board/status projection. Keep unsupported or corrupt records visible with original data, a typed error, and recovery guidance. A schema mismatch must not hide runs, reset completed work, or replace migration with regenerated evidence. Rebuild derived caches only from preserved source records. Quarantine or regeneration keeps original data and emits a diagnostic.

Producer-side gate: feature-task phases owning a bounded planning projection (`preplan`, `plan`, `implement`) re-enter their own fix loop when completed output fails the projection contract.

## Internal Skills

`internal-for: <parent>` in `content.md` installs an unlisted `<skill-name>.md` sidecar for parent reads. Only `skill-bill` can be a parent. Pack specialists are sidecars and native-agent inputs, never slash commands. See `docs/skill-source-generation.md`.

## Worktree ownership

Goals use runtime-owned `<state>/goals/<repo>/<goal>/checkout` worktrees, removed after completion unless they hold unpublished work. Standalone phases use the caller's checkout. Desktop branch starts use that checkout or a runtime-created sibling, removed after its last run.

## Product Intent

Read [Runtime command guidance](docs/runtime-command-guidance.md) before runtime command or workflow work. It defines phases, operations, retired names, agent strategies, build gates, commits, and checkpoints.

## Linear work tracking

Track work in Linear. Use `SONA` for new issues; Linear assigns the full key. Never mint or reserve local issue keys.

Use this repository's `linear-sona` MCP from `.codex/config.toml`; verify workspace and team. Switch connections only if instructed.

Before each goal, verify the connection and linked issue exists in the intended Sona team and workspace. Do not start until verified. Resolve auth, access, or team failures first. Chat prep does not start goals or reserve keys.

Preserve existing identities, issue keys, specs, and history; `SONA` is for new issues only.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — review pack roots and `validation_gate` argv; `addons/` holds flat pack add-ons. Goal planning excludes packs. Eligible `agent/history.md` and `agent/decisions.md` supply heading catalogs; preplanning selects which bodies to read.
- `orchestration/contracts/` — runtime contract schemas

Name pack skills and sidecars `bill-<capability>`, overrides `bill-<platform>-<base-capability>`, and review areas `bill-<platform>-code-review-<area>`. Areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral in `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources need `contract_version`; older ones parse for fixture migration. Put extra guidance in `content.md` H2 sections, not sibling files such as `skills/<skill>/patterns.md`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs extend routing and install through manifests. Hard-coded platform lists are prohibited. Change `orchestration/contracts/platform-pack-schema.yaml` first. `ShellContentLoader.buildPack` throws `InvalidManifestSchemaError` for malformed manifests. Kotlin enforces `x-coherence-checks` cross-field rules beyond JSON Schema.

Repo customization allows top-level custom fields. Runtime fields use `x-runtime-anchored: true`; others flow to `PlatformManifest.customFields`. Horizontal `skills/skill-bill/` and `.bill-shared` are protected. Removable packs, including shipped `kotlin`/`kmp`, live only under `platform-packs/<slug>/`, with no paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality checks, with no Kotlin fallback. `operation:verify` stays pre-shell.

## Add-ons

Pack add-ons live flat under `platform-packs/<slug>/addons/` in lowercase kebab-case. Resolve them after dominant-stack routing; declare consumers with manifest `addon_usage` / `feature_addon_usage`. Do not add selection tables to `content.md`. Cover changes with validator and routing-contract tests.

## Skill Authoring

Scaffold with `skill-bill new [--payload <file>]`; author with `show`, `fill`, `edit`, `validate`, and `render`. Use `create-and-fill` for one content-managed skill. Kinds are `horizontal`, `platform-pack`, and `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Validator, manifest, install, and link failures roll back atomically.

## Adding Platforms

Code review needs a pack root, valid manifest/`content.md`, registered pointers, README entry, tests, and validation. Routing contenders declare `validation_gate`; goal build runs the dominant pack's gate. Standalone and goal validation check the full project. Legacy packs may parse `declared_quality_check_file`, but routing, install, and scaffold ignore it. Feature-task/verify use horizontal and manifest surfaces; `skills/<platform>/` overrides are prohibited.

## Writing And Comments

Write direct, active prose. Cut filler, stale wording, praise, and repetition. Preserve names, numbers, and qualifications. Lead commits, PRs, and docs with the outcome and explain why. Avoid unsupported "successfully", "perfect", "comprehensive", or "robust". Prefer clear names and small functions. Follow Comments for scoped Kotlin.

For UI work, keep overview cards brief, evidence in expandable Steps, and design HTML current for layout, hierarchy, interactions, and narrow screens.

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `skill-bill operation unit-test-value-check` is the review gate.

Tests must not depend on wall-clock time or thread scheduling. Ban `Thread.sleep`, shell `sleep N`, real sleeps, racing timeouts, idle windows, poll intervals, elapsed-time/poll-count assertions, real-time deadline polling, latch/join timeouts, and `delay` outside `runTest`. Never widen CI sleeps or deadlines. Control transitions with injected clocks/schedulers, fake `Clock`, virtual `runTest` time, or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

Run QA with `cd desktop && ./gradlew :app:runFromSource`, using this checkout's runtime, matching identities, and `desktop/app/build/source-run/`. Keep QA state under `desktop/QA.md` scratch paths; never use the installed runtime.

## Comments

Under `runtime-kotlin`, including `build-logic`, ban `//` and non-KDoc `/* */` comments. Allow `/** */` KDoc only on interfaces and their members, including nested types. Put irreducible rationale in the owning `agent/decisions.md`. `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules` enforce this.

## Coding Conventions

For `runtime-kotlin` planning, design, changes, or review, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md) and apply A1–A12, G1–G7, P1–P8. Reviews also run its section 5 checklist and cite rule IDs.

For `runtime-kotlin` design, changes, or review, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) for dependencies, ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs rules with tests; review the rest. Existing violations never authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file sizes, and architecture guards under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** At governed seams, never inline keys in `get`, `put`, `mapOf`, or bracket access. Declare each in its owner's `*Keys` or `*PayloadKeys`. See `runtime-kotlin/ARCHITECTURE.md`. `runtime-contracts` holds keys read by at least two production modules or exposed by `runtime-ports` signatures. Owners:

- `SharedPayloadKeys`: workflow phase-output envelopes
- `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys`: decomposition manifests
- Area-owned keys beside their contract family, such as `ReviewVerificationSignalKeys`
- `TelemetryProxyPayloadKeys`: telemetry proxy wire vocabulary
- `LifecycleTelemetryPayloadKeys`: telemetry envelopes

Single-owner keys stay local, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. Never redeclare shared values. Use constants, never wire strings. Enum tokens use their owner's `wireValue`, never local `setOf`/`mapOf` copies. `WireVocabularyArchitectureTest` enforces seams through `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation` or CLI `skill-bill phase validation`. It discovers and runs repository, build, script, and CI checks, then repairs failures. Compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules, and acceptance/rejection tests.
