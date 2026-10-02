# Internal Skills Architecture

How dispatch-only skills become one visible entry point: the `internal-for`
classification, the sidecar install mechanism, the file-read dispatch
contract, and where every piece of prose, runtime, and agent machinery lives.
This is the architecture companion to the authored contract in
[skill-source-generation.md](skill-source-generation.md) (Internal Skills
section), which owns the normative rules.

Two parts follow: the single listed skill (`skill-bill`, SKILL-383) and the
code-review family (SKILL-104, the platform-pack extension, re-parented to
`skill-bill` in SKILL-383).

## Part 1 — The single listed skill (SKILL-383)

### The idea in one paragraph

The catalog has one listed skill: `skill-bill`. It is a dispatcher. Its
full-run form resolves intake and launches the goal runtime, which owns new
work preparation, durable planning, and execution. Standalone phases and operations are operator tools;
agents invoke them only at the operator's explicit request. Its `phase:<name>`
forms run `skill-bill phase <name>`,
and its `operation:<name>` forms run `skill-bill operation <name>`. The
runtime owns preparation, continuation, execution, and durable goal state. The
text of the retired listed skills (`bill-feature`, `bill-feature-spec`,
`bill-code-review`, `bill-code-check`, `bill-pr-description`,
`bill-boundary-history`, `bill-boundary-decisions`, `bill-pr-review-fix`,
`bill-unit-test-value-check`, `bill-update-check`, `bill-release`,
`bill-feature-verify`, `bill-feature-guard`, `bill-feature-guard-cleanup`)
lives in `skills/skill-bill/content.md` and in engine directive resources under
`runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/`.
`bill-monitor` and the `bill-code-review-inline` worker were deleted.
`skill-bill goal status` stays CLI-only.

## What install produces

The agent skill list shows `skill-bill`. The symlink in the agent's skills
directory points into the content-addressed staging cache:

```
~/.claude/skills/skill-bill
  → ~/.skill-bill/installed-skills/skill-bill-<content-hash>/
      SKILL.md                            rendered governed wrapper — what the agent lists
      content.md                          authored source, copied verbatim
      bill-<platform>-code-review*.md     pack review sidecars (selected packs only)
      platform-packs → …                  symlink for pack pointer resolution
```

An install over a home that has the retired skills removes their links and
installed copies (`InstallLegacySkillNames`). Feature-task, goal, phase, and
operation work runs inside the Kotlin runtime (`skill-bill feature-task`,
`skill-bill goal`, `skill-bill phase`, `skill-bill operation`). Platform-pack
review packs still install their own native subagents outside the skills
directory.

## Where the source lives

```
skills/
  skill-bill/content.md                       listed — the single entry point
```

Workflow rows, the database `workflow_name` constraint, telemetry `skill`
values, the quality-check `routed_skill`, and MCP tool names keep their
durable identities, including retired skill names such as
`bill-feature-verify` and `bill-code-check`.

## How routing works, end to end

```
user: "/skill-bill APP-1 …" / "/skill-bill phase:review" / "/skill-bill operation:release bump:patch"
  │
  ▼
skill-bill                                       [listed]
  │  update check
  │  full run:  tracker link/key → connected tracker requirements
  │             raw requirements / existing spec key or path
  │               → skill-bill <intake> → goal runtime
  │               new work → runtime prepares spec and parent workflow
  │               existing spec → resume its durable goal
  │               durable planning → execution
  │  operator-requested phase:<name>      → skill-bill phase <name>
  │  operator-requested operation:<name>  → skill-bill operation <name>
  │                        awaiting_confirmation → one operator question → confirm:<token>
  ▼
runtime output → verbatim relay
```

The dispatcher forwards the issue key and caller-selected review, agent
add-on, and `key:value` tokens to the runtime without resolving another skill.

## How the install pipeline produces this

One classification, read through one parser, validated by one rule evaluator,
consumed at three seams:

- **Classification.** `parseInternalForFrontmatter` is the single place that
  reads the key (first occurrence wins; a blank value is preserved so it can
  fail loudly, never "treat as listed"). `InternalSkillClassification.kt`
  holds the one rule evaluator: parent must exist, must be a listed *base*
  skill, must not be the skill itself, no chaining (depth 1), and
  platform-pack skills cannot carry the key at all. Authoring discovery and
  install-plan building throw the first violation as a typed
  `InvalidInternalSkillClassificationError`; `skill-bill validate` reports
  every violation as an issue.
- **Planning.** `discoverBaseSkills` carries `internalFor` onto each
  `InstallPlanSkill`. Staging intents are only emitted for skills that
  actually stage standalone — internal skills get none.
- **Staging.** When a parent stages, `discoverInternalSidecarTargets` finds
  its children under the plan's skills root, renders each child's governed
  wrapper once (one authoring-discovery walk per staging operation), writes
  them as `<skill-name>.md` into the parent's staging dir, and folds the
  rendered bytes into the parent's **content hash**. That hash fold makes the
  system self-maintaining: editing a child's `content.md` invalidates the
  parent's cache entry and re-renders the sidecar on the next install. The
  hash section is appended only when children exist, so a repo with no
  internal skills produces byte-identical output to the pre-SKILL-102
  pipeline.
- **Linking.** Agent skills-dir symlinks are created only for non-internal
  skills. The direct path is guarded too: `skill-bill link-skill` against an
  internal skill's directory refuses with the same typed error.
- **Reuse and repair.** A cached staging dir is reused only if its hash
  marker, `SKILL.md`, *and every expected sidecar* are intact; anything
  pruned or half-written triggers a clean re-render. Uninstalling the parent
  removes its one symlink — the sidecars live inside the parent's staging
  dir, so there is nothing separate to clean up.

### Guardrails enforced at `skill-bill validate` time

- All classification rules above, as repo-validation issues.
- **Collision guard:** an authored file in a parent's source dir occupying
  a would-be sidecar name (e.g. an authored `skill-bill/bill-kotlin-code-review.md`)
  fails validation and staging (`InternalSkillSidecarCollisionError`).
- **Reference co-location:** every `` `<skill-name>.md` `` sidecar reference
  inside any skill's prose must resolve to an internal skill sharing the
  referencing skill's effective parent — so re-parenting or de-listing one
  family member breaks validate, not a live session with a file-not-found.
- **README catalog:** internal skills are exempt from the "every skill
  appears in the README catalog" rule, since they are intentionally not
  user-invocable.

## Why it is shaped this way

- **File-read over Skill tool** — forced by reality (no agent has an
  invocable-but-hidden state) and better anyway: sibling file reads are the
  most portable primitive across supported agents.
- **Sidecars inside the parent, not a shared hidden folder** — the parent's
  installed directory is the one location the parent can always resolve
  ("a sibling of this SKILL.md"), needs no per-agent path knowledge, and
  inherits the parent's install/uninstall/cache lifecycle for free.
- **Full wrapper, no trimmed format** (PD6) — the executed behavior had to be
  provably identical to the listed era.
- **Repo paths and identity frozen** (PD3/PD4) — runtime resume, the DB CHECK
  constraint, telemetry history, and MCP dispatch all bind to the old names
  and paths; the blast radius stayed inside the install pipeline and the
  skill prose.
- **One listed skill** (SKILL-383) — every retired skill's capability already
  existed as a phase, a strategy, or an operation, so a second listed entry
  would only duplicate a route the dispatcher has.

## File map

| Concern | Where |
|---|---|
| Classification rules + parser (single source) | `runtime-kotlin/runtime-infra/skills/…/scaffold/authoring/InternalSkillClassification.kt` |
| Authoring discovery (reads the key, validates) | `…/scaffold/authoring/AuthoringDiscovery.kt` |
| Install-plan discovery + plan-time validation | `…/install/plan/InstallPlanSkillDiscovery.kt`, `InstallPlanBuilder.kt` |
| Sidecar discovery + render-once carrier | `…/install/staging/InternalSkillSidecars.kt` |
| Hash folding, staging, reuse checks | `…/install/staging/InstallStaging.kt`, `InstallStagingIO.kt` |
| Standalone-install filter, native-agent roots | `…/install/apply/InstallApply.kt`, `InstallApplyNativeAgents.kt` |
| Direct link-skill guard | `…/install/plan/InstallPrimitives.kt` |
| Validate-time rules incl. sidecar references | `…/scaffold/runtime/RepoValidationRuntime.kt` |
| Typed errors | `runtime-kotlin/runtime-contracts/…/error/ShellContentContractErrors.kt` |
| Routing prose (the actual dispatch sentences) | `skills/skill-bill/content.md` |
| Authored contract (normative) | `docs/skill-source-generation.md` → Internal Skills |
| Tests | `InternalSkillStagingTest`, `InternalSkillClassificationTest`, `InstallPlanInternalSkillDiscoveryTest`, `RepoValidationRuntimeTest` |

## Part 2 — The code-review family (platform-pack internals, SKILL-104)

The same mechanism, extended to platform-pack skills. The code-review family
had stack-specific review skills listed to users even though the supported
entry point is the review phase (`/skill-bill phase:review`), which detects the
dominant stack from `platform.yaml` routing signals and routes automatically.
SKILL-104 hid them as sidecars of `bill-code-review`; SKILL-383 re-parented
them to `skill-bill` and retired `bill-code-review`. Hiding them removes
dozens of listed skills from every agent's skill list and makes the listed
surface match the actual product surface.

### What changed in the contract (and what did not)

Exactly one rule changed (PD1): platform-pack skills may now declare
`internal-for`. Every other classification rule is byte-for-byte unchanged —
blank value, self parent, unknown parent, parent must be a listed base skill
under `skills/`, no chained `internal-for` (depth is 1). The pack manifest
(`platform.yaml`) is never consulted for classification; there is no
manifest-level internality flag. The review-pack frontmatter additions are the
only authored source change in the family.

### Flatten rule (PD2)

All review-pack skills — the stack entries AND their specialists —
declare `internal-for: skill-bill`. Stack entry skills do **not** become
parents of their specialists. Nesting (specialists internal to their stack
entry, entries internal to `skill-bill`) would require depth-2 sidecars —
a sidecar hosting sidecars — which the staging model cannot express (a sidecar
is a file, not a directory). Flattening keeps depth at 1, and sibling
co-location is what the review flow wants: the routed entry sidecar and the
specialist rubrics it reads live in one directory.

### Selection-aware sidecars (PD3) and installed layout

A base-skill internal sidecar stages whenever its parent stages. A
platform-pack internal sidecar stages only when its pack is selected
(`PlatformPackSelection`: `NONE`/`SELECTED`/`ALL`). Sidecar discovery consults
the install plan's selected pack skills (each already carries `sourceDir` and
parsed `internalFor`) rather than re-scanning `platform-packs/` independently
of selection. The parent's content hash folds exactly the selected sidecars.

After a scratch install with all packs selected, `skill-bill`'s staged
directory contains `SKILL.md` plus 84 sibling sidecars — and no agent
`skills_dir` symlink exists for any manifest-discovered review sidecar:

```
~/.claude/skills/skill-bill
  → ~/.skill-bill/installed-skills/skill-bill-<content-hash>/
      SKILL.md                              rendered governed wrapper — the listed entry
      content.md                            authored source, copied verbatim
      bill-ios-code-review.md               sidecar: iOS stack entry (selected)
      bill-ios-code-review-api-contracts.md sidecar: iOS specialist
      ... (10 iOS specialists total)
      bill-go-code-review.md                sidecar: Go stack entry (selected)
      ... (10 Go specialists total)
      bill-kotlin-code-review.md            sidecar: Kotlin stack entry (selected)
      ... (10 Kotlin specialists total)
      bill-kmp-code-review.md               sidecar: KMP stack entry (selected)
      ... (7 KMP specialists; 3 effective lanes compose from Kotlin)
      bill-php-code-review.md               sidecar: PHP stack entry (selected)
      ... (10 PHP specialists total)
      bill-python-code-review.md            sidecar: Python stack entry (selected)
      ... (10 Python specialists total)
      bill-rust-code-review.md              sidecar: Rust stack entry (selected)
      ... (10 Rust specialists total)
      bill-typescript-code-review.md        sidecar: TypeScript stack entry (selected)
      ... (10 TypeScript specialists total)
      platform-packs → …                    symlink for pack pointer resolution
```

With only the Kotlin pack selected, its entry and ten review specialists stage; other packs contribute
nothing. With no review packs selected, `skill-bill` stages
byte-identically to a repo with no internal pack skills (inertness). `ALL`
selection stages every opted-in review sidecar. SKILL-105 applies the same
selection-aware review sidecar model. Quality checks do not use pack sidecars:
the selected dominant pack declares its collect-all and
cache-bypassing collect-all argv in `validation_gate`, and
`skill-bill phase validation` is the standalone quality-check entry point. It uses
the same agent validation strategy as goal validate and runs all required project
checks. Goal build uses the dominant pack's build argv. KMP owns its gate; review
baseline composition does not provide a quality-check fallback.

### Baseline co-presence guard (PD8)

The KMP pack declares `bill-kotlin-code-review` as a required baseline layer
(`platform-packs/kmp/platform.yaml`,
`code_review_composition.baseline_layers`). The KMP orchestrator reads that
baseline as a sibling sidecar at review time. Once both are sidecars, selecting
KMP without Kotlin would leave the baseline sidecar absent. Install planning
loud-fails with `MissingBaselinePlatformSelectionError` when the selection
includes a pack declaring a required baseline layer in an unselected pack;
there is no silent auto-include. `ALL` selection is trivially safe.

### Routing walkthrough

```
user: "/skill-bill phase:review" on a Kotlin diff
  │
  ▼
skill-bill → skill-bill phase review               [listed dispatcher → runtime]
  │  reads platform.yaml routing signals from the diff
  │  (strong signals, then tie-breakers) → dominant pack
  ▼
read sibling bill-kotlin-code-review.md            [internal sidecar]
  │  the routed pack entry sidecar; reads its specialist rubric
  │  selection table (signal → area) and spawns specialists
  │
  ├── read sibling bill-kotlin-code-review-architecture.md   [internal sidecar]
  ├── read sibling bill-kotlin-code-review-security.md       [internal sidecar]
  ├── read sibling bill-kotlin-code-review-testing.md        [internal sidecar]
  └── ... per the signal table
  │
  ▼
  each specialist's rubric executes; findings merge into the
  review summary, risk register, and verdict
```

For a KMP diff, the routed entry is `bill-kmp-code-review.md`, which also reads
`bill-kotlin-code-review.md` as its baseline layer sidecar before its own
specialists. Delegated specialist subagents and parallel lanes
receive rendered runtime instructions and rubric content/paths from the parent
orchestrator — no worker ever resolves one of the platform-pack sidecars via the Skill tool or a
standalone `skills_dir` path (PD5).

### Selection-shaped variance at a glance

| Selection | Sidecars staged inside `skill-bill/` |
|---|---|
| `ALL` | 85 (8 stack entries + 77 specialists) |
| Kotlin only | 11 (`bill-kotlin-code-review.md` + 10 specialists) |
| KMP only | fails — Kotlin is a required baseline (PD8) |
| KMP + Kotlin | 19 (`bill-kmp-code-review` + 7 KMP specialists, `bill-kotlin-code-review` + 10 Kotlin specialists) |
| None | 0; `skill-bill` stages inert (byte-identical to no pack internals) |

### File map additions (platform-pack side)

| Concern | Where |
|---|---|
| Relaxed rule (pack skills may carry `internal-for`) | `runtime-kotlin/runtime-infra/skills/.../scaffold/authoring/InternalSkillClassification.kt` |
| Selection-aware sidecar discovery | `.../install/staging/InternalSkillSidecars.kt` (consults `InstallPlanSkill.sourceDir`) |
| Baseline co-presence guard | `.../install/plan/InstallPlanPolicy.kt`, `MissingBaselinePlatformSelectionError` in `ShellContentContractErrors.kt` |
| Pack-internal README catalog exemption | `.../scaffold/runtime/RepoValidationRuntime.kt` (`validateReadme`) |
| Authored pack source (unchanged paths) | `platform-packs/{go,ios,kotlin,kmp,php,python,rust,typescript}/code-review/<skill>/content.md` |
| Tests | `InternalSkillStagingTest`, `InternalSkillClassificationTest`, `InstallPlanInternalSkillDiscoveryTest`, `MissingBaselinePlatformSelectionTest`, `RepoValidationRuntimeTest` |
