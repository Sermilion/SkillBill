# Getting Started

Skill Bill installs governed agent workflows plus a local runtime. The fastest way to understand the product is to run `/skill-bill` on a small feature spec: it prepares the spec, then routes to implementation, which plans the work, implements it, reviews it, validates it, records history when relevant, and prepares the PR handoff.

The runtime is packaged Kotlin: normal `skill-bill` and `skill-bill-mcp` use distribution scripts built by `./install.sh`, not Gradle `run` tasks and not a legacy runtime selector.

Use this guide when you want to install Skill Bill, understand the runtime model, and know which behavior is enforced by contracts versus model reasoning.

## What Ships

Skill Bill has three operator surfaces:

- the one installed slash command, `/skill-bill`, which runs the full feature workflow, one phase (`phase:review`, `phase:validation`, ...), or one runtime operation (`operation:verify`, `operation:release`, ...)
- the local `skill-bill` CLI
- the local `skill-bill-mcp` stdio MCP server

The bundled skills are reference workflows and reusable workflow components. They are useful defaults, but the framework does not depend on them being preserved. Teams can fork, replace, or remove bundled skills and author their own governed workflows and platform packs.

Those surfaces use the same governed repo structure:

- `skills/` contains the canonical user-facing skill source, `skills/skill-bill/`. A source skill directory contains `content.md` and, only when needed, `native-agents/`.
- `platform-packs/` contains manifest-declared platform packs
- `orchestration/` contains shared contracts and playbooks
- `runtime-kotlin/` contains the packaged CLI and MCP runtime
- `intellij-plugin/` contains the isolated IntelliJ IDEA status plugin (separate Gradle build; see `intellij-plugin/README.md` and `intellij-plugin/ARCHITECTURE.md`)
- `vscode-extension/` contains the isolated VS Code status extension (separate npm build; see `vscode-extension/README.md` and `vscode-extension/ARCHITECTURE.md`)
- `scripts/` and `tests/` contain repo validation and maintainer tooling

## Install

```bash
git clone https://github.com/oila-gmbh/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

The default `./install.sh` is the **prebuilt** path: it downloads and checksum-verifies the prebuilt release runtime images for your host, so it needs no JDK and no Gradle build. Supported prebuilt hosts are `macos-arm64`, `macos-x64`, `windows-x64`, and `linux-x64`; any other host auto-falls back to a from-source build.

### Build prerequisites

A from-source build needs a **JDK 21+**. The runtime targets Java 21 and
`build-logic` runs on the Gradle buildscript classpath, so the Gradle daemon
itself must be 21+ — a Java 17 daemon fails configuration with
`Dependency requires at least JVM runtime version 21`.

`./install.sh` resolves a qualifying JVM before invoking Gradle: it honours
`SKILL_BILL_JAVA_HOME` first, then `JAVA_HOME`, then the `java` on `PATH`, and
finally scans the usual install roots (`/usr/libexec/java_home`, Homebrew,
SDKMAN, asdf, Gradle-provisioned JDKs). If none qualifies it fails with
remediation instead of a Gradle stack trace. To pin one explicitly:

```bash
SKILL_BILL_JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./install.sh --from-source
```

Note that a full local checkout installs from source by default, so this applies
to `./install.sh` in a clone even without `--from-source`.

For a pinned install, use the `--release TAG` mechanism (or the `SKILL_BILL_RELEASE_TAG` environment variable) instead of cloning a specific branch:

```bash
./install.sh --release v0.x.y
# or, equivalently:
SKILL_BILL_RELEASE_TAG=v0.x.y ./install.sh
```

`--release` pins the prebuilt artifacts to a specific release tag. It is ignored under `--from-source`, where the runtime is built from your current checkout.

On the default prebuilt path the installer fetches and checksum-verifies the Kotlin CLI and MCP distribution images from the matching GitHub release — it does **not** build them locally. With `--from-source` the installer instead builds the Kotlin CLI and MCP distributions with Gradle (**JDK 21+ required**). Either way it then copies the packaged runtime into `~/.skill-bill/runtime/`, verifies the installed bin scripts, installs `skill-bill` and `skill-bill-mcp` launchers into `${SKILL_BILL_BIN_DIR:-~/.local/bin}`, renders selected skills into staging, then links those staged skills into detected agent directories. MCP registrations point at the installed runtime copy so Gradle cleanup or IDE rebuilds inside the checkout do not break agent startup. If that launcher directory is not on `PATH`, install finishes with an explicit warning.

`./install.sh` collects install choices and delegates the durable work to the
packaged runtime command `skill-bill install apply`. The shell script still owns
bootstrap prompts and runtime distribution installation, but the runtime owns
planning, staging, symlink/native-agent linking, telemetry configuration, MCP
registration, replacement cleanup, and structured failure reporting.

Installer prompts cover:

- agent selection: manual or detected `claude`, `codex`,
  `cursor`, and `junie`
- platform packs: all packs, selected packs, or base skills only; selected
  packs are discovered from `platform-packs/` manifests
- telemetry: `anonymous`, `full`, or `off`

MCP registration is always applied for supported agent config entries so guided
workflows, learnings, telemetry, and workflow resume are available after
install.

Base skills are always included. Optional platform packs add their declared
review skills after manifest validation; dominant packs provide quality-check
commands through their `validation_gate`. All installed
content-managed skills are rendered into `~/.skill-bill/installed-skills/` and
agent entries link to that staging cache, not directly to source directories.
When `skill-bill` is present, install planning also selects the unique
pack declaring `fallback_capabilities: [code-review]`. The shipped declaration
belongs to `generic`, but teams may move it to a custom conforming pack. No
declaration leaves horizontal base behavior; multiple declarations fail.
Provider-native review workers are rendered from that same selection and
recorded in durable managed inventory, so later preflight does not inspect the
reviewed repository or require the source checkout.
When a platform pack declares `code_review_composition.baseline_layers`, the
rendered baseline review skill includes review-composition instructions so the
referenced baseline layer runs before pack-local specialists.

On Windows, Skill Bill uses explicit symlink preflight outcomes. If symlink
support is not available, install output tells the operator to enable Developer
Mode, run an elevated shell, or rerun after fixing the target filesystem. The
runtime preserves user-owned files and reports a structured failure rather than
silently replacing non-Skill-Bill content.

Supported install targets:

| Agent                                      | Install path                              |
|--------------------------------------------|-------------------------------------------|
| Claude Code                                | `~/.claude/skills/`                       |
| Claude Code (native subagent markdown)     | `~/.claude/agents/`                       |
| OpenAI Codex (skills)                      | `~/.codex/skills/`, `~/.codex-<name>/skills/`, or `~/.agents/skills/` |
| OpenAI Codex (native subagent TOMLs)       | `~/.codex/agents/`                        |
| Cursor (skills)                            | `~/.cursor/skills/`                       |
| Cursor (native subagent markdown)          | `~/.cursor/agents/`                       |
| JetBrains Junie (skills)                   | `~/.junie/skills/`                        |
| JetBrains Junie (native subagent markdown) | `~/.junie/agents/`                        |

Using a model family through Claude Code? Skill Bill installs to the Claude Code skills directory; no separate target is needed for the model family itself.

Installed skills are symlinks to rendered staging directories under `~/.skill-bill/installed-skills/`. Re-run `./install.sh` after changing the checkout so installed agents pick up refreshed `SKILL.md` wrappers, support pointer files, and content hashes.

### Config That Survives Installs

Your `config.json` (telemetry choices, `install_id`, and shared `external_addon_sources` for platform-pack and agent add-ons) lives at the durable path **`~/.config/skill-bill/config.json`**. That location is **outside** the `~/.skill-bill/` tree that installs wipe, so it survives every `./install.sh` and update automatically — no environment variable required.

Feature-task model and effort preferences also belong in this machine-wide JSON file under `execution_matrix`; they apply to every repository you run on this machine.

- **Fresh installs** write there by default.
- **Existing installs** are migrated automatically: the installer moves a legacy `~/.skill-bill/config.json` to the durable path before the pre-install cleanup, so nothing is lost on upgrade.
- **Custom location**: set `SKILL_BILL_CONFIG_PATH` to pin the config anywhere (this override always wins, for both the installer and the runtime). If you exported this in an earlier version just to relocate the config to `~/.config/skill-bill/`, you can drop it — the durable default now covers that automatically.

The pre-install cleanup still wipes the rest of `~/.skill-bill/`, preserving `skills/`, `platform-packs/`, `orchestration/`, `baseline-manifest.json`, and durable `*.db` state (goal/workflow stores, `review-metrics.db`). Only the config was ever at risk, and it now lives outside that tree. See [External Addon Sources](external-addons.md#persisting-config-across-installs) for details. For a one-off install that must not wipe any state, `SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 ./install.sh …` skips the cleanup entirely (intended for dev iteration).

On Claude, Codex, and Junie, orchestrators that delegate review work also install native subagent definitions for supported runtime surfaces. The stack-specific code-review skills (e.g. `bill-go-code-review-security`, `bill-kotlin-code-review`, `bill-php-code-review-security`, `bill-python-code-review-security`) are **internal sidecars** of `/skill-bill` — they are not listed slash commands; `/skill-bill phase:review` detects the dominant stack and routes to the matching sidecar. Invoke quality checks through `/skill-bill phase:validation`; it selects the dominant pack and runs that pack's `validation_gate` (`routed_skill` telemetry stays `bill-code-check`). Each declared platform-pack bundle registers its baseline reviewer and specialist reviewers as native subagents. Native subagent sources live as provider-neutral `native-agents/agents.yaml` bundles or standalone `native-agents/<name>.md` files. New and rendered neutral sources include `contract_version: "0.1"`; the parser still accepts older unpinned sources so existing repos can migrate gradually. Install renders those sources into `~/.skill-bill/native-agents/` before linking Claude markdown into `~/.claude/agents/`, Codex TOMLs into `~/.codex/agents/`, and Junie markdown into `~/.junie/agents/`; generated provider files are not checked into the repo. `~/.agents/agents/` is only a Skill Bill compatibility path for Codex homes without a `.codex` root, not the primary documented Codex custom-agent location. Claude and Junie use Markdown/YAML custom-subagent frontmatter; Codex resolves spawn instructions by TOML `name`. Today this covers shipped platform-pack baseline and specialist reviewers selected by manifest. Feature-task execution uses the Kotlin runtime driver (`skill-bill feature-task` / `skill-bill goal`); `operation:verify` has no verify-specific native subagents and runs its review through the review phase while keeping feature-flag, completeness, and verdict audits inline.

## Runtime Model

Normal use is Kotlin-only:

- `skill-bill` launches the packaged Kotlin CLI distribution.
- `skill-bill-mcp` launches the packaged Kotlin stdio MCP distribution.
- The installer registers MCP shims to the packaged Kotlin server.
- Gradle is not used by the default prebuilt install or by installed commands during normal use; it is needed only by maintainers building/validating the runtime and by the opt-in `./install.sh --from-source` install path.
- Repo validation commands are Kotlin-backed. The legacy maintainer stack is no longer required for current install, validation, or maintainer workflows.

If a packaged Kotlin distribution is missing, launcher behavior fails closed with install/build guidance. It does not silently run Gradle and does not fall back to a legacy runtime.

## First Checks

After install:

```bash
skill-bill version
skill-bill doctor
skill-bill telemetry status --format json
```

Then try the `/skill-bill` forms in your agent, in this order:

- `/skill-bill <KEY> phase:plan` (optional prep-first path for spec-only sessions)
- `/skill-bill <KEY> <description>`
- `/skill-bill phase:review`
- `/skill-bill phase:validation`
- `/skill-bill operation:verify <linear-issue|requirements|spec:<path>> [target:<pr>]`

Use the full run first because it exercises the full governed path: feature spec, planning, implementation, routed review, validation, history, and PR handoff. Use `phase:plan` when you need standalone spec/decomposition preparation before implementation. Use `phase:review` directly when you only need the review phase. `/skill-bill` is the only listed skill; each form runs `skill-bill phase <name>` or `skill-bill operation <name>`.

Choose standalone review execution explicitly with:

```text
/skill-bill phase:review mode:inline
/skill-bill phase:review mode:delegated
```

Omitting `mode:` is equivalent to `mode:inline`, the default reduced-depth review in one review session; `mode:delegated` is the experimental full-depth specialist subagent fan-out, reached only by that explicit selection on `phase:review`. `mode:auto` resolves to `inline` everywhere. Feature workflows review inline (`/skill-bill <issue-key> code-review:auto|inline`); delegated review is a standalone launch.

## Runtime Fallback Boundary

Skill Bill separates fail-closed contract behavior from best-effort operational behavior.

Fail closed:

- missing packaged CLI or MCP distributions
- malformed MCP arguments for strict schemas
- wrong shell contract versions
- missing platform manifests
- missing declared platform-pack content files
- missing generated wrapper sections during render/install validation
- extra files in `skills/` source directories other than `content.md` and optional `native-agents/`
- invalid scaffold payloads
- validation drift in generated wrappers or agent configs

Degrade or report explicitly:

- telemetry can be off or queued locally when sync is unavailable
- remote telemetry reads report capability or network failures without blocking local work
- agent detection may find no local agent directories; install/link commands can still target explicit paths
- learnings may resolve to `none`
- model-mediated review output can be uncertain and should mark confidence accordingly

Rollback for a broken runtime is to install a previous release. Do not expect a legacy runtime selector to recover current CLI or MCP execution.

## Strict vs Model-Mediated Guarantees

Strict and loud-fail guarantees:

- MCP tools publish strict schemas for priority workflow, telemetry, review, learning, scaffold, and workflow-state tools. Unknown top-level arguments are rejected before handler dispatch when the schema is strict.
- Shell and platform-pack fixtures enforce the governed contract version, manifest shape, declared files, generated wrapper sections, and generated support pointers.
- Scaffold and validation commands operate on structured manifests and `content.md` source files; invalid wizard input, invalid payloads, source-shape violations, or render drift fail the command. `skill-bill new --dry-run` previews planned files and manifest edits, including platform-pack baseline composition blocks; `skill-bill new --payload <file> --dry-run` is the scripted equivalent. `skill-bill show <skill>` exposes manifest-declared review composition for inspection. Legacy platform-pack payloads without `baseline_layers` continue to produce manifests with no composition section.
- `install link-skill` creates real symlinks to staged rendered skill directories and is covered by Kotlin CLI tests.

Model-mediated guarantees:

- review reasoning, implementation planning, audit reasoning, and PR description prose are produced by the active model using the governed instructions
- finding severity and confidence are signals, not proof
- workflow handoffs and audits are structured, but the judgement inside them still needs human review for high-risk changes

Use strict guarantees for compatibility and safety boundaries. Use model-mediated output as an expert second opinion that should be checked against the code.

## Bundled Workflows vs Framework

Skill Bill has two layers:

- the governed workflow framework: authoring rules, render/install staging, shell contracts, manifests, validators, CLI/MCP runtime, workflow state, telemetry, and cross-agent installation
- bundled reference workflows: the `/skill-bill` dispatcher, the phases and operations it reaches, and the platform-pack review skills

The bundled workflows are production-usable defaults, not a lock-in boundary. A team can delete or replace them and still use the framework to build its own governed workflow system.

## Governance System vs Reference Packs

Skill Bill is the governance system: routing, manifests, shell contracts, validation, installer behavior, workflow state, telemetry, and authoring rules.

The shipped platform packs are reference packs. They are real, validated, ready to use, and useful examples, but they are not the product boundary. Teams can fork, replace, or add conforming packs as long as discovery stays manifest-driven and the shell contract version remains locked.

Reference packs currently shipped:

Seven packs—Go, iOS, Kotlin, PHP, Python, Rust, and TypeScript—declare all ten approved review areas directly. KMP reaches the same effective coverage for Android and Kotlin Multiplatform by declaring `architecture`, `platform-correctness`, `security`, `persistence`, `reliability`, `ui`, and `ux-accessibility` and composing the remaining three areas (`performance`, `testing`, `api-contracts`) from Kotlin. Quality checks use the dominant pack's `validation_gate`; review composition does not supply quality commands.

- `kotlin`: Kotlin baseline review and validation-gate quality checks
- `kmp`: Android and Kotlin Multiplatform review declaring `architecture`, `platform-correctness`, `security`, `persistence`, `reliability`, `ui`, and `ux-accessibility` on the Kotlin baseline, governed Android add-ons, and its own validation gate
- `ios`: native iOS review and Xcode/SPM-aware validation gate
- `go`: Go services, libraries, CLIs, modules, APIs, persistence, concurrency, security, testing, Go-rendered UI, UX/accessibility, and validation gate
- `php`: PHP applications, services, Composer projects, APIs, persistence, security, testing, server-rendered UI, UX/accessibility, and validation gate
- `python`: Python applications, libraries, CLIs, APIs, persistence, security, testing, UI, UX/accessibility, and validation gate
- `rust`: Rust crates and Cargo workspaces, services, CLIs, async runtimes, FFI, persistence, safety, testing, UI/UX, and validation gate
- `typescript`: TypeScript applications, libraries, services, Node/browser runtimes, APIs, persistence, async behavior, TSX UI/UX, and validation gate

`skill-bill validate` discovers maintained packs from their manifests and applies the normal substance gate without exemptions: at least three platform-specific failure-mode clusters and ten evidence-bearing rules per effective specialist, no forbidden placeholders, at most 35% shared normalized five-word sequences per pack, and at most 65% similarity for corresponding authored rubrics.

## Common CLI Surfaces

Review and telemetry:

| Command                       | Purpose                                             |
|-------------------------------|-----------------------------------------------------|
| `skill-bill import-review`    | Import review output into the local SQLite store    |
| `skill-bill record-feedback`  | Record feedback for imported findings               |
| `skill-bill triage`           | Record triage decisions                             |
| `skill-bill stats`            | Show review acceptance metrics                      |
| `skill-bill feature-task-stats` | Show local feature-task runtime metrics           |
| `skill-bill goal-stats`       | Show local decomposed-goal run metrics              |
| `skill-bill verify-stats`     | Show local `operation:verify` metrics               |
| `skill-bill telemetry status` | Show telemetry configuration and pending sync state |
| `skill-bill telemetry sync`   | Reconcile stale sessions, then flush queued telemetry |
| `skill-bill telemetry capabilities` | Show configured proxy capabilities            |
| `skill-bill telemetry stats`  | Fetch aggregate remote workflow stats from the proxy |

Workflow state:

| Command                               | Purpose                                    |
|---------------------------------------|--------------------------------------------|
| `skill-bill feature-task status`      | Inspect a durable feature-task runtime run |
| `skill-bill feature-task lookup`      | Resolve continuation for an issue key      |
| `skill-bill goal status`              | Inspect a decomposed goal run              |
| `skill-bill goal watch`               | Follow a decomposed goal until terminal    |
| `skill-bill verify-workflow list`     | List persisted verify workflows            |
| `skill-bill verify-workflow latest`   | Show the latest verify workflow            |
| `skill-bill verify-workflow show`     | Show one verify workflow, including full durable state |
| `skill-bill verify-workflow resume`   | Build a verify resume/recovery explanation |
| `skill-bill verify-workflow continue` | Reopen a resumable verify workflow and emit compact continuation guidance |

Authoring and install:

| Command                                 | Purpose                                                                                           |
|-----------------------------------------|---------------------------------------------------------------------------------------------------|
| `skill-bill list`                       | List content-managed skills                                                                       |
| `skill-bill show <skill>`               | Inspect one governed skill                                                                        |
| `skill-bill explain [skill]`            | Explain the governed authoring boundary                                                           |
| `skill-bill validate`                   | Run repo or targeted governed-skill validation                                                    |
| `skill-bill render`                     | Render generated wrappers to stdout or install output without writing generated files into source |
| `skill-bill fill <skill>`               | Write authored `content.md` text and validate                                                     |
| `skill-bill upgrade`                    | Validate governed render output and regenerate native-agent artifacts through runtime guardrails   |
| `skill-bill new`                        | Scaffold a governed skill or platform pack through deterministic prompts                           |
| `skill-bill create-and-fill`            | Scaffold and immediately author one content-managed skill                                         |
| `skill-bill new-addon`                  | Create a pack-owned or external add-on skeleton, then edit the generated markdown file             |
| `skill-bill remove`                     | Remove a horizontal skill, platform pack, or governed add-on with cleanup                         |
| `skill-bill doctor`                     | Show local install and telemetry health                                                           |
| `skill-bill install agent-path <agent>` | Print an agent install path                                                                       |
| `skill-bill install detect-agents`      | List detected agents                                                                              |
| `skill-bill install link-skill`         | Render one skill into staging and symlink it into a target path                                   |
| `skill-bill validate-agent-configs`     | Run the agent-config/catalog/workflow-contract validator                                          |

## External Author Dry Run

The supported external-author flow is:

1. Scaffold a platform pack from a payload.
2. Validate the generated repo state.
3. Link one generated skill into an explicit agent path.
4. Remove the generated link and temporary pack artifacts.

Example payload:

```bash
cat > /tmp/skill-bill-pack.json <<'JSON'
{
  "scaffold_payload_version": "1.0",
  "kind": "platform-pack",
  "platform": "java",
  "display_name": "Java",
  "description": "Use when reviewing Java server and library changes."
}
JSON

skill-bill new --payload /tmp/skill-bill-pack.json
skill-bill validate
skill-bill install link-skill \
  --source platform-packs/java/code-review/bill-java-code-review \
  --target-dir /tmp/skill-bill-agent/skills \
  --agent codex
rm /tmp/skill-bill-agent/skills/bill-java-code-review
rm -rf platform-packs/java
```

Platform-pack scaffolds create the baseline code-review skill and every
approved code-review specialist. Declare `validation_gate` on the pack when it
can win dominant-stack quality-check routing. Remove unwanted
focus areas afterward through governed removal paths. In normal team usage,
remove scaffolded example files with your usual VCS workflow instead of
deleting committed pack files by hand. The explicit `link-skill` target
receives a symlink to a rendered staging directory, not to the source skill
directory.

## Goal Observability

`/skill-bill` hands confirmed goals to the foreground
`skill-bill goal` runtime. The runtime owns a flat worker model: one runnable
subtask, one durable child workflow, one fresh child process. Native or nested
subagents may help the child session stay focused, but workflow-store state is
the reliability contract.

This keeps the SKILL-56 and SKILL-58 goal-runner behavior intact: status/watch
are read-only, raw child output stays hidden unless debug output is requested,
stale running children are reconciled against authoritative terminal outcomes,
and the goal advances only after a durable child outcome is written.

Run the goal normally to see compact progress while raw child output stays
hidden:

```bash
skill-bill goal SKILL-901
```

```text
goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress
goal_observability: issue_key=SKILL-901 subtask_id=1 workflow_phase=implement worker_role=foreground liveness_class=durable_progress sequence_number=1
```

Use read-only status or watch commands when checking an in-flight run. Goal status is CLI-only; no skill wraps it:

```bash
skill-bill goal status SKILL-901
skill-bill goal watch SKILL-901 --interval-seconds 5
```

The watch command follows until the goal finishes and prints every refresh,
including unchanged status, at the selected interval. Use
`--suppress-unchanged` when repeated heartbeat output is undesirable.

```text
latest_observability: phase=implement role=phase_subagent liveness=durable_progress sequence=8
watch_observability: index=2 phase=implement role=phase_subagent liveness=durable_progress sequence=8
```

Add current git activity only when needed:

```bash
skill-bill goal status SKILL-901 --diff-stat
skill-bill goal status SKILL-901 \
  --diff-hunk runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt \
  --diff-hunk-max-hunks 2 \
  --diff-hunk-max-lines 20 \
  --diff-hunk-max-bytes 4000
```

```text
diff_stat: files_changed=3 insertions=12 deletions=4
selected_diff_hunks: count=1 truncated=false
selected_diff_line: hunk_index=1 line_index=1 path=runtime-kotlin/runtime-cli/src/main/kotlin/skillbill/cli/GoalCliCommands.kt staged=false text=+new
```

### Resuming, inspecting state, and accounting

Feature-task continuation is database-first. Before preparing a feature spec,
`/skill-bill` performs a read-only lookup using the normalized issue key and
the canonical repository identity (`repo-root-realpath-v1:` plus the resolved
Git top-level path). A unique resumable result keeps its persisted workflow ID,
mode, governed spec path, and completed phase artifacts. Ambiguous, running,
terminal-only, or corrupt results stop loudly; they never fall through to a new
workflow. An explicit workflow ID may select among otherwise ambiguous eligible
records, but it must match the same issue and repository.

The governed `spec.md` is the feature contract, not a workflow checkpoint.
Pre-planning, planning, and later phase outputs remain in durable workflow
state. Initial implementation continuation receives the completed `plan`.
When audit finds gaps, implementation remediation receives the immutable
original completed `preplan` and `plan` outputs plus the latest unmet criteria;
the runtime does not relaunch or overwrite either planning phase.

`feature-task` / `goal` activation and `verify-workflow continue` /
`verify-workflow show` are different surfaces:

- `skill-bill feature-task` and `skill-bill goal` own feature-task and goal
  activation, continuation lookup, and foreground execution.
- `verify-workflow continue` is **mutating activation** for verify runs — it
  re-opens resumable state and returns the **compact** continuation payload a
  session uses to resume verify work.
- `verify-workflow show` is **read-only inspection** — it changes nothing and
  returns the full snapshot (every step plus the complete durable `artifacts`
  map).

Goal child sessions resume from the **compact continuation output** and treat its
`current_step_artifacts` as authoritative, instead of rebuilding context from
chat history. **Fetch full state only when explicitly needed** (for example, to
read an omitted or large artifact) via the read-only `goal status` /
`feature-task status` / `verify-workflow show` path.

To answer *why* a subtask retried, stopped, or blocked, inspect the append-only
attempt ledger (`goal_attempt_ledger`) on the child workflow record with
`goal status` / `feature-task status` — its `action`, `blocked_reason`,
`stop_reason`, and `final_reconciled_result` fields explain each case without
scraping any provider session log.

> **Caveat:** provider-reported *total* token counts can be dominated by cached
> input replay, so they are a diagnostic signal, not a Skill Bill contract. Skill
> Bill optimizes payload size and session behavior (compact continuation,
> transition-only monitoring, read-only-on-demand full state) rather than relying
> on provider cache accounting.

See `orchestration/workflow-contract/PLAYBOOK.md` for the full attempt-ledger
field reference and the cached-token caveat in detail.

## MCP Server

`skill-bill-mcp` exposes the same local runtime primitives as structured MCP tools. It is useful when an agent can call local tools directly and should not parse CLI text.

Primary MCP groups:

- review and learning tools: `import_review`, `triage_findings`, `resolve_learnings`, `review_stats`
- telemetry tools: `telemetry_proxy_capabilities`, `telemetry_remote_stats`
- workflow stats tools: `feature_verify_stats`, `goal_stats`
- verify workflow tools: `feature_verify_started`, `feature_verify_workflow_open`, `feature_verify_workflow_update`, `feature_verify_workflow_list`, `feature_verify_workflow_latest`, `feature_verify_workflow_get`, `feature_verify_workflow_resume`, `feature_verify_workflow_continue`, `feature_verify_finished`
- quality and PR tools: `quality_check_started`, `quality_check_finished`, `pr_description_generated`
- scaffold tool: `new_skill_scaffold`
- health tool: `doctor`

The foreground feature-task driver owns its durable state, lifecycle telemetry,
and continuation lookup in-process. Settlement-only MCP tools
(`feature_task_phase_complete`, `feature_task_phase_block`,
`feature_task_audit_settle`) let a child agent choose the next edge without
schema-policing a stdout envelope. The preplan, plan, and implement briefings
pin the workflow id and attempt and instruct the child to finish through those
tools with one prose `value` and an optional `prompt`. Use `skill-bill
feature-task` and `skill-bill feature-task-stats` for lifecycle control. When a
phase ends through stdout with no settlement record, the runtime takes the
non-blank stdout prose as the value; it does not parse a JSON envelope or
relaunch for format. Later phases read the selected spec as a persisted
handoff rather than a value restated in the briefing.

## Validation Gate

Maintainers should run the full gate before shipping runtime, scaffold, contract, docs, or agent-config changes:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

`./gradlew check` is a maintainer validation command inside `runtime-kotlin/`; it is not part of normal installed command execution.

When changing the IntelliJ plugin, also run its isolated gate (JDK 21):

```bash
(cd intellij-plugin && ./gradlew check verifyPlugin)
```

Do not fold IntelliJ Platform tasks into `runtime-kotlin`. Plugin local setup, CLI path resolution (preference override then `PATH` lookup of `skill-bill`), compatibility range (IDEA 2025.2 and newer), and architecture notes live in `intellij-plugin/README.md` and `intellij-plugin/ARCHITECTURE.md`.

## Reference Docs

- [Getting Started for Teams](getting-started-for-teams.md)
- [Skill Bill Teams Roadmap](team-control-plane-roadmap.md)
- [External Addon Sources](external-addons.md)
- [Skill Source And Generation Model](skill-source-generation.md)
- [Review Telemetry](review-telemetry.md)
- `orchestration/shell-content-contract/PLAYBOOK.md`
- `orchestration/workflow-contract/PLAYBOOK.md`
# Agent add-ons

Agent add-ons are explicit, agent-compatible extensions. For Codex, invoke the
execution boundary guidance with
`/skill-bill <ISSUE_KEY> agent-addon:execution-budget`; omit the token for the
unchanged baseline workflow. See [Agent add-on authored sources](skill-source-generation.md#agent-add-on-authored-sources)
for precedence, source shape, staging, and resume guarantees.
