# AGENTS.md

## Project Context

skill-bill governs skill authoring, routing, validation, installation, and measurement. It owns orchestration, telemetry, workflow state, and review, check, feature, verification, and PR shells.

Non-negotiable contracts:

- Authored source is `content.md` (plus governed sidecars); generated `SKILL.md` is install output. Listed-skill staging: `SKILL.md`, `.content-hash`, pointers, optional `native-agents/` — no `content.md` copy.
- Skill sources: `skills/<skill>/content.md`, optional `native-agents/`, contract-authorized sidecars.
- Platform behavior lives in manifest-declared packs under `platform-packs/<slug>/`.
- `orchestration/` owns shared routing, review, delegation, telemetry, workflow, and shell contracts.
- `agent-addons/<slug>/` contains only user-owned `agent-addon.yaml` and `content.md`.
- Generated support pointers, provider-specific native-agent outputs, and install staging artifacts are not committed.
- Discovery, install, routing, and validation stay dynamic and manifest-driven.
- Missing manifests, content or required sections, and wrong contract versions fail loudly with typed errors.
- Every fallback, degradation, or swallowed failure emits a record; see `docs/observability-policy.md`.

## Internal Skills

`internal-for: <parent>` in `content.md` frontmatter installs an unlisted `<skill-name>.md` sidecar beside its parent, which reads it in-session. Only `skill-bill` can be a parent. Pack specialists are its unlisted sidecars and native-agent inputs, never slash commands. Contract: `docs/skill-source-generation.md`.

## Worktree ownership

Goals use runtime-owned `<state>/goals/<repo>/<goal>/checkout` worktrees, removed after recorded completion unless they hold unpublished work. Standalone phases use the caller's checkout. Desktop starts naming a branch use its checkout or a runtime-created sibling, removed after its last run.

## Product Intent

`/skill-bill` is the only listed skill. Before invoking, planning, designing, changing, or reviewing runtime commands or workflows, read [Runtime command guidance](docs/runtime-command-guidance.md). It owns phases, operations, retired-name compatibility, agent execution, and goal commit/checkpoint rules.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` holds code-review packs and `validation_gate` quality-check argv; `addons/` holds flat pack-owned add-ons. Goal-planning discovery excludes packs. Planning receives only eligible `agent/history.md` and `agent/decisions.md` headings, then bodies for headings preplanning selected.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers, read `docs/skill-source-generation.md`.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Provider-neutral sources are `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources need `contract_version`; older sources parse for fixture migration. Extra guidance belongs in `content.md` H2 sections, not sibling org files like `skills/<skill>/patterns.md`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs are the extension surface. Routing and install read manifests, not hard-coded platform lists. Change `orchestration/contracts/platform-pack-schema.yaml` first. `ShellContentLoader.buildPack` rejects malformed manifests with `InvalidManifestSchemaError`. Kotlin `x-coherence-checks` own cross-field rules JSON Schema cannot express.

Top-level custom fields are allowed. Runtime-consumed fields need `x-runtime-anchored: true`; others flow to `PlatformManifest.customFields`. Horizontal `skills/skill-bill/` and `.bill-shared` are protected. Packs under `platform-packs/<slug>/`, including shipped `kotlin`/`kmp`, are removable. No paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contracts: Draft 2020-12 schema in YAML → Kotlin `*_CONTRACT_VERSION` → parity test → a failure-code entry in the owner's `RuntimeFailureCode` enum, thrown as `SkillBillRuntimeException` → loud-fail at every parse seam. Detail: `runtime-kotlin/ARCHITECTURE.md`.

Schema bumps loud-fail legacy records; runtime quarantines and regenerates in-band. Feature-task producers of bounded planning projections, `preplan`, `plan`, and `implement`, re-enter their own fix loop if completed output fails the projection contract.

## Add-ons

Add-ons are pack-owned files, not skills: flat lowercase kebab-case under `platform-packs/<slug>/addons/`, resolved only after dominant-stack routing. Declare consumers through manifest `addon_usage` / `feature_addon_usage`, not `content.md` selection tables. Changes need validator and routing-contract coverage.

## Skill Authoring

Scaffold with `skill-bill new` or `--payload <file>`; author with `skill-bill show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` handles one content-managed skill. Kinds: `horizontal`, `platform-pack`, `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Scaffolding is atomic: validator, manifest, install, and link failures roll back.

## Adding Platforms

Code review needs a pack root, conforming manifest/`content.md`, manifest-registered pointers, README catalog, pack tests, and validation. Packs eligible for dominant-stack routing need `validation_gate`; goal build uses the dominant pack's build gate. `skill-bill phase validation` uses goal validate's full project strategy. Leftover custom packs may parse optional `declared_quality_check_file`, but routing, install, and scaffold do not consume it. Feature-task/verify use horizontal and manifest surfaces; no legacy `skills/<platform>/` overrides.

## Runtime Agent Behavior And Commits

[Runtime command guidance](docs/runtime-command-guidance.md) owns injected agent strategies, build gates, one-commit-per-subtask, and checkpoint rules.

## Writing And Comments

Write direct, active prose without filler, stale wording, praise, or repetition. Preserve names, numbers, and qualifications. Commits/PRs/docs lead with the outcome and explain changes and reasons. Avoid unsupported "successfully", "perfect", "comprehensive", and "robust". Use clear names and small functions. Follow Comments for Kotlin.

## Testing

Write few, high-value tests. Before authoring, name the realistic bug; assert observable boundaries, not implementation structure. Review gate: `skill-bill operation unit-test-value-check`.

No tests may depend on wall-clock time or thread scheduling. Ban real sleeps, including `Thread.sleep` and shell `sleep N`, racing timeouts, idle windows, poll intervals, elapsed-time or poll-count assertions, real-time deadline polling, latch or join timeouts, and real `delay` outside `runTest`. Never widen sleeps or deadlines for loaded CI runners. Control every transition with an injected clock and scheduler, fake `Clock`, `runTest` virtual time, or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

Desktop refuses runtimes from another commit. Dirty builds also need matching `SKILL_BILL_INSTALL_RUN_ID`. Never use the operator's installed runtime for QA. Run these commands from `desktop/` in the QA checkout, in order, with one exported run id. Separate Gradle calls are required: `:app:run` can read the runtime record before `installDist` writes it.

```sh
export SKILL_BILL_INSTALL_RUN_ID=qa-<invocation id>
./gradlew :runtime-kotlin:runtime-cli:installDist
SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db ../runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli stats
SKILL_BILL_RUNTIME_DIR=$PWD/../runtime-kotlin/runtime-cli/build/install SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db JAVA_TOOL_OPTIONS="-Duser.home=<dir>/home -Djava.io.tmpdir=<dir>/tmp" ./gradlew :app:run
```

`stats` creates the isolated store; without it the board reports "The selected database does not exist". Use `SKILL_BILL_REVIEW_DB`; `SKILL_BILL_DB` does nothing. An isolated home's New task screen reports "Not ready: no agent found", as expected.

## Comments

Kotlin under `runtime-kotlin` and `runtime-kotlin/build-logic` must have no `//` or non-KDoc `/* */` comments. `/** */` KDoc is allowed only on `interface` declarations and their members, including nested types. Put irreducible why-only rationale in the owning `agent/decisions.md`. `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules` enforce this.

## Coding Conventions

**Required reading.** Read [Runtime Architecture Guidelines](docs/architecture-guidelines.md) before any `runtime-kotlin` planning, design, change, or review. Apply A1-A12, G1-G7, and P1-P8. Reviews use section 5's checklist and cite rule IDs.

Before designing, changing, or reviewing `runtime-kotlin`, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) for dependency direction, state/resource ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs mechanical rules with tests; other rules stay review-only. Existing violations permit no new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards, enforced under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** Never inline contract or wire keys in `get`, `put`, `mapOf("key" to …)`, or bracket access at governed seams. See Wire vocabulary in `runtime-kotlin/ARCHITECTURE.md`. Declare each key once in its owning `*Keys` or `*PayloadKeys` object. `runtime-contracts` keys require readers in two or more production modules or exposure in a `runtime-ports` signature: `SharedPayloadKeys` owns phase-output envelopes; `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys` own decomposition manifests; area-owned keys such as `ReviewVerificationSignalKeys` stay beside their contract family; `TelemetryProxyPayloadKeys` owns telemetry proxy vocabulary; `LifecycleTelemetryPayloadKeys` owns telemetry envelopes. Single-owner keys stay in that module, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. They must not redeclare shared values. Consumers use constants, not wire strings. Use the owning enum's `wireValue`, not local `setOf`/`mapOf` tokens. `WireVocabularyArchitectureTest` checks governed seams via `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation`, CLI `skill-bill phase validation`. Use goal validate's strategy: discover all required checks in repository instructions, build configuration, scripts, and CI, run them, and repair failures. Compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules, and acceptance/rejection tests.
