---
name: scaffold-payload
description: Payload schema for the new-skill scaffolder (SKILL-15). Documents the JSON contract consumed by `skill-bill new --payload`, the `new_skill_scaffold` MCP tool, and desktop/runtime scaffold callers.
---

# Scaffold Payload Contract

This is the canonical payload schema for scripted scaffold callers. The human
CLI path is `skill-bill new`, which collects deterministic prompts and then
builds this same payload internally. Payload callers such as
`skill-bill new --payload`, the `runtime-mcp` `new_skill_scaffold` tool, and
desktop/runtime scaffold integrations must ship a payload that conforms to this
schema. Mismatches raise specific named exceptions and abort the run; no silent
coercion.

## Versioning

- Required key: `scaffold_payload_version`.
- Current value: `"1.0"`.
- Any payload that declares a different version raises
  `ScaffoldPayloadVersionMismatchError`. Bump the scaffolder and every caller
  in lockstep when the contract changes.
- Version follows `MAJOR.MINOR`. Minor changes are additive; major changes
  are breaking.

## Required Keys

Every payload MUST include:

- `scaffold_payload_version` — exact match for the scaffolder's expected
  version string.
- `kind` — one of:
  - `"horizontal"` — placed under `skills/<name>/content.md`.
  - `"platform-pack"` — creates a new `platform-packs/<slug>/` root with a
    baseline `code-review` skill, approved specialist stubs, and a freshly
    rendered `platform.yaml` (quality-check commands belong in optional
    `validation_gate`, not a second pack skill).
  - `"add-on"` — placed at `platform-packs/<platform>/addons/<name>.md` (flat; no
    sub-directory) unless `addon_location_path` is provided.
  - `"agent-addon"` — creates `agent-addons/<slug>/agent-addon.yaml` and
    `agent-addons/<slug>/content.md` as one atomic scaffold transaction.
- `name` — the canonical `bill-...` slug for a new horizontal skill. For
  `platform-pack`, the scaffolder derives canonical names when this key is
  omitted; if provided, the value must still match the canonical shape.

Retired partial creation kinds are rejected before scaffold execution:
`"platform-override-piloted"`, `"platform-override"`, `"override"`,
`"code-review-area"`, `"area"`, and `"specialist"` raise
`RetiredScaffoldKindError`. Create a full `platform-pack`, or edit/remove
existing pack content through normal authoring and removal commands instead of
creating partial scaffold pieces. Existing platform override and code-review
area source files remain discoverable, renderable, installable, validatable, and
removable.

## Conditionally Required Keys

- `platform` — required for `platform-pack` and `add-on`.
  - For `add-on`, it must name an existing platform slug (e.g. `kotlin`, `kmp`).
  - For `platform-pack`, it is the new platform slug to create.
- `routing_signals` — required for `platform-pack` only when the platform
  does not have a built-in preset. Must be a mapping with a non-empty
  `strong` list and optional `tie_breakers` list. For
  known platforms such as `java`, the scaffolder can infer these
  defaults. The scaffolder completes bare/glob extension pairs and appends any
  missing positive-dominance, adjacent-pack disambiguation, or
  generated/vendored exclusion rule so the emitted manifest conforms to the
  review structure standard.
- `slug`, `description`, `agent_ids`, and `consumers` — required for
  `agent-addon`. Slugs use lowercase kebab-case, agents must come from the
  install-agent registry, and consumers must come from the agent-addon consumer
  registry. The current consumer is `skill-bill`.

## Optional Keys

- `description` — one-line description copied into the frontmatter.
- `content_body` — authored markdown body for governed skills. When
  provided for a content-managed skill, the scaffolder writes it into
  `content.md` below canonical frontmatter and the generated title instead
  of the default starter content. It must not include YAML frontmatter or
  generated wrapper headings such as `## Descriptor`, `## Execution`, or
  `## Ceremony`.
- `display_name` — human-friendly label for `platform-pack`. Defaults to a
  title-cased version of `platform`.
- `baseline_layers` — optional list for `platform-pack` only. When present,
  the scaffolder writes `code_review_composition.baseline_layers` into the
  new pack's `platform.yaml`. Each entry must include:
  - `platform` — existing referenced platform pack slug.
  - `skill` — existing code-review skill from the referenced pack.
  - `scope` — currently only `same-review-scope`.
  - `required` — explicit boolean.
  - `mode` — currently only `kmp-baseline`, supported only for
    `kotlin/bill-kotlin-code-review`.
  Supplying this field for any kind other than `platform-pack` raises
  `InvalidScaffoldPayloadError`. Invalid references fail before mutation:
  missing referenced pack, missing referenced skill, unsupported `mode`,
  unsupported `scope`, self-reference to the new pack, and duplicate
  `platform` + `skill` layers are rejected.
- `body` — advanced/scripted string for `add-on`. Normal `skill-bill new` and
  desktop creation omit this field so the scaffolder writes a skeleton markdown
  file with a TODO body placeholder. When provided by a scripted payload, the
  scaffolder treats the value as present, including blank strings, and writes
  that markdown body to the target add-on file with the repo-standard trailing
  newline instead of rendering the skeleton.
- `consumer_skill_dirs` — advanced/scripted list for `add-on`. Normal
  `skill-bill new` and desktop creation omit this field so the scaffolder uses
  the owning pack's baseline code-review skill when one exists, otherwise the
  pack's only manifest-declared skill directory. If the pack has no
  unambiguous default, add-on scaffold fails before mutation and scripted
  `consumer_skill_dirs` is required. Each scripted entry is a declared
  platform-pack-relative skill directory such as
  `code-review/bill-kmp-code-review-ui`. The scaffolder rejects directories
  that are unsafe, missing, or not declared by the pack manifest before any file
  or manifest mutation, then registers the new add-on in `platform.yaml` by
  adding generated pointer entries and `addon_usage` entries for these
  consumers.
- `addon_location_path` — optional path for `add-on`. When omitted, the
  scaffolder creates a pack-owned add-on under
  `platform-packs/<platform>/addons/<name>.md` and edits the pack's
  `platform.yaml`. When provided, the scaffolder creates `<name>.md` in that
  external add-on source directory and creates or updates
  `addon-manifest.yaml` there with bare pointer targets. `~` is expanded and
  relative paths resolve against `repo_root`. The platform pack must still
  exist so the scaffolder can validate/default `consumer_skill_dirs`.
- `repo_root` — optional absolute path override. When present, scaffolding uses
  this path. When omitted, scaffolding uses the invocation repository root (the
  CLI or MCP caller's resolved repository root, not a layout walk).
- `subagent_specialists` — list of specialist subagent names to scaffold
  alongside an orchestrator skill. Each name must match
  `^[a-z][a-z0-9-]*$`, be non-empty, and unique within the list. Honored
  only for `horizontal`. A `platform-pack` always emits exactly one
  manifest-derived native-agent source per declared specialist, so supplying
  `subagent_specialists` for `platform-pack` raises
  `InvalidScaffoldPayloadError`. Supplying it for `add-on` also raises.
  Default: `[]`.
- `no_subagents` — boolean opt-out for `horizontal`. A `platform-pack` cannot
  opt out of its required manifest-derived specialist bundle, so supplying
  this field for `platform-pack` raises `InvalidScaffoldPayloadError`.
  Default: `false`.

When a horizontal skill declares `subagent_specialists`, or when a platform
pack derives specialists from the full generated code-review area set, the
scaffolder emits one provider-neutral source bundle at
`<orchestrator-skill-dir>/native-agents/agents.yaml` with one entry per specialist.
Provider-specific Claude markdown, Codex TOML, and Junie markdown are
self-contained install-cache outputs generated from those logical sources during
install. Authors edit `native-agents/agents.yaml`, then run
`skill-bill render` to validate source renderability. The scaffolder also
injects a `## Subagent Spawn Runtime Notes` section into the
orchestrator's `content.md` that documents how Claude, Codex,
and Junie resolve specialist spawns. Explicit `subagent_specialists` source
stubs ship with a `TODO:` description and body plus a free-text pointer to
`specialist-contract.md` for the F-XXX Risk Register format used by review
specialists; they do NOT include literal `F-XXX` markers. Platform-pack
default specialist bundles compose from governed content and use the generated
specialist's per-area review description. Their governed content deliberately
contains the substance-promotion TODO prompts described below. Authors replace
those prompts before promotion, and repo validation fails if generated provider
artifacts are checked into the repo.

## Worked Examples

### Horizontal skill

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "horizontal",
  "name": "bill-new-horizontal",
  "description": "Use for ..."
}
```

### Retired partial kind rejection

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-override-piloted",
  "platform": "kotlin",
  "family": "code-review"
}
```

This raises `RetiredScaffoldKindError`. Use a full `platform-pack` scaffold for
new platform behavior, or edit/remove existing pack content directly through the
authoring/removal commands.

### New platform pack

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "java",
  "display_name": "Java",
  "description": "Use when reviewing Java server and library changes."
}
```

This creates `platform-packs/java/platform.yaml`,
`platform-packs/java/code-review/bill-java-code-review/content.md`, plus
bare specialist content stubs for every approved code-review area. The built-in
`java` preset supplies the routing signals. Generated wrappers and platform
pointer files are not staged into source. `skeleton_mode` and
`specialist_areas` are retired for new platform-pack creation; remove unwanted
focus areas afterward through governed removal paths.

### Platform pack with baseline review composition

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "kmp",
  "display_name": "KMP",
  "routing_signals": {
    "strong": ["kotlin(\"multiplatform\")", "androidMain", "iosMain"]
  },
  "baseline_layers": [
    {
      "platform": "kotlin",
      "skill": "bill-kotlin-code-review",
      "scope": "same-review-scope",
      "required": true,
      "mode": "kmp-baseline"
    }
  ]
}
```

Dry-run and execute use this same payload shape. In dry-run mode, CLI output
includes the planned `platform.yaml` edit so users and agents can inspect the
`code_review_composition.baseline_layers` block before mutation. Payloads that
omit `baseline_layers` remain valid and generate no
`code_review_composition` section.

### Governed skill with concrete authored content

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "horizontal",
  "name": "bill-new-horizontal",
  "content_body": "## Focus\n\nReview API boundary regressions.\n\n## Review Guidance\n\n- Prefer client-visible contract issues.\n- Call out backward-compatibility breaks explicitly.\n"
}
```

### Canonical platform-pack specialist content

New `platform-pack` scaffolds create every specialist with these H2 sections,
in exactly this order: `Focus`, `Ignore`, `Applicability`, and
`Project-Specific Rules`. The rules section begins with an H3 grouping and
includes this severity closer:

```text
For Blocker or Major findings, describe the concrete <area-appropriate consequence> scenario.
```

The consequence is canonical for the selected area. For example, `security`
uses `authorization-bypass or data-exposure`, while `persistence` uses
`data-loss, consistency, or durability failure`.

The specialist contract limits severity vocabulary to `Blocker`, `Major`, and
`Minor`. Scaffolded specialists inherit the calibrated consequence-based severity
definition from the shared contract and must not define their own severity
vocabulary. Existing packs can be migrated under the review-skill structure
standard; this payload contract describes the newly scaffolded shape.

### Add-on

Normal add-on authoring is a two-step source workflow: create the skeleton,
edit `platform-packs/<platform>/addons/<name>.md`, then run the normal
validate/render/install checks for the repo. `addon_usage` registration stays in
`platform.yaml`; do not add per-skill add-on selection tables to `content.md`.

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "add-on",
  "name": "android-paging",
  "platform": "kmp"
}
```

To create an external add-on source directly, include `addon_location_path`:

```json
{
  "scaffold_payload_version": "1.0",
  "kind": "add-on",
  "name": "acme-review",
  "platform": "ios",
  "addon_location_path": "~/dev/acme-review-addon"
}
```

This writes `~/dev/acme-review-addon/acme-review.md` and creates or updates
`~/dev/acme-review-addon/addon-manifest.yaml`. Desktop callers register that
directory in `external_addon_sources` after successful execution so the app tree
and install overlay can discover it. Scripted callers invoking this raw payload
outside the desktop app must register the source themselves.

## Platform-pack substance promotion

Platform-pack scaffolds deliberately emit TODO-bearing specialist prompts for
ten mechanism-plus-consequence rules across state/lifecycle/ordering,
contract/data/security, and resource/toolchain/operational failure clusters.
Quality-check commands belong in the optional `validation_gate`; the scaffolder
does not emit a quality-check skill. These prompts make the required authoring
work explicit, but TODO is forbidden by the maintained-repository substance
audit. A newly generated pack must be filled with concrete platform evidence
before it can be promoted into the maintained set.
Promotion uses the normal exemption-free repository gate: every effective
specialist needs at least three platform-specific failure-mode clusters and ten
evidence-bearing rules with zero forbidden placeholders; pack-wide shared
normalized five-word sequences must be at most 35%, and corresponding authored
rubrics at most 65% similar. A pack that can win quality-check routing must
declare its own `validation_gate`. Discovery and enforcement remain
manifest-driven rather than
enumerating the currently maintained platform slugs.

## Loud-Fail Exception Catalog

All exceptions derive from `skillbill.contracts.ShellContentContractException` and the scaffold error types declared in `runtime-contracts`:

- `ScaffoldPayloadVersionMismatchError` — `scaffold_payload_version`
  disagrees with the scaffolder.
- `InvalidScaffoldPayloadError` — missing required key, malformed value, or
  unapproved area slug.
- `RetiredScaffoldKindError` — `kind` is a retired partial creation kind
  (`platform-override-piloted`, `platform-override`, `override`,
  `code-review-area`, `area`, or `specialist`).
- `UnknownSkillKindError` — `kind` is not one of the active supported kinds.
- `UnknownPreShellFamilyError` — pre-shell family not in
  `PRE_SHELL_FAMILIES`; the retired family spelling `feature-` + `implement`
  fails with this typed error and names `feature-task` as the replacement.
- `MissingPlatformPackError` — platform pack (`platform-packs/<slug>/`)
  does not exist; create a conforming `platform.yaml` before retrying.
- `MissingSupportingFileTargetError` — a file name declared in
  `RUNTIME_SUPPORTING_FILES` for this skill is not registered in
  `SUPPORTING_FILE_TARGETS`; register the target or drop the reference.
  Render and install flows never silently skip generated support pointers.
- `SkillAlreadyExistsError` — target path already occupied.
- `ScaffoldValidatorError` — post-scaffold validator run failed; all
  staged changes are rolled back.
- `ScaffoldRollbackError` — rollback itself failed (the only failure mode
  that may leave the repo partially mutated).

## SKILL-41: Source content.md authoring (v1.1)

SKILL-41 makes `content.md` the only source-authored surface for governed
skills on shell contract `1.1`. The output contract is:

- Every governed skill scaffold writes source `content.md` with canonical
  frontmatter and clean authored body sections.
- `skill-bill render <skill>` produces deterministic `SKILL.md` wrapper
  stdout with generated `## Descriptor`, `## Execution`, and `## Ceremony`
  sections. It does not write wrappers or pointer files into source.
- End-user creation flows may also provide `content_body` so governed
  skills are concrete at scaffold time without requiring generated wrapper
  headings.

Loud-fail exceptions added to the shell contract catalog (raised by the
loader and surfaced by the validator, not by the scaffolder itself):

- `ContractVersionMismatchError` — pack declares an outdated
  `contract_version`; the message points at
  the historical migration notes in `docs/migrations/`.
- `MissingContentFileError` — sibling `content.md` is missing for a
  governed skill.
- `InvalidExecutionSectionError` — the `## Execution` section body is not
  the canonical byte-identical form.
- `InvalidCeremonySectionError` — the `## Ceremony` section body drifted
  from the wrapper template.
