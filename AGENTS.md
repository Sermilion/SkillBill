# AGENTS.md

## Project Context

skill-bill governs skill authoring, routing, validation, installation, and measurement. It owns orchestration, telemetry, workflow state, and review/check/feature/verification/PR shells.

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

`internal-for: <parent>` in `content.md` installs an unlisted `<skill-name>.md` sidecar, read by its parent in-session. Only `skill-bill` can be a parent. Pack specialists become its sidecars and native-agent inputs, never slash commands. See `docs/skill-source-generation.md`.

## Worktree ownership

Goals use runtime-owned worktrees at `<state>/goals/<repo>/<goal>/checkout`, removed after recorded completion unless they hold unpublished work. Standalone phases use the caller's checkout. Desktop branch starts use that branch's checkout or a runtime-created sibling worktree, removed after its last run.

## Product Intent

`/skill-bill` is the only listed skill. Before invoking, planning, designing, changing, or reviewing runtime commands and workflows, read [Runtime command guidance](docs/runtime-command-guidance.md). It owns phase/operation behavior, retired-name compatibility, agent execution, and goal commits/checkpoints.

## Taxonomy

- `skills/` — canonical user-facing skill source; `skills/skill-bill/` is the only listed skill
- `platform-packs/<platform>/` — pack roots for code review and pack `validation_gate` quality-check argv; `addons/` flat pack-owned add-ons. Packs are excluded from goal-planning discovery; eligible `agent/history.md` and `agent/decisions.md` reach planning as a heading catalog only — bodies arrive for headings preplanning selected.
- `orchestration/contracts/` — runtime contract schemas

Naming (pack skills and sidecars): `bill-<capability>`; overrides `bill-<platform>-<base-capability>`; review areas `bill-<platform>-code-review-<area>`. Approved areas: `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`.

## Source And Generated Files

Read `docs/skill-source-generation.md` before changing skills, scaffolding, rendering, install staging, native-agent generation, or support pointers.

Forbidden in source: governed `SKILL.md` wrappers; generated support pointers (`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`, review/delegation/add-on pointers); provider-specific `*-agents/` outputs (`claude-agents/`, `codex-agents/`, `junie-agents/`, `cursor-agents/`).

Native-agent source is provider-neutral at `native-agents/agents.yaml` or `native-agents/<name>.md`. New/rendered sources require `contract_version`; older sources parse for fixture migration. Put extra guidance in `content.md` H2 sections, never sibling files such as `skills/<skill>/patterns.md`.

Run `./install.sh` after changing source skills, renderer behavior, or support pointer generation.

## Platform Packs

Packs extend routing and install through manifests, never hard-coded platform lists. Change `orchestration/contracts/platform-pack-schema.yaml` first. `ShellContentLoader.buildPack` rejects malformed manifests with `InvalidManifestSchemaError`. Kotlin enforces cross-field rules under `x-coherence-checks` when JSON Schema cannot.

Per-repo customization: top-level custom fields allowed; runtime-consumed fields use `x-runtime-anchored: true`; non-anchored fields flow to `PlatformManifest.customFields`. Product vs extension: horizontal `skills/skill-bill/` and `.bill-shared` are protected; `platform-packs/<slug>/` (including shipped `kotlin`/`kmp`) are removable — no paired `skills/<platform>/` trees.

`kmp` covers Android and Kotlin Multiplatform on the Kotlin baseline. Its `validation_gate` owns quality-check commands, with no Kotlin fallback. `operation:verify` remains pre-shell.

## Runtime Contract Schemas

Every YAML under `orchestration/contracts/` is a runtime contract. New contracts: Draft 2020-12 schema in YAML → Kotlin `*_CONTRACT_VERSION` → parity test → a failure-code entry in the owner's `RuntimeFailureCode` enum, thrown as `SkillBillRuntimeException` → loud-fail at every parse seam. Detail: `runtime-kotlin/ARCHITECTURE.md`.

Schema bumps loud-fail legacy records; runtime quarantines and regenerates in-band. Producer-side gate: feature-task phases owning a bounded planning projection (`preplan`, `plan`, `implement`) re-enter their own fix loop when completed output fails the projection contract.

## Add-ons

Pack-owned files (not skills): flat under `platform-packs/<slug>/addons/`, lowercase kebab-case, resolved only after dominant-stack routing. Declare consumers in the pack manifest (`addon_usage` / `feature_addon_usage`); do not hand-author selection tables in `content.md`. Changes need validator and routing-contract coverage.

## Skill Authoring

Scaffold with `skill-bill new`, optionally `--payload <file>`. Author with `show`, `fill`, `edit`, `validate`, and `render`. `create-and-fill` handles one content-managed skill. Kinds: `horizontal`, `platform-pack`, `add-on`. Follow `orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md`. Validator/manifest/install/link failures roll back atomically.

## Adding Platforms

Code review requires a pack root, conforming manifest/`content.md`, manifest-registered pointers, README catalog, pack tests, and validation. Routing contenders declare `validation_gate`; goal build runs the dominant pack's gate. Standalone and goal validation check the full project. Legacy custom packs may parse `declared_quality_check_file`, but routing, install, and scaffold ignore it. Feature-task/verify use horizontal and manifest surfaces; legacy `skills/<platform>/` overrides are prohibited.

## Runtime Agent Behavior And Commits

[Runtime command guidance](docs/runtime-command-guidance.md) owns injected agent strategies, build gates, one-commit-per-subtask, and checkpoint rules.

## Writing And Comments

Write direct, active prose. Remove filler, stale wording, praise and repetition; preserve names, numbers and qualifications. Commits/PRs/docs lead with the outcome and explain what changed and why. Avoid unsupported "successfully", "perfect", "comprehensive", and "robust". Prefer clear names and small functions. Follow Comments for scoped Kotlin.

## Testing

Write few, high-value tests; name the realistic bug each would catch before authoring. Assert observable boundaries, not implementation structure. `skill-bill operation unit-test-value-check` is the review gate.

Tests must not depend on wall-clock time or thread scheduling. Ban real sleeps, including `Thread.sleep` and shell `sleep N`, racing timeouts, idle windows, poll intervals, elapsed-time/poll-count assertions, real-time deadline polling, latch/join timeouts, and real `delay` outside `runTest`. Never widen sleeps or deadlines for loaded CI. Control every transition with injected clocks/schedulers, fake `Clock`, `runTest` virtual time, or code-signalled latches. Leave real-time-only behavior untested.

## Desktop QA

Desktop and runtime builds must share a commit and, for dirty trees, `SKILL_BILL_INSTALL_RUN_ID`. Never use the operator's runtime installation for QA. Run these separately, in order, from `desktop/` in the QA checkout with one exported run id. Combining Gradle tasks races `:app:run` against `installDist` writing the runtime record.

```sh
export SKILL_BILL_INSTALL_RUN_ID=qa-<invocation id>
./gradlew :runtime-kotlin:runtime-cli:installDist
SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db ../runtime-kotlin/runtime-cli/build/install/runtime-cli/bin/runtime-cli stats
SKILL_BILL_RUNTIME_DIR=$PWD/../runtime-kotlin/runtime-cli/build/install SKILL_BILL_STATE_DIR=<dir>/state SKILL_BILL_REVIEW_DB=<dir>/state/review-metrics.db JAVA_TOOL_OPTIONS="-Duser.home=<dir>/home -Djava.io.tmpdir=<dir>/tmp" ./gradlew :app:run
```

`stats` creates the isolated store; otherwise the board reports "The selected database does not exist". Use `SKILL_BILL_REVIEW_DB`; `SKILL_BILL_DB` does nothing. An isolated home reports "Not ready: no agent found" on New task, as expected.

## Comments

Under `runtime-kotlin`, including `build-logic`, ban `//` and non-KDoc `/* */` comments. Allow `/** */` KDoc only on interfaces and their members, including nested types. Put irreducible why-only rationale in the owning `agent/decisions.md`. `CommentAndInterfaceKdocArchitectureTest` and `PrincipleEnforcementInventory.enforceableRules` enforce this.

## Coding Conventions

Before planning, designing, changing, or reviewing `runtime-kotlin`, read [Runtime Architecture Guidelines](docs/architecture-guidelines.md). Apply A1–A12, G1–G7, and P1–P8. Every runtime-kotlin review runs its section 5 checklist and cites rule IDs.

Before designing, changing, or reviewing `runtime-kotlin`, apply [Design Principles](runtime-kotlin/ARCHITECTURE.md#design-principles) for dependencies, state/resource ownership, persistence, contracts, simplicity, and test value. `PrincipleEnforcementInventory.enforceableRules` pairs mechanical rules with tests; others require review. Existing violations never authorize new ones.

Follow [Code Principles](docs/code-principles.md) for Kotlin patterns, package clustering, imports, file-size limits, and architecture guards. Guards live under `runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/`.

**Wire and payload keys.** At governed payload seams, never inline contract/wire map keys in `get`, `put`, `mapOf`, or bracket access. Declare each once in its owner's `*Keys` or `*PayloadKeys` object. See Wire vocabulary in `runtime-kotlin/ARCHITECTURE.md`. Use `runtime-contracts` only for keys read by at least two production modules or exposed by a `runtime-ports` signature. Shared owners:

- `SharedPayloadKeys`: workflow phase-output envelopes
- `DecompositionManifestPayloadKeys` and `DecompositionPlanningPayloadKeys`: decomposition manifests
- Area-owned keys beside their contract family, such as `ReviewVerificationSignalKeys`
- `TelemetryProxyPayloadKeys`: telemetry proxy wire vocabulary
- `LifecycleTelemetryPayloadKeys`: telemetry envelopes

Single-owner keys stay in that module, such as `SqliteReviewTelemetryPayloadKeys` in `runtime-infra/sqlite` and `GoalRunnerResetPayloadKeys` in `runtime-cli`. Never redeclare shared values. Consumers use constants, never wire strings. Enum tokens use the owning enum's `wireValue`, never restated local `setOf`/`mapOf` tokens. `WireVocabularyArchitectureTest` enforces seams through `WireVocabularyGovernedSeamInventory`.

## Quality Checks

Prefer `/skill-bill phase:validation`, CLI `skill-bill phase validation`. Like goal validate, it discovers and runs all checks from repository instructions, build configuration, scripts, and CI, then repairs failures. Compilation alone is insufficient. Use stable base commands, platform routers, explicit gate argv, validator-backed rules, and acceptance/rejection tests.
