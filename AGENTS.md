# AGENTS.md

## Project Context

skill-bill owns skill authoring, routing, validation, installation, measurement, orchestration, telemetry, workflow state, and workflow shells.

Required contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Keep generated support pointers, provider-specific native-agent outputs, and staging artifacts uncommitted.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, content or required sections, and wrong contract versions fail loudly with typed errors.
- Every fallback, degradation, or swallowed failure emits a record; see `docs/observability-policy.md`.

## Runtime Contract Schemas

Every `orchestration/contracts/` YAML is a runtime contract. New contracts need Draft 2020-12 YAML, Kotlin `*_CONTRACT_VERSION`, a parity test, an owner `RuntimeFailureCode` entry thrown as `SkillBillRuntimeException`, and loud failure at every parse seam. See `runtime-kotlin/ARCHITECTURE.md`.

Ship a persisted-data migration path with every schema change. Prove readability for compatible changes; provide explicit steps for version bumps or incompatible changes. Validate source and migrated records. Apply coupled updates atomically and make migration repeatable. Preserve run identity, completed work, commits, checkpoints, and history. Test the previous supported version; retain earlier migration steps for older supported records.

Preserve all existing runs and persisted user and runtime data during installation and upgrades. Migrate automatically before the new installation reads or resumes data. On migration failure, preserve original data and a usable recovery path. Installation must not reset, discard, or replace data with defaults. Verify upgrade behavior against populated previous-version stores, including interrupted migration and repeated installation.

Run migrations before strict current-version validation and board or status projection. Keep unsupported or corrupt records visible with original data, a typed error, and recovery guidance. A schema mismatch must not hide an existing run, reset completed work, or regenerate durable evidence as a substitute for migration. Rebuild derived caches only from preserved source records after migration. Quarantine or regeneration retains original data and emits a diagnostic.

Producer-side gate: feature-task phases owning a bounded planning projection (`preplan`, `plan`, `implement`) re-enter their own fix loop when completed output fails the projection contract.

## Internal Skills

`internal-for: <parent>` in `content.md` installs an unlisted `<skill-name>.md` sidecar for in-session parent reads. Only `skill-bill` can be a parent. Pack specialists are its sidecars and native-agent inputs, never slash commands. See `docs/skill-source-generation.md`.

## Worktree ownership

Goals use runtime-owned `<state>/goals/<repo>/<goal>/checkout` worktrees, removed after recorded completion unless they hold unpublished work. Standalone phases use the caller's checkout. Desktop branch starts use its checkout or a runtime-created sibling worktree, removed after its last run.

## Product Intent

Read [Runtime command guidance](docs/runtime-command-guidance.md) before invoking, planning, designing, changing, or reviewing runtime commands or workflows. It owns phase/operation behavior, retired names, agent execution, and goal commits/checkpoints.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — code review pack roots and `validation_gate` argv; `addons/` holds flat pack-owned add-ons. Goal-planning discovery excludes packs. Eligible `agent/history.md` and `agent/decisions.md` supply heading catalogs; bodies arrive only for headings selected by preplanning.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral at `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources require `contract_version`; older sources parse for fixture migration. Extra guidance belongs in `content.md` H2 sections; sibling files such as `skills/<skill>/patterns.md` are prohibited.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs extend routing and install through manifests. Hard-coded platform lists are prohibited. Change `orchestration/contracts/platform-pack-schema.yaml` first. `ShellContentLoader.buildPack` throws `InvalidManifestSchemaError` for malformed manifests. Kotlin enforces `x-coherence-checks` cross-field rules beyond JSON Schema.

Repo customization allows top-level custom fields. Runtime-consumed fields use `x-runtime-anchored: true`; other fields flow to `PlatformManifest.customFields`. Product vs extension: horizontal `skills/skill-bill/` and `.bill-shared` are protected; `platform-packs/<slug>/` (including shipped `kotlin`/`kmp`) are removable — no paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Add-ons

Pack-owned add-on files live flat under `platform-packs/<slug>/addons/`, use lowercase kebab-case, and resolve only after dominant-stack routing. Declare consumers through manifest `addon_usage` / `feature_addon_usage`. Selection tables in `content.md` are prohibited. Changes need validator and routing-contract coverage.

## Skill Authoring

Scaffold with `skill-bill new`, optionally `--payload <file>`. Author with `show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` handles one content-managed skill. Kinds: `horizontal`, `platform-pack`, `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Validator/manifest/install/link failures roll back atomically.

## Adding Platforms

Code review needs a pack root, conforming manifest/`content.md`, registered pointers, README catalog, pack tests, and validation. Routing contenders declare `validation_gate`; goal build runs the dominant pack's gate. Standalone and goal validation check the full project. Legacy packs may parse `declared_quality_check_file`; routing, install, and scaffold ignore it. Feature-task/verify use horizontal and manifest surfaces; legacy `skills/<platform>/` overrides are prohibited.

## Runtime Agent Behavior And Commits

[Runtime command guidance](docs/runtime-command-guidance.md) owns injected agent strategies, build gates, one-commit-per-subtask, and checkpoint rules.

## Writing And Comments

Write direct, active prose. Cut filler, stale wording, praise and repetition. Preserve names, numbers and qualifications. Commits/PRs/docs lead with the outcome and explain changes and reasons. Avoid unsupported "successfully", "perfect", "comprehensive", and "robust". Prefer clear names and small functions. Follow Comments for scoped Kotlin.

When implementing designs, keep status cards and overview elements brief. Show the current status and a short summary. Never put walls of text, full reports, evidence tables, or detailed findings in an overview. Put findings and supporting evidence in the relevant expandable Steps section.

When adding or changing UI, update the matching design HTML files in the same change. Keep the reference design and implementation consistent, including layout, content hierarchy, interactions, and narrow layouts.

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `skill-bill operation unit-test-value-check` is the review gate.

Tests must not depend on wall-clock time or thread scheduling. Ban `Thread.sleep`, shell `sleep N`, other real sleeps, racing timeouts, idle windows, poll intervals, elapsed-time/poll-count assertions, real-time deadline polling, latch/join timeouts, and real `delay` outside `runTest`. Never widen sleeps or deadlines for CI. Control transitions with injected clocks/schedulers, fake `Clock`, `runTest` virtual time, or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

QA launches with `cd desktop && ./gradlew :app:runFromSource`, building this checkout's runtime with matching identities and scratch home `desktop/app/build/source-run/`. Keep QA state in `desktop/QA.md` scratch paths; never use the operator's installed runtime.

## Comments

Under `runtime-kotlin`, including `build-logic`, ban `//` and non-KDoc `/* */` comments. Allow `/** */` KDoc only on interfaces and their members, including nested types. Put irreducible rationale in the owning `agent/decisions.md`. `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules` enforce this.

## Coding Conventions

For `runtime-kotlin` planning, design, changes, or review, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md). Apply A1–A12, G1–G7, and P1–P8. Runtime-kotlin reviews run its section 5 checklist and cite rule IDs.

For `runtime-kotlin` design, changes, or review, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) for dependencies, state/resource ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs rules with tests; others need review. Existing violations never authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards. Guards live under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** At governed seams, never inline contract/wire keys in `get`, `put`, `mapOf`, or bracket access. Declare each once in its owner's `*Keys` or `*PayloadKeys`. See Wire vocabulary in `runtime-kotlin/ARCHITECTURE.md`. `runtime-contracts` holds only keys read by at least two production modules or exposed by `runtime-ports` signatures. Shared owners:

- `SharedPayloadKeys`: workflow phase-output envelopes
- `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys`: decomposition manifests
- Area-owned keys beside their contract family, such as `ReviewVerificationSignalKeys`
- `TelemetryProxyPayloadKeys`: telemetry proxy wire vocabulary
- `LifecycleTelemetryPayloadKeys`: telemetry envelopes

Single-owner keys stay local, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. Never redeclare shared values. Use constants, never wire strings. Enum tokens use their owner's `wireValue`, never local `setOf`/`mapOf` copies. `WireVocabularyArchitectureTest` enforces seams through `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation`, CLI `skill-bill phase validation`. Like goal validate, it discovers and runs repository, build, script, and CI checks, then repairs failures. Compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules, and acceptance/rejection tests.
