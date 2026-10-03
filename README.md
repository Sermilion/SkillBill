![Skill Bill, governed workflows for AI coding agents](docs/assets/skill-bill-readme-hero.svg)

# Skill Bill



[![License: MIT](https://img.shields.io/badge/License-MIT-4c1.svg)](LICENSE)
![Latest release](https://img.shields.io/github/v/release/Sermilion/SkillBill?include_prereleases&sort=semver)
![Validate agent configs](https://img.shields.io/github/actions/workflow/status/Sermilion/SkillBill/validate-agent-configs.yml?branch=main&label=validate)

Skill Bill takes feature work from an issue and acceptance criteria through planning, implementation, simplification, review, and a PR. A local runtime saves progress between phases, so interrupted work can continue from durable state.

Use it with Claude Code, Codex, or Cursor. One listed skill, `/skill-bill`, runs the full feature workflow, a single phase such as review or validation, or a runtime operation. You review the resulting changes before merging. The project is pre-1.0.

[Quickstart](#quickstart) · [Workflow](#feature-workflow) · [Skills](#skills) · [Platform packs](#platform-packs) · [IDE integrations](#agents-and-ide-integrations) · [Execution matrix](#execution-matrix) · [Documentation](#learn-more)

## Quickstart

Install and authenticate your coding agent's CLI, then install Skill Bill:

```bash
curl -fsSL https://raw.githubusercontent.com/Sermilion/SkillBill/main/install.sh | bash
```

Choose your agents, platform packs, and telemetry level when prompted. The installer downloads a self-contained runtime, renders `/skill-bill` with the selected packs' review specialists as its internal sidecars, links it into agent directories, and registers the MCP server. Prebuilt installs need no system JDK or Gradle. Installing over an older version removes the retired `bill-*` skills; see [Upgrading from the `bill-*` skills](#upgrading-from-the-bill--skills).

Check the installation:

```bash
skill-bill version
skill-bill doctor
```

Open your coding agent in the target repository and start a feature:

```text
/skill-bill APP-123 Add CSV export for the filtered orders list
```

New work needs both a connected tracker link or issue key and the requirements after it. The first line of the requirements, or a link's URL slug, names the spec folder (for example `APP-123-add-csv-export`). If either part is missing, `/skill-bill` asks for it before it launches: the tracker key for raw requirements, or the requirements for a bare key when tracker lookup returns none. Include observable acceptance criteria and constraints in the requirements. To resume, pass the key or path of an existing spec. Skill Bill launches the full workflow, prepares missing spec artifacts, and resumes existing work. Use `/skill-bill APP-123 phase:plan` to prepare a spec without starting implementation, or `/skill-bill phase:review target:uncommitted` to review existing changes. These examples use slash notation; use your agent's skill invocation syntax.

<details>
<summary>Install requirements, PATH setup, and source builds</summary>

Prebuilt targets are `macos-arm64`, `macos-x64`, `linux-x64`, and `windows-x64`. The installer needs Bash, `curl`, `tar`, `unzip`, and either `shasum` or `sha256sum` to download and checksum-verify the release bundle and runtime images. On Windows, use a Bash environment with symlink support. The installer reports how to enable it when unavailable.

Feature work also needs the target project's build tools and credentials for any Git push or PR creation.

To inspect the installer before running it:

```bash
curl -fsSL https://raw.githubusercontent.com/Sermilion/SkillBill/main/install.sh -o install.sh
less install.sh
bash install.sh
```

If `skill-bill` is not found, add its launcher directory to your `PATH`. For the default directory, add this to your Bash or Zsh startup file:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

A full local checkout builds from source by default. Contributors can make that choice explicit:

```bash
git clone https://github.com/Sermilion/SkillBill.git
cd SkillBill
./install.sh --from-source
```

Source builds require JDK 21 or newer. Set `SKILL_BILL_JAVA_HOME` if the installer cannot find a suitable JDK. Unsupported prebuilt hosts fall back to the source path and need its prerequisites. Use `bash install.sh --release <tag>` to select a published release; `--from-source` ignores release selection.

Runtime files and rendered skills live under `~/.skill-bill/`. User configuration lives at `~/.config/skill-bill/config.json` and survives reinstalling. `SKILL_BILL_CONFIG_PATH` overrides that location. See [Getting Started](docs/getting-started.md) for install paths and troubleshooting.

</details>

To update later:

```bash
skill-bill update-check
skill-bill update
```

## Feature workflow

`/skill-bill <intake>` launches the goal runtime. The runtime prepares a missing spec, plans the work, and runs it. If a spec or goal already exists, it resumes that instead. A small feature uses one subtask. Larger work can use dependency-ordered subtasks, each with a fresh execution context and durable handoff artifacts.

Every run follows the same fixed sequence of nine phase slots:

| Slot | What happens |
| --- | --- |
| `preplan` | Collect repository instructions and relevant durable artifacts. For a goal, this runs once and every subtask shares it. |
| `plan` | Plan the subtask's implementation. |
| `implementation` | Implement the plan, then run a mandatory simplification pass scoped to the subtask's changes. |
| `audit` | Check the acceptance criteria against the current code and tests, then plan and repair gaps. |
| `code_review` | Review the subtask commit, verify findings, and fix them in bounded rounds. |
| `quality_gate` | Run full project validation, or a compile-only build for goal subtasks before the last. |
| `write_history` | Record boundary history and decisions in area-owned `agent/` files. |
| `commit_push` | Commit and push the subtask: one commit per subtask on the feature branch. |
| `pull_request` | Create or update the PR once every subtask is complete. |

Each standalone phase runs a subset of these slots through the same run loop. For example, `phase:review` runs only `code_review`, and `phase:plan` runs `preplan` and `plan`. Runs differ in which slots they include and where they keep state, not in how a slot runs.

The quality phases have different purposes:

| Phase or command | Checks |
| --- | --- |
| `validate` | An agent discovers and runs the project's required checks from repository instructions, build configuration, scripts, and CI. The runtime advances only when the agent reports that those checks passed. |
| `build` | Every goal subtask except the last runs the dominant pack's declared build command, then a cache-bypassing confirmation. This proves buildability and does not run the full test suite. |
| Standalone quality check | `/skill-bill phase:validation` runs the same full project checks and repair loop as goal validation. |

An unknown quality-gate selection value fails as a usage error.

Specs live under `.feature-specs/`, with a parent spec, executable subtask specs, and a decomposition manifest. Local specs are the default; optional Linear-backed preparation records issues and supports spec rehydration. The default commit model leaves one commit per completed subtask on the feature branch.

The runtime saves phase outputs and continuation state in a local database. Resuming uses those records and the current repository. Invalid contracts or ambiguous continuation records stop the run with an explicit error.

<details>
<summary>Watch an illustrated feature run</summary>

![Scripted illustration of a feature run interrupted and resumed](docs/assets/skill-bill-demo.gif)

This [scripted playback](docs/assets/generate_demo_gif.py) illustrates interruption and continuation. It is not a recording of the current runtime; the stages above describe current behavior.

</details>

### Inspect, pause, and continue

Run these from the repository that owns the goal:

| Command | Effect |
| --- | --- |
| `skill-bill goal status APP-123` | Read-only goal state and subtask details |
| `skill-bill work status --format json` | Repository work snapshot used by the IDE integrations |
| `skill-bill goal pause APP-123` | Request a pause after the current subtask |
| `skill-bill goal stop APP-123` | Record an operator stop and terminate the running goal |
| `skill-bill goal resume APP-123` | Clear a durable pause without launching work |

To continue a paused goal from the CLI, clear its pause and launch it with an explicit agent:

```bash
skill-bill goal resume APP-123
skill-bill goal APP-123 --agent claude
```

Use the agent ID for your installed CLI, such as `claude`, `codex`, or `cursor`. `/skill-bill APP-123` starts or resumes the full workflow. Recovery can use another compatible agent because workflow state belongs to Skill Bill.

## Review and quality checks

Name the work you want reviewed:

```text
/skill-bill phase:review target:pr
/skill-bill phase:review target:HEAD
/skill-bill phase:review target:uncommitted mode:inline
/skill-bill phase:review target:staged mode:delegated
```

Review also accepts `target:unstaged` or a commit sha. Without `target:`, it reviews uncommitted changes when the worktree is dirty and `HEAD` when it is clean.

Review fixes what it finds. Both modes verify their findings, fix Blocker and Major ones in the working tree, and report the rest with a verdict. A standalone review creates no commit.

`inline` is the default. It runs one review worker over the routed areas at reduced depth. `auto` also resolves to inline. `delegated` is the experimental full-depth mode, with separate specialist workers, and requires explicit `mode:delegated` on a standalone review. Feature and goal workflows accept `code-review:auto|inline` and use inline review. A required worker that cannot launch blocks the review rather than silently reducing its depth.

For full project validation:

```text
/skill-bill phase:validation
```

The phase uses the same agent strategy as goal validate. It picks the platform gate from the branch's tracked files and validates the whole branch, not only the changed files. It discovers required checks from repository instructions, build configuration, scripts, and CI, then runs those checks and repairs failures. Ambiguous platform ownership or a missing gate declaration blocks the phase. It does not fall back to another pack's gate.

## Skills

`/skill-bill` is the only listed skill. Phases and operations are forms of it, not separate commands. Stack-specific review skills install as its internal sidecars.

There are three kinds of form:

- The full run (`/skill-bill <intake>`) runs all nine slots with durable state, so it can pause and resume.
- A phase (`phase:<name>`) runs a few slots in memory. It writes no workflow row, branch, or checkpoint commit, and it cannot be resumed. `commit_push` is not available as a standalone phase.
- An operation (`operation:<name>`) is a standalone job outside the feature workflow. Operations that edit files, tag a release, or push stop at `awaiting_confirmation` and act only after you confirm.

| Form | Purpose | Runs |
|------|---------|------|
| `/skill-bill` | Prepare new feature work from an `<intake>`, or resume an existing spec or goal, then run it | `skill-bill goal` |
| `/skill-bill <intake> phase:plan` | Prepare a parent spec, executable subtask specs, and a manifest without implementing | `skill-bill phase plan` |
| `/skill-bill phase:review` | Review a PR, commit, or working-tree change with inline or delegated depth | `skill-bill phase review` |
| `/skill-bill phase:validation` | Run full project validation and repair findings, using the goal validation strategy | `skill-bill phase validation` |
| `/skill-bill phase:pr` | Commit pending changes, push the branch, and create or update a PR | `skill-bill phase pr` |
| `/skill-bill <intake> operation:feature-guard` | Guard an implementation with a feature flag | `skill-bill operation feature-guard` |
| `/skill-bill <intake> operation:feature-guard-cleanup` | Remove a rolled-out feature flag and its legacy path | `skill-bill operation feature-guard-cleanup` |
| `/skill-bill operation:verify <linear-issue\|requirements\|spec:<path>> [target:<pr\|branch\|base..head>]` | Verify a change against a Linear issue, requirements text, or a task spec | `skill-bill operation verify` |
| `/skill-bill [<pr>] operation:pr-review-fix` | Triage PR feedback, then apply selected fixes, reply, and push after approval | `skill-bill operation pr-review-fix` |
| `/skill-bill [<scope>] operation:unit-test-value-check` | Identify tests that cannot catch a realistic regression | `skill-bill operation unit-test-value-check` |
| `/skill-bill operation:release bump:<patch\|minor\|major>` | Prepare a changelog, confirm the requested semver bump, and push an annotated tag | `skill-bill operation release` |
| `/skill-bill operation:update-check` | Compare the installed runtime version with GitHub releases | `skill-bill operation update-check` |

Boundary history and decisions are written by the goal's `write_history` phase. Goal status is CLI-only: run `skill-bill goal status <KEY>`.

```text
/skill-bill APP-123 Add CSV export               # full workflow from intake
/skill-bill APP-123 phase:plan                   # skill-bill phase plan APP-123
/skill-bill phase:review mode:delegated target:HEAD
```

Standalone phases and operations are operator tools. Agents invoke them only
when explicitly requested and never select them as full-run steps or recovery
actions. New full-run work needs both a connected tracker link or issue key and
its requirements; `/skill-bill` asks for whichever is missing before launch. An
existing spec key or path resumes that spec. `skill-bill <intake>` routes to
the goal runtime, which prepares new work and resumes existing specs without
invoking a standalone phase command.

The full run forwards `code-review:inline|auto` as `--code-review-mode`; `phase:review` forwards `mode:` and `target:` unchanged. Release first prints the proposed version and changelog and exits `awaiting_confirmation`; confirming it with `confirm:<token>` creates and pushes the tag. `[<scope>] operation:unit-test-value-check` reviews unit tests without editing. `<intake> operation:feature-guard` and `<intake> operation:feature-guard-cleanup` print a plan and exit `awaiting_confirmation`. They edit only on `confirm:<token>`. `[<pr>] operation:pr-review-fix` prints a per-thread matrix for the PR's unresolved review threads and exits `awaiting_confirmation`; the dispatcher asks which threads to fix and re-runs it with `confirm:<token>` and `select:`. It pushes only with `push:on`.

### Upgrading from the `bill-*` skills

Earlier versions installed a separate listed skill for each job. Those skills are retired, and reinstalling removes their links and copies. Use the matching `/skill-bill` form instead:

| Retired skill | Use instead |
| --- | --- |
| `bill-feature` | `/skill-bill <intake>` |
| `bill-feature-spec` | `/skill-bill <intake> phase:plan` |
| `bill-code-review` | `/skill-bill phase:review` |
| `bill-code-check` | `/skill-bill phase:validation` |
| `bill-pr-description` | `/skill-bill phase:pr` |
| `bill-boundary-history`, `bill-boundary-decisions` | The goal's `write_history` slot |
| `bill-feature-verify` | `/skill-bill operation:verify` |
| `bill-feature-guard` | `/skill-bill <intake> operation:feature-guard` |
| `bill-feature-guard-cleanup` | `/skill-bill <intake> operation:feature-guard-cleanup` |
| `bill-pr-review-fix` | `/skill-bill operation:pr-review-fix` |
| `bill-unit-test-value-check` | `/skill-bill operation:unit-test-value-check` |
| `bill-release` | `/skill-bill operation:release bump:<patch\|minor\|major>` |
| `bill-update-check` | `/skill-bill operation:update-check` |
| `bill-monitor` | `skill-bill goal status <KEY>` from the CLI |

Telemetry and stored workflow rows still use some retired names, such as `bill-code-check` as the quality-check `routed_skill`, so existing dashboards and in-flight workflows keep working.

## Platform packs

Platform packs live under `platform-packs/<slug>/`. Their manifests declare routing signals, review areas, native workers, add-ons, and quality commands. Discovery and routing use those declarations, so teams can add or replace packs without editing a hard-coded platform list.

| Pack | Scope |
| --- | --- |
| `generic` | Review fallback for unsupported, documentation-only, and unresolved paths; no full quality gate |
| `go` | Go modules and workspaces, services, libraries, and CLIs |
| `ios` | Native iOS, Swift, SwiftUI/UIKit, and Xcode/SPM projects |
| `kotlin` | Kotlin/JVM and the baseline review layer for KMP |
| `kmp` | Android and Kotlin Multiplatform, composed with the Kotlin baseline |
| `php` | PHP applications, services, and Composer projects |
| `python` | Python applications, libraries, services, and CLIs |
| `rust` | Rust crates and Cargo workspaces |
| `typescript` | TypeScript and TSX applications, libraries, and services |

The ten review areas are `architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, and `ux-accessibility`. KMP declares seven of its own and takes `performance`, `testing`, and `api-contracts` from Kotlin. KMP has its own quality gate with no Kotlin fallback. The other shipped packs each declare all ten review areas.

Concrete path ownership takes precedence over the generic review fallback. Content signals break ties between equal positive path matches. The fallback owner is manifest-declared and replaceable; declaring more than one fails validation.

Pack review skills install as internal sidecars of `/skill-bill`. Run `/skill-bill phase:review`; the stack-specific skills are not separate user commands. Pack validation checks specialist substance as well as manifest shape. See the [source-generation guide](docs/skill-source-generation.md) and [review substance standard](orchestration/review-orchestrator/platform-pack-substance-standard.md) for authoring requirements.

## Agents and IDE integrations

The installer supports Claude Code, Codex, Cursor, and JetBrains Junie. It generates each provider's skill and native-agent files from shared sources and registers the local MCP server. Runtime review launch support depends on the provider's isolation capabilities. Claude, Codex, and Cursor have governed review launch adapters; Junie's adapter currently rejects governed review launches that require tool and MCP isolation.

Two separately packaged IDE integrations show repository work status, planning progress, the active phase, and elapsed time:

- [IntelliJ plugin](intellij-plugin/README.md), distributed as a plugin ZIP under `plugin-v*` releases.
- [VS Code extension](vscode-extension/README.md), distributed as a VSIX under `extension-v*` releases.

Both offer stop and pause-after-subtask controls. Launch and resume stay in the CLI or agent session. Install the plugins from their release artifacts; they are separate from the Skill Bill runtime installer. Their READMEs include compatibility requirements and source build instructions.

## Execution matrix

Use `execution_matrix` to choose the model and effort for feature-task phases, including goal children. Add it to your machine-wide `~/.config/skill-bill/config.json`, or the file selected by `SKILL_BILL_CONFIG_PATH`. Merge it into the existing JSON object so your telemetry and other settings remain intact. These preferences apply across repositories; `.skill-bill/config.yaml` does not own them.

The matrix selects a model for the agent already assigned to a phase. It does not switch agents. You can configure several agents in one file and keep only the entries you use.

Each example below is a complete JSON object for one agent. To configure several agents, combine their entries under the same `execution_matrix.agents` object. Model availability depends on your provider and account; use model IDs accepted by your installed CLI.

### Claude Code

The `claude` entry accepts model aliases or full model IDs. The runtime forwards `effort` through Claude's `--effort` option.

```json
{
  "execution_matrix": {
    "agents": {
      "claude": {
        "reasoning": { "model": "opus", "effort": "high" },
        "implementation": { "model": "sonnet", "effort": "medium" }
      }
    }
  }
}
```

When using a custom `ANTHROPIC_BASE_URL`, the adapter lets `ANTHROPIC_MODEL` override a Claude model name or alias when that environment variable is set.

### Codex

The `codex` entry uses a model ID and optional reasoning effort. The runtime passes these through `--model` and `--config model_reasoning_effort=...`.

```json
{
  "execution_matrix": {
    "agents": {
      "codex": {
        "reasoning": { "model": "gpt-6-astra", "effort": "high" },
        "implementation": { "model": "gpt-5.6-sol", "effort": "medium" }
      }
    }
  }
}
```

### Cursor

The `cursor` entry uses model IDs from `agent --list-models`. This example selects a reasoning model with effort encoded in its ID and Composer for implementation.

```json
{
  "execution_matrix": {
    "agents": {
      "cursor": {
        "reasoning": { "model": "claude-opus-5-thinking-high" },
        "implementation": { "model": "composer-2.5" }
      }
    }
  }
}
```

For Cursor, a separate `effort` field becomes a model parameter, such as `model[effort=high]`. If the model already contains an `[effort=...]` parameter, the values must agree. For parameterized models with other options, put the complete model string in `model` and omit `effort`.

### Junie

Junie's runtime adapter does not support model or effort overrides. Omit `junie` from `execution_matrix.agents`. Assigning a directive to a Junie phase fails before launch.

<details>
<summary>Default phase tiers, per-phase overrides, and precedence</summary>

### Phase tiers and overrides

The default tier assignments are:

| Tier | Phases |
| --- | --- |
| `reasoning` | `plan`, `review`, `verify_findings`, `audit`, `validate` |
| `implementation` | `preplan`, `implement`, `simplify`, `implement_fix`, `build`, `write_history`, `commit_push`, `pr` |

These are model defaults for agent launches. They do not make runtime-owned operations, such as goal-subtask commit and push, launch an agent.

Use `phase_tiers` to move a phase to another tier for all configured agents. To override one phase for one agent, put the phase ID beside that agent's tier entries. For example, this moves `preplan` to the reasoning tier and gives Codex review its own effort setting:

```json
{
  "execution_matrix": {
    "phase_tiers": {
      "preplan": "reasoning"
    },
    "agents": {
      "codex": {
        "reasoning": { "model": "gpt-6-astra", "effort": "high" },
        "implementation": { "model": "gpt-5.6-sol", "effort": "medium" },
        "review": { "model": "gpt-6-astra", "effort": "xhigh" }
      }
    }
  }
}
```

Resolution order is an explicit `feature-task --phase-model phase=model@effort` assignment, then the agent's phase entry, then its tier entry. With no matching directive, Skill Bill leaves model selection to the provider's launch defaults. The `--phase-model` option belongs to the lower-level `skill-bill feature-task run` and `resume` commands, not `skill-bill goal` or `/skill-bill`.

Every directive requires a non-blank `model`. Omit `effort` to leave it unspecified; an empty string or `null` is invalid. Unknown fields, agent IDs, phase IDs, and tier names fail with the offending config path. The runtime validates this structure; the provider validates model availability and supported effort values.

</details>

## Customize and extend

Put repository-wide instructions in `AGENTS.md` and skill-specific guidance in `.agents/skill-overrides.md`. The [override example](.agents/skill-overrides.example.md) shows the section format. Boundary history and decisions live beside the code in area-owned `agent/history.md` and `agent/decisions.md` files.

Platform-pack add-ons supply stack-specific guidance after routing. [External add-on sources](docs/external-addons.md) let teams keep private guidance outside the shared repository.

Agent add-ons are separate, explicitly selected extensions. The shipped `execution-budget` add-on applies to Codex feature work and reinforces the user's stopping boundary, compact handoffs, and delegation constraints:

```text
/skill-bill APP-123 agent-addon:execution-budget
```

Its source is `agent-addons/<slug>/agent-addon.yaml` plus `content.md`. See [agent add-on authoring](docs/skill-source-generation.md#agent-add-on-authored-sources) for compatibility, precedence, staging, and resume behavior.

To author skills or packs, start with `skill-bill new`, then use `show`, `fill`, `edit`, `validate`, and `render`. Authored skill content lives in `content.md`. The installer generates `SKILL.md`, support pointers, and provider-specific native-agent files into staging; those generated files do not belong in source. Re-run `./install.sh` after changing skill sources, rendering, or support pointer generation.

See [Contributing](CONTRIBUTING.md), [source generation](docs/skill-source-generation.md), and the [scaffold payload contract](orchestration/shell-content-contract/SCAFFOLD_PAYLOAD.md) before changing these contracts. The runtime, IntelliJ plugin, and VS Code extension have separate builds.

## Telemetry

The default telemetry level is `anonymous`. The installer lets you choose `anonymous`, `full`, or `off`. Events go to the configured telemetry proxy, using the shipped relay by default. You can [self-host the proxy](docs/cloudflare-telemetry-proxy/README.md).

To disable transmission:

```bash
skill-bill telemetry disable
```

Some diagnostic events still queue locally while telemetry is off. [Telemetry Privacy](docs/telemetry-privacy.md) documents the fields, destinations, correlation identifiers, retention, and what happens if you enable telemetry later. Workflow state remains local and supports continuation independently of telemetry transmission.

## Learn more

- [Getting Started](docs/getting-started.md): installation, CLI commands, MCP tools, and recovery.
- [Getting Started for Teams](docs/getting-started-for-teams.md): rollout and project customization.
- [Runtime Command Guidance](docs/runtime-command-guidance.md): full-run, phase, and operation behavior, plus goal commit rules.
- [Capability Deep-dive](docs/capabilities.md): workflows, packs, memory, and governance.
- [Token Economy](docs/token-economy.md): bounded context and durable handoffs for long goals.
- [Review Telemetry](docs/review-telemetry.md): review measurements, learnings, and local statistics.
- [Runtime Architecture](runtime-kotlin/ARCHITECTURE.md): module ownership, persistence, and contract enforcement.
- [Observability Policy](docs/observability-policy.md): required records for failures and degraded behavior.
- [Teams Roadmap](docs/team-control-plane-roadmap.md): proposed hosted team controls and open product questions.

## License

Skill Bill is licensed under the [MIT License](LICENSE). See the
[licensing summary](docs/licensing.md) for details.
