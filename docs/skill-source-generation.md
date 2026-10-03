# Skill Source And Generation Model

This document explains the governed source model for Skill Bill skills and the
generated runtime artifacts produced by render, scaffold, install, and native
agent flows.

Use it when changing anything under `skills/`, `platform-packs/`,
`orchestration/`, `runtime-kotlin/.../scaffold`, `runtime-kotlin/.../install`,
or native-agent rendering.

## Core Principle

`content.md` is the authored source. `SKILL.md` and support pointer files are
generated runtime artifacts.

The repository should be easy to review and hard to drift:

- authors edit concise governed source files
- render and install generate runtime-complete skill directories
- validators reject generated artifacts committed back into source
- installed agents see complete `SKILL.md` wrappers and support files without
  source directories carrying generated files

This is also a product UX rule, not just repository hygiene. Authors should see
and edit only the material they own. Wrapper ceremony, runtime wiring, pointer
files, and provider-specific agent artifacts are the system's responsibility.

## Source Layout

Canonical skills under `skills/` are source-only directories. The shipped tree
is `skills/skill-bill/`, the only listed skill. Phases and operations are forms
of it (`phase:<name>`, `operation:<name>`), not separate skills; the text of the
retired listed skills lives in `skills/skill-bill/content.md` and in engine
directive resources under
`runtime-kotlin/runtime-engine/src/main/resources/skillbill/engine/`.

Allowed files under each `skills/<skill>/` directory:

- `content.md`
- `native-agents/` when the skill owns provider-neutral native-agent sources
- explicit authored sidecars only when a documented governed contract allows
  them

Allowed files under `skills/<skill>/native-agents/`:

- `agents.yaml` for a bundled list of native agents
- `<agent-name>.md` for one standalone provider-neutral native-agent source

Do not commit these under `skills/`:

- `SKILL.md`
- generated support pointer files, including ceremony pointers such as
  `peak-hours-warner.md` and `shell-ceremony.md`, telemetry pointers such as
  `telemetry-contract.md`, and stack-routing, review, delegation, add-on, or
  Android support pointer files
- provider-specific generated native-agent directories such as
  `claude-agents/`, `codex-agents/`, `junie-agents/`, or
  `cursor-agents/`
- extra organization files such as `patterns.md`, `reference.md`, or
  `audit-rubrics.md`

If authored guidance needs more sections, put them in `content.md` as normal
Markdown H2 sections.

Platform packs use their own source layout:

- `platform-packs/<slug>/platform.yaml`
- `platform-packs/<slug>/code-review/<skill>/content.md`
- optional `validation_gate` on `platform.yaml` for dominant-stack quality-check argv (legacy `declared_quality_check_file` may still parse but is unused by routing, install, and scaffold)
- `platform-packs/<slug>/addons/*.md` for pack-owned add-ons
- one co-located, specialist-owned authored Markdown rubric sidecar when the
  review-skill structure standard explicitly permits and names it; it belongs
  to that specialist, is declared by its `content.md`, and is not a generated
  pointer or a general-purpose organization file

Platform pack pointer files declared by `platform.yaml` are generated output,
not source files.

The authored-sidecar exception is narrow: do not use it for wrapper ceremony,
telemetry, routing, provider-native output, or arbitrary `patterns.md`,
`reference.md`, or `audit-rubrics.md` files. Put ordinary authored guidance in
`content.md` H2 sections.

Pack-owned add-ons are consumed through manifest-owned declarations, not
hand-authored skill prose. `addon_usage` maps review/check skill-relative
directories to add-on entry pointers and optional companion pointers the
installed skill may open after stack routing. `feature_addon_usage` maps
feature-task consumers to the same pack-owned add-on model. The renderer turns
those contracts into generated wrapper sections, and the loader rejects add-on
usage that does not reference generated pointers targeting the same pack's
`addons/` directory.

## `content.md`

`content.md` is the default authored skill body for governed skills, except
for documented governed sidecar contracts. It owns:

- YAML frontmatter with `name` and `description`
- task-specific execution guidance
- workflow phase instructions and structured result contracts
- inline reference material that belongs to the skill
- links or cues for generated support pointers that will exist in installed
  staging

The frontmatter also accepts one optional classification key,
`internal-for: <parent-skill-name>`. Presence classifies the skill as
**internal** — see the Internal Skills section below for the full contract.

## Internal Skills

This section is the normative contract; for the end-to-end architecture and
routing walkthrough see
[internal-skills-architecture.md](internal-skills-architecture.md).

A **listed** skill installs as a `SKILL.md` entry in each agent's `skills_dir`
and is invocable by users via the Skill tool. The shipped catalog lists exactly
one, `skill-bill`. A skill can
instead be classified **internal**: its governed content still installs, but as
a markdown sidecar inside another (parent) skill's installed directory, and it
is never listed or directly invocable. Internal skills exist because some
governed content is a dispatch target selected by a parent router, not a user
entry point, and listing every dispatch target dilutes trigger-phrase matching
and misrepresents the product surface.

### Frontmatter contract (PD1)

A skill is internal when its `content.md` frontmatter carries exactly one
optional key:

```yaml
internal-for: <parent-skill-name>
```

Presence of the key makes the skill internal; absence means listed. There are no
other frontmatter keys, no `config.yaml` switches, no per-agent overrides, and no
manifest-level internality flag — the platform pack manifest (`platform.yaml`)
is never consulted for classification. Both base skills under `skills/` and
platform-pack skills under `platform-packs/<slug>/` may carry the key; the only
parent-side restriction is that the parent itself must be a **listed base
skill** (`skills/<parent>/`), never a platform-pack skill and never another
internal skill. One shared rule evaluator backs the authoring seam, the
install-plan seam, and `skill-bill validate`, so the same declaration fails
identically everywhere it is read.

### Parent rules and loud-fail behavior

The pipeline loud-fails with a typed, actionable error
(`InvalidInternalSkillClassificationError`; the same rules surface as issues at
`skill-bill validate` time) when an internal skill:

- has a **missing** value — the `internal-for` key is present with no value;
- declares an **unknown** parent — no discovered skill matches the name;
- declares a parent that is **itself internal** — chaining is not allowed;
  depth is 1, so a sidecar cannot host another sidecar;
- declares **itself** as parent;
- declares a **platform-pack** parent — the parent must be a listed base skill.

A separate collision guard (`InternalSkillSidecarCollisionError`) fails staging
when an authored file in the parent's source directory already occupies a
would-be sidecar name (e.g. an authored `skill-bill/bill-kotlin-code-review.md`);
`skill-bill validate` surfaces the same collision before install. Validation
also checks every `` `<skill-name>.md` `` sidecar reference inside a skill's
content.md: the referenced skill must be internal and share the referencing
skill's effective parent, or the reference would break at session time with a
file-not-found.

Direct installs of an internal skill are refused: `skill-bill link-skill`
against an internal skill's directory fails with the same typed error and
directs to the parent, so an internal skill can never gain its own
`skills_dir` entry through a side path.

### Installed layout (PD2 / PD6)

Install renders an internal skill's governed content as a markdown sidecar named
`<skill-name>.md` (the full skill name, e.g. `bill-kotlin-code-review.md`, never an
abbreviated form) placed at the top level of the parent skill's installed
directory, next to the parent's `SKILL.md`. The sidecar carries the same
governed wrapper a listed skill's `SKILL.md` would carry (frontmatter,
descriptor, class sections, `## Execution` body, ceremony) — behavior parity
over token savings; there is no trimmed sidecar format. Critically, install
creates **no `SKILL.md` directory entry** for the internal skill in any agent's
`skills_dir`: the agent skill list never contains an internal skill, and the
sidecar's bytes are folded into the parent's content hash so reinstall
re-renders and idempotency holds. Cache reuse re-verifies that every expected
sidecar file is present, so an externally pruned sidecar triggers a re-render
instead of being reused broken.

### Flatten rule for multi-level families (PD2)

When a family has more than one logical level — a router parent, stack entry
skills, and area specialists — every internal member declares the **same
parent** rather than chaining through the intermediate level. Depth stays at 1
(PD1: a sidecar is a file, not a directory; the staging model cannot express a
sidecar hosting another sidecar), and sibling co-location is exactly what a
router flow needs: the parent's installed directory holds the routed entry
sidecar and the specialist sidecars it reads as siblings, all resolvable as
"a file next to this `SKILL.md`" with no per-agent path knowledge. The
code-review family is the worked example: 84 review-pack skills — eight stack
entries plus their 77 specialists — all carry `internal-for: skill-bill`
and all install as siblings inside `skill-bill/`. The stack entry skills
do **not** become parents of their specialists.

The maintained manifest-derived totals are 85 review sidecars and 77
provider-neutral specialist agents. KMP accounts for one review entry, seven
physical specialists, and seven neutral agents; its three inherited review
lanes reuse Kotlin rather than duplicating Kotlin source or native-agent
declarations. Quality checks have no pack sidecars; their commands live in each
dominant pack's `validation_gate`.

### Selection-aware sidecars for platform-pack internals (PD3)

A base-skill internal sidecar stages whenever its parent stages, because base
skills are always installed. A platform-pack internal sidecar stages **only when
its pack is selected** (`PlatformPackSelection`: `NONE`/`SELECTED`/`ALL`). The
staging sidecar discovery consults the install plan's selected pack skills
(which already carry each skill's `sourceDir` and parsed `internalFor`) rather
than re-scanning `platform-packs/` independently of selection. The parent's
content hash folds exactly the selected sidecars, so changing platform selection
re-stages the parent and cache reuse stays correct. With `NONE` of a parent's
internal packs selected, the parent stages byte-identically to a repo with no
internal pack skills — unselected packs contribute no sidecar and no hash
bytes. `ALL` selection stages every opted-in internal sidecar.

### Baseline co-presence guard (PD8)

A pack manifest may declare required baseline layers
(`code_review_composition.baseline_layers[].required = true`, resolved as a
sibling sidecar at review time — see the KMP pack's `bill-kotlin-code-review`
baseline). Once both layers are internal sidecars, selecting the dependent pack
without its baseline pack would leave the baseline sidecar missing at review
time. Install planning therefore loud-fails with a typed error
(`MissingBaselinePlatformSelectionError`) when the selection includes a pack
that declares a required baseline layer in an unselected pack. There is no
silent auto-include — consistent with the shell's never-silently-substitute
ethos. `ALL` selection is trivially safe because every required baseline is
selected too.

### File-read invocation contract (PD5)

The Skill tool on every supported agent resolves only listed skills; there is no
invocable-but-hidden state. A listed parent therefore invokes an internal skill
by **reading the sidecar file from its own installed directory** (a sibling file
read) and executing its instructions in the current session, passing the same
argument conventions as before (issue key, spec path).
The Skill tool is never used
to invoke an internal skill. This file-read pattern is already established for
other sibling sidecars (`shell-ceremony.md`, `compose-guidelines.md`) and is
more portable across agents than Skill-tool mechanics.

The contract extends verbatim to review routing, where the dispatch target is
itself an internal sidecar. The routed dispatch resolves the dominant pack's
entry sidecar (e.g. `bill-kotlin-code-review.md`); a stack orchestrator sidecar
resolves its specialist rubrics as sibling sidecars (e.g.
`bill-kotlin-code-review-security.md`); and a KMP baseline layer resolves
`bill-kotlin-code-review.md` as a sibling sidecar. Delegated specialist subagents keep
receiving rendered runtime instructions and rubric content/paths from the parent
orchestrator — no worker ever resolves a hidden skill via the Skill tool or a
standalone `skills_dir` path.

### Worked example: the single listed skill

The catalog has one listed skill, `skill-bill`. Its full-run form calls the goal
runtime directly with raw requirements, an existing spec key or path, or a
tracker reference. The dispatcher searches local specs including ignored files
and checks repository-matched persisted workflows before fetching a new tracker
reference through its connected tracker. Existing work resumes without replacing
its requirements. The runtime prepares a missing
spec and parent workflow before durable planning; the dispatcher never invokes
`skill-bill phase plan` on its own.
Standalone phases and operations are operator tools and require an explicit
operator request. Its `phase:<name>` forms run `skill-bill phase <name>`
and its `operation:<name>` forms run `skill-bill operation <name>`; there is no
feature goal sidecar and no other listed entry. `skill-bill goal status` stays
CLI-only. An install over an old home removes the retired skills' links and
installed copies (`InstallLegacySkillNames`). The runtime workflow identity,
database constraint, telemetry `skill` values, the verify `workflow_name`, the
quality-check `routed_skill`, and MCP tool names remain durable contracts and
keep retired names such as `bill-feature-verify` and `bill-code-check`.

### Worked example: the code-review family

The code-review family is the platform-pack worked example. Every manifest-discovered
review-pack skill is internal — all entries under
`platform-packs/{go,ios,kotlin,kmp,php,python,rust,typescript}/code-review/`: eight stack entry skills
(`bill-ios-code-review`, `bill-kotlin-code-review`, `bill-kmp-code-review`,
`bill-go-code-review`, `bill-php-code-review`, `bill-python-code-review`, `bill-rust-code-review`,
`bill-typescript-code-review`) plus their area specialists. Go, iOS, Kotlin, PHP, Python, Rust, and TypeScript declare all ten areas; KMP covers Android and Kotlin Multiplatform, declaring seven areas (`architecture`, `platform-correctness`, `security`, `persistence`, `reliability`, `ui`, `ux-accessibility`) and composing the remaining three (`performance`, `testing`, `api-contracts`) from Kotlin. All carry
`internal-for: skill-bill` and install as siblings inside
`skill-bill/`'s staged directory; the eight stack entries do **not** become
parents of their specialists (PD2 flatten rule). After install with all packs
selected, the agent skill list shows only `skill-bill` and none of those
internal sidecars. The review phase (`/skill-bill phase:review`) reads the
dominant pack's entry sidecar, which reads its
specialist rubric sidecars as siblings. With only the Kotlin pack selected,
the Kotlin entry plus its ten specialists stage; other packs contribute nothing (PD3). The KMP pack declares
`bill-kotlin-code-review` as a required baseline layer, so selecting KMP without
Kotlin fails install planning with the typed baseline-co-presence error (PD8).
Quality checks have no pack skill sidecars. A dominant pack's `validation_gate`
owns its build and collect-all argv. `skill-bill phase validation` uses the same
agent validation strategy as goal validate and discovers all required project
checks from repository configuration. `bill-code-check` remains the pack gate
`routed_skill` telemetry identity.

`content.md` must not contain generated wrapper headings:

- `## Descriptor`
- `## Execution`
- `## Ceremony`

Those headings belong to generated `SKILL.md` wrappers.

`content.md` should also avoid copying shared shell ceremony, telemetry, stack
routing, review orchestration, delegation contracts, and ceremony pointer
instructions inline. Those shared contracts live under `orchestration/` and are
exposed to installed skills via generated support pointers.

Preferred authoring paths:

- `skill-bill show <skill>`
- `skill-bill explain [skill]`
- `skill-bill fill <skill> --body-file <file>`
- `skill-bill fill <skill> --section <heading> --body-file <file>`
- `skill-bill validate`
- `skill-bill render <skill>` for read-only render checks

Manual edits to `content.md` are acceptable for maintainer work, but ordinary
skill authoring should use the CLI so validation and rollback behavior stay in
the loop.

## Generated `SKILL.md`

`SKILL.md` is runtime-facing output. It is rendered from:

- frontmatter in `content.md`
- generated descriptor metadata
- the authored body rendered into `## Execution`
- generated ceremony text in `## Ceremony`

The renderer is implemented in `runtime-kotlin/runtime-infra/skills` around
`AuthoringRender.kt` and supporting scaffold renderers.

Important rules:

- Do not commit generated `SKILL.md` under `skills/`.
- Do not edit generated `SKILL.md` during normal skill authoring.
- Change renderer code and tests when wrapper shape changes.
- Use `skill-bill render` to inspect generated output.
- Install staging writes `SKILL.md` into the installed cache.

## Generated Support Pointers

Installed skills need local filenames such as `peak-hours-warner.md`,
`shell-ceremony.md`, `telemetry-contract.md`, `stack-routing.md`,
`review-orchestrator.md`, and pack add-on pointers. These files are generated
into installed staging rather than committed under source skill directories.

There are three pointer families:

- Static runtime support pointers for canonical `skills/` entries. Their
  targets are defined by `requiredSupportingFilesForSkill` and
  `supportingFileTargets` in `ScaffoldSupport.kt`.
- Platform pack manifest pointers declared in each pack's `platform.yaml`.
- Ceremony pointers declared by skill-class YAML files under
  `orchestration/skill-classes/*.yaml`.

Skill-class YAML files use `matchers` to select applicable skills. A matcher
with `exact` applies to one skill name, while a matcher with `pattern` applies
to skill names matching that regular expression. The `pointers` list names the
generated sibling pointer files installed with every matching skill, and
`ceremony_lines` are injected into the rendered `## Ceremony` section. The
pointer files themselves are install-staging output and must not be committed
under `skills/` or platform-pack source skill directories.

Worked example: `orchestration/skill-classes/feature-task.yaml` matches
`^bill-[a-z0-9-]*feature-task$` and injects ceremony lines for
`peak-hours-warner.md`, `shell-ceremony.md`, and `telemetry-contract.md`.
`orchestration/skill-classes/feature-launch-warning.yaml` exactly matches
`skill-bill`; it injects the `peak-hours-warner.md`
ceremony line. `peak-hours-warner.md` is an operator launch warning/config
pointer, so warning behavior comes from the operator-facing configuration
surface rather than hard-coded product-specific wording in each skill.

Install staging materializes support pointer files as normalized relative
pointer text. Targets must be regular files inside the repo and must not point
back to themselves. Missing target files fail install/render validation.

If a skill needs a new support file:

1. Put the canonical source under `orchestration/` or the owning
   `platform-packs/<slug>/addons/` directory.
2. Register the support pointer target or manifest pointer.
3. Add validator and install-staging coverage.
4. Do not add the generated pointer file under `skills/<skill>/` or a
   platform-pack source skill directory.

If a platform-pack skill needs governed review/check add-ons, declare their
generated pointer filenames in the pack's `addon_usage` block. If feature-task
flows need governed add-ons, declare them in `feature_addon_usage`. Keep add-on
selection cues and topic indexes in the pack-owned add-on files, not duplicated
in `content.md`.

## Install Staging

Content-managed skills install through staging.

Install flow:

1. Resolve the source skill directory.
2. Render `SKILL.md` with the full inlined `## Execution` body from source
   `content.md`.
3. Copy other authored companions such as `native-agents/` (source
   `content.md` stays in the content-hash inputs and is not staged).
4. Generate platform pointers and static support pointers.
5. Compute a content hash from authored files (including source `content.md`)
   and pointer target declarations.
6. Promote an atomic staging directory under the Skill Bill installed-skills
   cache.
7. Link each selected agent's skill entry to the staged directory.

The installed directory is runtime-complete and may contain:

- `SKILL.md`
- `.content-hash`
- generated support pointer files
- generated platform pointer files
- copied `native-agents/` source files
- internal-skill sidecars (`<skill-name>.md`) for any internal skills that
  declare this skill as their parent via `internal-for`; these are rendered
  into the parent's staged directory and folded into the parent's content
  hash, and the internal skills themselves get no `skills_dir` entry

Listed-skill staging does not ship a verbatim `content.md` copy; agents read
the inlined body from `SKILL.md`. Source-tree `content.md` remains the
authored surface for show/explain/render/validate/fill/edit and native-agent
composition.

Agent install directories therefore point to staging, not directly to the repo
source skill directory.

Re-run `./install.sh` after source, renderer, or support pointer changes so
installed agents receive a fresh staging hash.

The terminal installer uses this same
runtime-owned install plan/apply path. Their choices may change which agents,
platform packs, telemetry level, or MCP registrations are applied, but they do
not change the governed source shape. Base skills remain part of every install;
optional platform packs are selected from manifests; generated wrappers,
support pointers and provider-native agent files
remain install/render artifacts instead of authored source.

Review-capable install plans include the unique manifest-declared
`code-review` fallback pack in addition to explicitly selected concrete packs.
The fallback slug is never inferred. Zero declarations preserve the horizontal
base installation and multiple declarations fail manifest coherence checks.
Provider-neutral native-agent declarations from the resolved selection are
rendered into an installed cache and recorded in durable link inventory;
preflight resolves logical workers exclusively from that inventory.

## Native-Agent Generation

Provider-neutral native-agent sources live under `native-agents/` in source
skill directories or platform pack skill directories.

Supported source forms:

- `native-agents/<agent-name>.md`
- `native-agents/agents.yaml`

Native-agent sources can define their own body or use:

```yaml
compose: governed-content
```

`compose: governed-content` means the provider-specific native-agent artifact
is composed from the matching governed `content.md`, producing a self-contained
provider artifact at install time. The generated artifact must not depend on
repo-local `content.md` at runtime.

Newly scaffolded and rendered provider-neutral sources include a top-level
`contract_version: "0.1"` pin. The native-agent schema keeps that key optional
for legacy parsing tolerance, but when it is present the value is pinned to
`NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION` by schema validation and parity
tests.

Install renders provider-specific artifacts into the Skill Bill native-agents
cache, then links them into runtime-specific directories:

- Claude: Markdown/YAML custom agents under `~/.claude/agents/`
- Codex: TOML custom agents under `~/.codex/agents/`
- Junie: Markdown/YAML custom agents under `~/.junie/agents/`
- Cursor: Markdown/YAML custom agents under `~/.cursor/agents/`

`~/.agents/agents/` exists only as a Skill Bill compatibility path for Codex
homes that do not have a `.codex` root. It is not the primary documented Codex
custom-agent location.

Generated provider artifacts are never committed to the repo.

Delegated-review worker rules are authored once in
`orchestration/review-orchestrator/specialist-contract.md`. Its marked launch,
evidence-surface, and report-structure blocks plus forbidden-rediscovery list
are projected into runtime, CLI, and provider-native launches. Generated
support pointers and provider artifacts may deliver those rules, but governed
`content.md` and delegation playbooks must not maintain divergent copies.
`ReviewPacketConsumerContractParityTest` owns byte/list parity with the runtime
constants.

## Scaffolding

The scaffolder is the supported way to create new governed source.

Entrypoints:

- `skill-bill new`
- `skill-bill new --interactive`
- `skill-bill new --payload <file>` for scripted automation

Supported scaffold kinds:

- `horizontal`: creates `skills/<name>/content.md`
- `platform-pack`: creates `platform-packs/<slug>/platform.yaml`, baseline
  code-review content and specialist content stubs for every approved
  code-review area. Quality-check commands belong in the optional
  `validation_gate`. Payloads may also declare `baseline_layers`;
  the scaffolder validates those references before mutation and writes them as
  `code_review_composition.baseline_layers` in the new manifest.
- `add-on`: creates one pack-owned skeleton add-on under
  `platform-packs/<slug>/addons/`

Retired partial scaffold kinds are rejected for new creation:
`platform-override-piloted`, `platform-override`, `override`,
`code-review-area`, `area`, and `specialist`. The error recommends creating a
full `platform-pack`, or editing/removing existing pack content through normal
authoring and removal flows. Existing platform override and code-review area
source files remain discoverable, renderable, installable, validatable, and
removable.

Scaffolding writes source files only. It does not stage generated `SKILL.md`
wrappers or support pointer files into source.

Use `skill-bill new --dry-run` for the guided path, or
`skill-bill new --payload <file> --dry-run` for scripted payloads, to inspect
planned files and manifest edits before mutation. For platform-pack composition
payloads, dry-run output includes a preview of the generated `platform.yaml` block. After
creation, `skill-bill show <baseline-skill>` surfaces any manifest-declared
review composition so users and agents can understand which baseline review
layers run. New platform-pack scaffolds are full packs; remove unwanted focus
areas afterward through governed removal paths. Legacy platform-pack payloads
that omit `baseline_layers` remain valid and generate no
`code_review_composition` section.

For add-ons, the normal wizard creates a skeleton markdown file and registers
the generated pointer through the owning pack's manifest-owned add-on usage.
When `addon_location_path` is provided, the wizard instead creates the skeleton
in that external add-on source directory and creates or updates the source's
`addon-manifest.yaml`; the owning pack still provides the validated/defaulted
consumer skill directories. It does not ask for body text or raw consumer
directories. The authoring sequence is: create the skeleton, edit the generated
add-on markdown file, then validate/render/install through the normal repo
checks. The installer registers external add-on sources in
`external_addon_sources` after successful creation so the app tree and install
overlay can discover them; scripted callers that invoke the raw scaffold payload
directly still need to register the source themselves. Scripted payloads may
still provide `body` or `consumer_skill_dirs` for deterministic automation.
Omitted consumers default to the owning pack
baseline when one exists, otherwise to the pack's only manifest-declared skill
directory; packs with no unambiguous default require scripted
`consumer_skill_dirs` and fail before any add-on file or manifest mutation.
These fields are advanced inputs and explicit consumers are validated before
any add-on file or manifest mutation.
Do not hand-author per-skill add-on usage tables in `content.md`.

When `subagent_specialists` are requested for orchestrator scaffolds, the
scaffolder writes `native-agents/agents.yaml` stubs. Render and install output
generate the runtime spawn notes into `SKILL.md`; authors must fill the TODOs
before shipping.

The scaffolder is atomic: if validation, manifest updates, install, or rollback
fails, it must not leave partial source changes.

## Validation And Guards

Use the normal authoring check before shipping authored content changes:

```bash
skill-bill validate
```

Use the full maintainer gate before shipping runtime, scaffold, contract, docs,
or agent-config changes:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

Key guards:

- source skill directories under `skills/` may contain `content.md`, optional
  `native-agents/`, and explicit authored sidecars only when a documented
  governed contract allows them
- committed generated `SKILL.md` wrappers are rejected
- committed generated support pointer files are rejected
- committed provider-specific native-agent artifacts are rejected
- platform manifests must match shell contract version `1.8`
- manifest-declared files must exist and be valid `content.md`
- pointer target parity is validated against platform manifests
- native-agent composition must render self-contained provider output

## Changing The Generation System

When changing wrapper, support pointer, install, scaffold, or native-agent
generation:

1. Update the generator code.
2. Update validators so stale source shapes fail loudly.
3. Update tests and golden fixtures.
4. Update `docs/skill-source-generation.md`, `AGENTS.md`, and onboarding docs
   if the source/runtime boundary changes.
5. Run the full validation gate.
6. Rebuild packaged distributions with `(cd runtime-kotlin && ./gradlew installDist)`.
7. Re-run `./install.sh` or the appropriate install commands to refresh local
   staged installs and native-agent links.

## Practical Do And Do Not

Do:

- edit authored skill guidance in `content.md`
- keep support contracts under `orchestration/`
- keep add-ons under `platform-packs/<slug>/addons/`
- use generated support pointers for installed runtime files
- use `native-agents/agents.yaml` for bundled orchestrator native agents

Installed provider-native agents are tracked in
the user-home `.skill-bill/native-agent-link-inventory.json`. Each entry records the provider, logical worker,
installed link, cache target, source root, and rendered-content digest. Install reconciliation uses
the prior complete inventory to remove obsolete or dangling Skill Bill-managed links and preserves
regular files and links outside managed cache roots. Delegated review preflight verifies the selected
workers against this inventory before launch; its typed failure carries `skill-bill install apply` as
the repair command.
- validate after every source-shape or generation change

Do not:

- commit `skills/<skill>/SKILL.md`
- commit support pointer files under `skills/<skill>/`
- split ordinary skill guidance into extra files under `skills/<skill>/`
- commit `claude-agents/`, `codex-agents/`,
  `junie-agents/`, or `cursor-agents/`
- manually edit generated runtime staging directories as source
- rely on direct source symlinks for content-managed skills

# Agent add-on authored sources

Agent add-ons are a separate user-owned extension surface under
`agent-addons/<slug>/`. Each source directory contains exactly
`agent-addon.yaml` and `content.md`. The manifest declares the contract version,
slug, description, target agent ids, and consumers; `content.md` is the only
ordinary instruction body.

Agent add-ons may also be sourced from external add-on roots declared in the
shared machine-global `external_addon_sources` config. Use
`kind: "agent-addon"` and point `path` at a directory containing the same
`<slug>/agent-addon.yaml` plus `<slug>/content.md` source shape. Legacy platform
entries without `kind` remain platform-pack add-ons; new platform entries may
use `kind: "platform-pack"`.

```json
{
  "external_addon_sources": [
    { "kind": "agent-addon", "path": "~/dev/personal-agent-addons" }
  ]
}
```

The manifest is strict: `contract_version: "1.0"`, a canonical `slug`, a
single-line `description`, one or more `agent_ids`, and one or more supported
`consumers`. Invocation is explicit, for example
`/skill-bill SKILL-122 agent-addon:execution-budget`. The receiving agent must
be declared; `execution-budget` supports Codex only. With no `agent-addon:`
token the feature workflow is unchanged.

Selected add-ons are additive and remain below user intent, `AGENTS.md`,
governed skill instructions, and repository contracts. Rendering and install
staging create consumer-owned `agent-addon-<slug>.md` pointers outside the
authored source; never edit or commit those generated files. This differs from
platform add-ons, which are pack-owned files under
`platform-packs/<slug>/addons/` and are selected by platform routing.

Durable workflow state records the ordered slug, canonical manifest identity,
and content digest. Resume reuses that exact selection and fails loudly when
the source is missing or changed instead of silently substituting content.
Agent add-ons should not automatically activate or configure themselves for a
specific model version, and must not prescribe manual context-window or
compaction controls.

Generated `SKILL.md` wrappers, support pointers, provider-native output
directories, native-agent sources, and arbitrary sidecars are forbidden in an
agent add-on source directory. Agent add-ons are distinct from governed skills
under `skills/` and pack-owned add-ons under `platform-packs/<slug>/addons/`.

## Runtime Handoff Terminology

Governed feature-task content describes four distinct inputs: private producer
evidence, the exact prompt-visible consumer projection, repository checkpoint
state, and allowlisted phase-local instructions. Do not describe these as
complete artifact inheritance or agent-selected retrieval. Handoff declarations
and delivered persistence records are versioned runtime contracts; projection
budgets are enforced before launch without truncation or full-artifact fallback.

## Required rubric companions

A pack may declare `required_rubric_companions` as a mapping of declared review area to Markdown filenames. Each file belongs in that specialist's directory beside `content.md`. The loader rejects missing or unreadable files, directory escapes, repeated filenames, and guidance exceeding 256 KiB per specialist. Runtime rubric composition and native-agent rendering include these files before optional add-ons. Workers receive the bodies in their launch and do not read pack files through the evidence broker.

Refresh generated output after changing this declaration or its guidance. A goal child that forbids installation must leave that refresh to an authorized external run and report it as an acceptance prerequisite.
