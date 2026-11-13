# AGENTS.md

## Project Context

skill-bill owns authoring, routing, validation, installation, measurement, orchestration, telemetry, workflow state and shells.

Public commands are `/sona` and `sona`; MCP executable `sona-mcp`, server `sona`; desktop launcher `sona-app`, visible name Sona. Keep canonical source `skills/skill-bill/`, `internal-for: skill-bill`, durable state paths and raw operation identities. SONA-5's final subtask makes `skill-bill` notice-only.

Required contracts:

- Author `content.md` and governed sidecars. Generate `SKILL.md` on install. Stage listed skills as `SKILL.md`, `.content-hash`, pointers and optional `native-agents/`, without `content.md`.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Keep generated support pointers, provider-specific native-agent outputs, and staging artifacts uncommitted.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, content or required sections, and wrong contract versions fail loudly with typed errors.
- Fallbacks, degradations, and swallowed failures emit records; see `docs/observability-policy.md`.

## Runtime Contract Schemas


Every `orchestration/contracts/` YAML is a runtime contract. New contracts require Draft 2020-12 YAML, Kotlin `*_CONTRACT_VERSION`, a parity test, and an owner `RuntimeFailureCode` thrown as `SkillBillRuntimeException`. Every parse seam fails loudly. See `runtime-kotlin/ARCHITECTURE.md`.

Every schema change needs a persisted-data migration. Prove compatible reads; document version bumps and incompatible migrations. Validate source and migrated records, apply coupled updates atomically, and make migration repeatable. Preserve run identity, completed work, commits, checkpoints, and history. Test the previous supported version and retain earlier migration steps for older supported records.

Install and upgrade without losing runs or persisted data. Migrate automatically before reading or resuming data. On failure, preserve original data and a recovery path. Never reset, discard, or replace data with defaults. Test populated previous-version stores, interrupted migrations, and repeated installation.

Migrate before strict version validation or board/status projection. Keep unsupported or corrupt records visible with original data, a typed error, and recovery guidance. Schema mismatches must not hide runs, reset work, or replace migration with regenerated evidence. Rebuild caches only from preserved source records. Quarantine or regeneration retains originals and emits a diagnostic.


`preplan`, `plan` and `implement` re-enter their own fix loop if completed output fails their bounded planning projection contract.

## Internal Skills

`content.md`'s `internal-for: <parent>` installs unlisted `<skill-name>.md` for parent reads. Only `skill-bill` may be a parent. Pack specialists are sidecars and native-agent inputs, never slash commands. See `docs/skill-source-generation.md`.

## Worktree ownership


Goals use runtime-owned `<state>/goals/<repo>/<goal>/checkout` worktrees, removed after completion unless they hold unpublished work. Standalone phases use the caller's checkout. Desktop branches use it or a runtime-created sibling worktree, removed after the last run.


## Product Intent

Before runtime command or workflow work, read [Runtime command guidance](docs/runtime-command-guidance.md) for phases, operations, retired names, agent strategies, build gates, one-commit-per-subtask and checkpoints.

## Linear work tracking


Track repository work in Linear under `SONA`, except direct tasks explicitly requested on `main`; do not create issues for those. Let Linear assign complete keys. Never mint or reserve local keys.

Use `.codex/config.toml`'s `linear-sona` connection. Verify its workspace and team. Switch connections only on operator instruction.

Before each goal, verify its issue in that workspace/team. Resolve access problems before launch. Chat prep does not launch goals or reserve keys.

Use `SONA` only for new work; preserve historical run identities, keys, specs, and history.

## Taxonomy

- `skills/` — canonical user-facing sources; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — code review pack roots and `validation_gate` argv; `addons/` holds flat pack-owned add-ons. Goal-planning discovery excludes packs. Eligible `agent/history.md` and `agent/decisions.md` supply heading catalogs; bodies arrive only for headings selected by preplanning.
- `orchestration/contracts/` — runtime schemas


Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before skill/scaffold/render/install-staging/native-agent/support-pointer changes.


Source forbids governed `SKILL.md` wrappers, generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers), and provider outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).


Provider-neutral native-agent source lives at `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources require `contract_version`; parse older sources for fixture migration. Put extra guidance in `content.md` H2 sections; prohibit siblings such as `skills/<skill>/patterns.md`.

Run `./install.sh` after source-skill, renderer or support-pointer changes.

## Platform Packs


Packs extend routing and install through manifests; hard-coded platform lists are prohibited. Update `orchestration/contracts/platform-pack-schema.yaml` first. `ShellContentLoader.buildPack` throws `InvalidManifestSchemaError` for malformed manifests. Kotlin enforces `x-coherence-checks` beyond JSON Schema.


Allow top-level custom fields. Runtime-consumed fields require `x-runtime-anchored: true`; others flow to `PlatformManifest.customFields`. Protect horizontal `skills/skill-bill/` and `.bill-shared`. Packs under `platform-packs/<slug>/`, including shipped `kotlin`/`kmp`, are removable. No paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Add-ons


Keep pack add-ons flat under `platform-packs/<slug>/addons/` with lowercase kebab-case names. Resolve them after dominant-stack routing; declare consumers with manifest `addon_usage` / `feature_addon_usage`. No selection tables in `content.md`. Add validator and routing-contract coverage.


## Skill Authoring

Use `sona new` with optional `--payload <file>`, then `show`/`fill`/`edit`/`validate`/`render`. `create-and-fill` handles one content-managed skill. Kinds: `horizontal`, `platform-pack`, `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Roll back validator/manifest/install/link failures atomically.

## Adding Platforms


Code review needs a pack root, manifest and `content.md`, pointers, README entry, tests, and validation. Routing packs declare `validation_gate`; goals run the dominant pack's gate. Standalone and goal validation cover the project. Legacy packs may parse `declared_quality_check_file`, ignored by routing, install, and scaffold. Feature-task/verify use horizontal and manifest surfaces; no `skills/<platform>/` overrides.

## Writing And Comments

Write direct, active prose. Cut filler, stale wording, praise, and repetition. Preserve names, numbers, and qualifications. Lead commits, PRs, and docs with outcomes and reasons. Avoid unsupported "successfully", "perfect", "comprehensive", and "robust". Use clear names and small functions. Follow Comments for scoped Kotlin.


For UI work, keep overview cards brief and evidence in expandable Steps. Update matching design HTML for layout, hierarchy, interactions and narrow layouts.

## Testing

Write few, high-value tests. Name each realistic bug before authoring. Assert observable boundaries, not structure. Review gate: `sona operation unit-test-value-check`.

Tests cannot depend on wall-clock time or thread scheduling. Ban `Thread.sleep`, shell `sleep N`, real sleeps, racing timeouts, idle windows, poll intervals, elapsed-time/poll-count assertions, real-time deadline polling, latch/join timeouts and real `delay` outside `runTest`. Never widen sleeps or deadlines for CI. Use injected clocks/schedulers, fake `Clock`, `runTest` virtual time or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

QA uses `cd desktop && ./gradlew :app:runFromSource` to build this checkout's runtime with matching identities and scratch home `desktop/app/build/source-run/`. Use `desktop/QA.md` scratch paths, never the installed operator runtime.

## Comments

In `runtime-kotlin` and `build-logic`, ban `//` and non-KDoc `/* */`. Permit `/** */` only on interfaces and members, including nested types. Put rationale in owner `agent/decisions.md`. Enforce via `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules`.

## Coding Conventions


For `runtime-kotlin` planning, design, changes, or review, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md) and apply A1–A12, G1–G7, and P1–P8. Reviews use its section 5 checklist and cite rule IDs.

For `runtime-kotlin` design, changes, or review, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) for dependencies, ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs rules with tests; review the rest. Existing violations never authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, packages, imports, file-size limits, and architecture guards under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** At governed seams, declare each key once in its owner's `*Keys` or `*PayloadKeys`; never inline keys in `get`, `put`, `mapOf`, or bracket access. See `runtime-kotlin/ARCHITECTURE.md`. `runtime-contracts` holds only keys read by two or more production modules or exposed by `runtime-ports` signatures. Shared owners:


- `SharedPayloadKeys`: workflow phase-output envelopes
- `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys`: decomposition manifests
- Area keys beside their contract family, e.g. `ReviewVerificationSignalKeys`
- `TelemetryProxyPayloadKeys`: telemetry proxy wire vocabulary
- `LifecycleTelemetryPayloadKeys`: telemetry envelopes


Keep single-owner keys local, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. Never redeclare shared values or inline wire strings. Enum tokens use their owner's `wireValue`, never local `setOf`/`mapOf` copies. `WireVocabularyArchitectureTest` checks governed seams in `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/sona phase:validation` or `sona phase validation`. It discovers repository, build, script, and CI checks and repairs failures; compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules, and acceptance/rejection tests.

