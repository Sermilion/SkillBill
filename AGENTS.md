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
- Every fallback, degradation, or swallowed failure emits a record; see `docs/observability-policy.md`.

## Runtime Contract Schemas

All `orchestration/contracts/` YAMLs are runtime contracts. New ones require Draft 2020-12 YAML, Kotlin `*_CONTRACT_VERSION`, parity tests, an owner `RuntimeFailureCode` thrown as `SkillBillRuntimeException`, and loud failures at every parse seam. See `runtime-kotlin/ARCHITECTURE.md`.

Every schema change needs a persisted-data migration path. Prove compatible readability; document steps for version bumps and incompatible changes. Validate source and migrated records. Migrations must be repeatable, with atomic coupled updates. Preserve run identity, completed work, commits, checkpoints and history. Test the previous supported version; retain steps for older supported records.

Installation and upgrades preserve all runs and persisted user/runtime data. Migrate automatically before reading or resuming data. Failed migration preserves originals and a usable recovery path. Never reset, discard or replace data with defaults. Test upgrades with populated previous-version stores, interrupted migration and repeated installation.

Migrate before strict current-version validation and board/status projection. Show unsupported or corrupt records with original data, typed errors and recovery guidance. Schema mismatches must never hide runs, reset completed work or regenerate durable evidence instead of migrating. Rebuild caches only from preserved source records after migration. Quarantine or regeneration preserves originals and emits a diagnostic.

`preplan`, `plan` and `implement` re-enter their own fix loop if completed output fails their bounded planning projection contract.

## Internal Skills

`content.md`'s `internal-for: <parent>` installs unlisted `<skill-name>.md` for parent reads. Only `skill-bill` may be a parent. Pack specialists are sidecars and native-agent inputs, never slash commands. See `docs/skill-source-generation.md`.

## Worktree ownership

Goals own `<state>/goals/<repo>/<goal>/checkout`, removed after recorded completion unless unpublished work remains. Standalone phases use the caller's checkout. Desktop branch starts use that checkout or a runtime-created sibling, removed after its last run.

## Product Intent

Before runtime command or workflow work, read [Runtime command guidance](docs/runtime-command-guidance.md) for phases, operations, retired names, agent strategies, build gates, one-commit-per-subtask and checkpoints.

## Linear work tracking

Track every work item in Linear. New issues use team `SONA`; Linear assigns complete keys. Never mint or reserve substitute local keys.

Use `.codex/config.toml`'s project-scoped `linear-sona` MCP connection. Verify its workspace and `SONA` team before use. Another connection requires operator instruction.

Before any goal starts, verify the connection and read its existing issue in the intended Sona team and workspace. Report and resolve authentication failures, inaccessible issues or an unavailable `SONA` team before launch. No goal starts without a verified issue. Chat preparation neither starts goals nor reserves keys.

Preserve run identities, issue keys, specs and history. `SONA` applies only to new work.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` holds review packs, `validation_gate` argv and flat `addons/`. Exclude packs from goal-planning discovery. Eligible `agent/history.md` and `agent/decisions.md` supply heading catalogs; preplanning selects which bodies arrive.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before skill/scaffold/render/install-staging/native-agent/support-pointer changes.

Prohibit source `SKILL.md` wrappers, generated pointers such as `shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers, and provider outputs `claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`.

Provider-neutral native-agent source lives at `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources require `contract_version`; parse older sources for fixture migration. Put extra guidance in `content.md` H2 sections; prohibit siblings such as `skills/<skill>/patterns.md`.

Run `./install.sh` after source-skill, renderer or support-pointer changes.

## Platform Packs

Packs extend routing/install through manifests. Prohibit hard-coded platform lists. Change `orchestration/contracts/platform-pack-schema.yaml` first. Malformed manifests make `ShellContentLoader.buildPack` throw `InvalidManifestSchemaError`. Kotlin enforces `x-coherence-checks` beyond JSON Schema.

Allow top-level custom fields. Runtime-consumed fields require `x-runtime-anchored: true`; others flow to `PlatformManifest.customFields`. Protect horizontal `skills/skill-bill/` and `.bill-shared`. Packs under `platform-packs/<slug>/`, including shipped `kotlin`/`kmp`, are removable. No paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Add-ons

Keep pack add-ons flat in `platform-packs/<slug>/addons/`, named lowercase kebab-case. Resolve after dominant-stack routing only. Declare consumers via manifest `addon_usage` / `feature_addon_usage`, without `content.md` selection tables. Changes require validator and routing-contract coverage.

## Skill Authoring

Use `sona new` with optional `--payload <file>`, then `show`/`fill`/`edit`/`validate`/`render`. `create-and-fill` handles one content-managed skill. Kinds: `horizontal`, `platform-pack`, `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Roll back validator/manifest/install/link failures atomically.

## Adding Platforms

Code review requires a pack root, conforming manifest/`content.md`, registered pointers, README catalog, pack tests and validation. Routing contenders declare `validation_gate`; goal build runs the dominant gate. Standalone/goal validation covers the full project. Legacy packs may parse `declared_quality_check_file`; routing/install/scaffold ignore it. Feature-task/verify use horizontal and manifest surfaces. Prohibit legacy `skills/<platform>/` overrides.

## Writing And Comments

Write direct, active prose. Cut filler, stale wording, praise and repetition; preserve names, numbers and qualifications. Commits/PRs/docs lead with outcomes, changes and reasons. Avoid unsupported "successfully", "perfect", "comprehensive" and "robust". Use clear names and small functions. Follow Comments for scoped Kotlin.

For UI work, keep overview cards brief and evidence in expandable Steps. Update matching design HTML for layout, hierarchy, interactions and narrow layouts.

## Testing

Write few, high-value tests. Name each realistic bug before authoring. Assert observable boundaries, not structure. Review gate: `sona operation unit-test-value-check`.

Tests cannot depend on wall-clock time or thread scheduling. Ban `Thread.sleep`, shell `sleep N`, real sleeps, racing timeouts, idle windows, poll intervals, elapsed-time/poll-count assertions, real-time deadline polling, latch/join timeouts and real `delay` outside `runTest`. Never widen sleeps or deadlines for CI. Use injected clocks/schedulers, fake `Clock`, `runTest` virtual time or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

QA uses `cd desktop && ./gradlew :app:runFromSource` to build this checkout's runtime with matching identities and scratch home `desktop/app/build/source-run/`. Use `desktop/QA.md` scratch paths, never the installed operator runtime.

## Comments

In `runtime-kotlin` and `build-logic`, ban `//` and non-KDoc `/* */`. Permit `/** */` only on interfaces and members, including nested types. Put rationale in owner `agent/decisions.md`. Enforce via `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules`.

## Coding Conventions

Read [Runtime Architecture Guidelines](docs/architecture-guidelines.md) for `runtime-kotlin` planning/design/changes/review. Apply A1 through A12, G1 through G7 and P1 through P8. Reviews run section 5's checklist and cite rule IDs.

For `runtime-kotlin` design/changes/review, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) on dependencies, state/resource ownership, persistence, contracts, simplicity and test value. `PrincipleEnforcementInventory.enforceableRules` pairs rules with tests; review the rest. No new violations, regardless of existing debt.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, clustering, imports, file-size limits and guards in `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** Governed `get`, `put`, `mapOf` and bracket access must use owner `*Keys`/`*PayloadKeys`, declared once. See Wire vocabulary in `runtime-kotlin/ARCHITECTURE.md`. Only keys read by two production modules or exposed by `runtime-ports` signatures belong in `runtime-contracts`. Shared owners:

- `SharedPayloadKeys`: workflow phase-output envelopes
- `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys`: decomposition manifests
- Area keys beside their contract family, e.g. `ReviewVerificationSignalKeys`
- `TelemetryProxyPayloadKeys`: telemetry proxy wire vocabulary
- `LifecycleTelemetryPayloadKeys`: telemetry envelopes

Keep single-owner keys local, e.g. `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite`, `GoalRunnerResetPayloadKeys` in `runtime-cli`. No redeclared shared values or wire strings. Use owner `wireValue` for enums, without local `setOf`/`mapOf` copies. `WireVocabularyArchitectureTest` enforces `WireVocabularyGovernedSeamInventory` seams.

## Quality Checks

Prefer `/sona phase:validation` or CLI `sona phase validation`. Like goal validate, discover and run repository, build, script and CI checks, then repair failures. Compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules and acceptance/rejection tests.
