# SKILL-447 - Config pane in the Skill Bill desktop app

## Intended Outcome

The desktop main window gains a Config pane. It shares the right-hand slot with the Specs pane and shows Skill
Bill's configuration: the execution matrix, compaction, the selected project's settings, and the system settings.
The operator can view and edit these values there.

Every model name is checked against the agent's model list as the operator types. A typo such as `opus5-5` for
Claude is caught before any run uses it, and a model that is new since the last Skill Bill release still saves.
No check sends a request to a model.

The same check runs from the CLI and before a goal launches, so a hand-edited `config.json` cannot carry a typo into
a run.

## Design

The design boards are the visual and interaction source of truth:

- `design/App/ConfigPane.dc.html`: the main window with the pane open. It is interactive: the toggle, the Models
  section, and the inline editor with live model checks.
- `design/App/ConfigPaneModelField.dc.html`: every model-check state, numbered 01 to 12.
- `design/App/ConfigPaneCompaction.dc.html`, `design/App/ConfigPaneProject.dc.html`,
  `design/App/ConfigPaneSystem.dc.html`: the other three sections.
- `design/App/ConfigPaneEdgeStates.dc.html`: a malformed file, a file changed on disk, YAML comments, and the goal
  launch preflight.

The boards are also published at https://claude.ai/artifact/HS9RcTXRr3tBEw18K7JfDy. That link is not reachable from
headless agents; the files above hold the same boards.

Visual tokens are the existing desktop palette in `desktop-tray/core/designsystem`: canvas, sidebar, panel, raised,
border, ink, muted, dim, mint, amber, orange, red, green and blue, with the Inter, Space Grotesk and JetBrains Mono
type. The boards use these values.

Some board content is example data:
- the `review` phase override and its `review.md` instruction file;
- the `claude-opus-6-0` and `[retired model id]` values;
- the `SKILL-442` running work.

## Configuration in scope today

| Scope | File | Keys | Today |
| --- | --- | --- | --- |
| Machine | `~/.config/skill-bill/config.json` (override: `SKILL_BILL_CONFIG_PATH`) | `execution_matrix` (`phase_tiers`, `agents.<agent>.<tier or phase>.{model, effort}`), `compaction` (`enabled`, `window_tokens`, `trigger_pct`, `phases.<phase>`), `telemetry` (`level`, `proxy_url`, `batch_size`), `external_platform_pack_sources`, `external_addon_sources`, `install_id` | Only `telemetry.level` has a mutation (`TelemetryConfigMutations`). The matrix and compaction are read-only (`ConfigResolutionService`). |
| Project | the project store `config.yaml` (`ProjectConfigFilePort.ensureConfigFile`, read by `FileSystemRepoLocalConfig`) | `spec_type`, `validation_gate.gradle_wrapper`, `review_context_budget.*` (ten limits, defaults in `ReviewContextBudgetPolicy`) | Read-only. `skill-bill config edit` opens the file in an editor. |
| Repository | `.skill-bill/phases/<phase>` Markdown files | Per-phase prompt instructions, 16 KiB cap | No UI. |
| Install | `~/.skill-bill/install-selection.json` | `selected_agents`, platform pack selection, telemetry level, MCP registration | Written by `install.sh`. |
| Desktop | `~/.skill-bill/desktop-settings.json` | retention, notifications, start at login, Claude config dir, database path, `specPaneOpen` | Edited in the Settings window. |

Parsing and validation already exist and must be reused:
- `parseExecutionMatrix` and `DEFAULT_PHASE_TIERS` in `runtime-domain/.../config/model/ExecutionMatrixModels.kt`;
- `parseCompactionSettings` in `CompactionSettingsModels.kt`, which holds the 200,000-token trigger floor;
- `parseValidationGateRepoConfig` and `parseGradleWrapperPath` in `ValidationGateRepoConfigModels.kt`;
- the `review_context_budget` checks in `FileSystemRepoLocalConfig` and `ReviewContextBudgetPolicy`.

Where the runtime already knows models and agents:
- `MODEL_DIRECTIVE_CAPABLE_AGENTS` and `supportsModelDirective` in `InstallAgentModels.kt` (Junie is not capable).
- `ANTHROPIC_MODEL_ALIASES` in `runtime-infra/launcher/.../agentrun/AgentRunCommandBuilders.kt`.
- The provider namespace and the `ANTHROPIC_BASE_URL` plus `ANTHROPIC_MODEL` remap in
  `AgentRunLaunchModelResolution.kt`.
- The Opus constants in `runtime-domain/.../workflow/taskruntime/model/skeleton/PhaseLaunchModel.kt`.
- Executable resolution in `PathExecutableLookup.kt`.

## Agent CLI facts (measured 2026-10-26)

These were measured on the installed CLIs. They drive the check design.

- **`codex debug models`** prints JSON `{"models": [...]}` instantly and offline. Each entry has:
  - `slug`, `display_name` and `visibility` (`list` or `hide`);
  - `supported_reasoning_levels` (a list of `{effort, description}`);
  - `default_reasoning_level`.

  Efforts differ by model: for example, `gpt-6-luna` stops at `max`, while `gpt-6.1-sol` also offers `ultra`.
- **`agent --list-models`** (Cursor) prints `Available models`, then one `<id> - <display name>` line per model:
  about 265 models in about 1 second. The effort is part of the ID (`claude-opus-5-5-high`).
- **Claude has no list command.** `/v1/models` needs an API key, which subscription (OAuth) users do not have.
- **Claude reports an unknown model inside its JSON result and still exits 0.** The result has `is_error: true`
  and `api_error_status: 404`.
- **Claude ignores an unknown `--effort` value.** It prints a stderr warning (`Valid values: low, medium, high,
  xhigh, max`) and runs at the default effort, so a typo in effort never fails.
- **Codex rejects an unknown model or effort with HTTP 400** and exits 1. Its effort error lists the supported
  values.

## Requirements

### R1. Pane slot and toggle

- **Toggle.** A "Config" toggle sits next to "Specs" in the window accessory row (`composeApp/.../composition/Main.kt`).
  It uses the same pressed style as the Specs toggle.
- **Slot.** The Config pane occupies the Specs pane's slot to the right of the detail (`NavigationHost.kt`,
  `SpecsPaneHost`), at the same 320dp width. At most one of the two is open: opening one closes the other, and
  pressing the open pane's toggle closes it.
- **Close button.** The pane header has a close button.
- **Persistence.** The choice persists as one desktop setting with the values none, specs and config. It replaces
  `specPaneOpen`, and a stored `specPaneOpen = true` migrates to specs.
- **Terminal pane.** The terminal pane under the detail is unaffected.

### R2. Sections

- **Tabs.** The pane has the section tabs Models, Compaction, Project and System. The selected tab persists.
- **Scope line.** A line under the tabs names what the section edits:
  - `machine-wide · ~/.config/skill-bill/config.json` for Models and Compaction;
  - `<project> · <store config.yaml path>` for Project;
  - a short description for System.
- **Footer.** The footer says that changes apply to new runs. When the selected work item is running, it adds that
  this run keeps the models it recorded at launch. This is the resume rule in `runtime-kotlin/agent/decisions.md`:
  same-agent resume launches the recorded assignment and warns on drift.

### R3. Models section

- **Agent tabs.** There is one tab per installed agent (from the install selection) that supports model directives.
  An installed Junie shows as a disabled tab whose tooltip says it cannot take a model or effort setting. The
  default tab is the agent of the selected running work item, if one is selected; otherwise it is the first
  installed capable agent. A short note says that the matrix picks the model for the agent that runs a phase and
  never switches agents.
- **Tier rows.** Two rows, Reasoning and Implementation, show the tier directive's model and effort, plus the
  number of phases currently in that tier. A tier with no directive shows "agent default" and offers Set.
- **Phase list.** The phase list shows all runtime phases (today 24, the keys of `DEFAULT_PHASE_TIERS`), grouped by
  stage:
  - Planning: intake, preplan, plan, plan_subtasks.
  - Build: implement, simplify, implement_fix, build.
  - Audit: audit, audit_plan_fix, audit_implement_fix.
  - Review: review, verify_findings, present_findings.
  - Validate and QA: validate, qa, qa_fix.
  - Ship: write_history, commit_push, pr, monitor, monitor_fix.
  - Goal finish: goal_assessment, goal_assessment_fix.

  A phase the runtime adds later still appears, in an "Other" group.
- **Phase row contents.** Each row shows:
  - the phase id;
  - a badge when a `.skill-bill/phases/<phase>` file exists in the selected project (`code_review` counts for
    `review`);
  - the tier letter R or I, filled when `phase_tiers` moved the phase away from its default;
  - the effective model and effort.

  Values inherited from the tier are muted. A phase override is bold and marked with a dot. A value whose check
  state is near miss or unlisted carries a "typo?" or "unlisted" tag.
- **Filter.** A filter field narrows the phases by id. Groups with no match are hidden.
- **Inline editor.** Clicking a tier or phase row opens an inline editor below it. Only one editor is open at a
  time. It contains:
  - for phases only, a tier toggle (Reasoning, Implementation) labelled "applies to every agent", because it
    writes `phase_tiers`;
  - the model field (R4, R5);
  - effort chips (R6);
  - for phases only, the instruction file with Open, or Create when it is absent (Create makes
    `.skill-bill/phases/<phase>` in the project repository and opens it in the editor);
  - Save, Cancel, and Reset to tier when the phase has an override.

  Enter saves when Save is enabled, and Escape cancels.
- **Save rules.**
  - Saving a phase directive equal to its tier's directive removes the override.
  - Moving a phase back to its default tier removes its `phase_tiers` entry.
  - An unset effort omits the `effort` key. The runtime rejects `""` and `null`.

### R4. Model catalog (runtime)

- **One catalog per agent**, owned by the runtime behind a port. The desktop and the CLI use it in process.
  - **Codex:** run `codex debug models` and parse `slug`, `display_name`, `visibility`,
    `supported_reasoning_levels[].effort` and `default_reasoning_level`. Models with `visibility: hide` count as
    known when typed exactly but are not suggested.
  - **Cursor:** run `agent --list-models` and parse the `<id> - <display name>` lines.
  - **Claude:** read a list that ships as a runtime resource. Each entry has an id, a display name, an alias flag
    and a deprecated flag. The initial entries are the aliases `opus`, `sonnet` and `haiku`, and the IDs
    `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` and `claude-fable-5-1`.
- **Claude list as single source.** The Claude list becomes the single source for the Claude aliases and model
  constants the runtime hard-codes today: `ANTHROPIC_MODEL_ALIASES`, and the Opus constants where the module rules
  allow it. If a domain constant must stay because the domain cannot read resources, a guard test asserts that it
  agrees with the list.
- **Same environment as launches.** Agent executables resolve the way launches resolve them (`PathExecutableLookup`
  and the launch environment), so the pane and a run see the same lists.
- **Caching.** Each list is read on first use and cached for the desktop session. The Refresh action in the
  suggestion list reads it again. The CLI and the preflight read fresh. There is no polling timer.
- **Lenient parsing.** A missing executable, a non-zero exit, or output that does not parse makes the list
  "unavailable". It never makes a value "wrong".
- **No model requests.** No part of this feature launches an agent session or sends a request to a model.

### R5. Model check

The check is a pure function of (agent, typed value, catalog, launch environment). The pane, the CLI and the
preflight share it. It yields exactly one state; the board numbers are in parentheses.

1. **Known (01):** the value equals a listed id or alias. Save is enabled. The status shows the display name and
   the list's source.
2. **Near miss (02):** the value is not listed, and one of these holds for a listed entry:
   - it is equal once both are lowercased, stripped of every non-alphanumeric character, and stripped of a leading
     `claude-`;
   - it has the same digit sequence, and its letters are at most one edit (Levenshtein) away.

   Save waits. The status reads `"<value>" isn't a <agent> model. Did you mean <id>?`, with the actions
   "Use <id>" and "Keep as typed". A value kept as typed saves with a "typo?" tag on its row (04).
3. **Unlisted (03):** neither of the above. Save is enabled, the status says the value will be used as typed, and
   the row shows "unlisted". A new model never needs a Skill Bill release to be usable.
4. **Deprecated (09):** a listed entry marked deprecated. Save is enabled with a warning.
5. **Unavailable (08):** the agent's list could not be read. Save is enabled; the status names the reason and
   offers Try again; the row shows "unchecked".
6. **Not checked (10):** Claude with `CLAUDE_CODE_USE_BEDROCK` or `CLAUDE_CODE_USE_VERTEX` set, where model IDs are
   provider-specific. The status says why.
7. **Replaced by the environment (11):** Claude with a non-official `ANTHROPIC_BASE_URL` and `ANTHROPIC_MODEL` set.
   The launcher's remap replaces the value, so the status warns that runs use `ANTHROPIC_MODEL`.

Two more rules:
- **Empty value:** an empty model is an error, and the editor points to Reset to tier.
- **Suggestions:** the field suggests up to five listed models while typing, matching on substring and on the
  normalized form above, with a near-miss candidate first. Up and Down move through them, Enter picks, and
  Escape closes. The footer of the suggestion list names the source and when it was read.

### R6. Effort

- **Claude:** chips `low`, `medium`, `high`, `xhigh`, `max` and unset (`—`). Free text is not offered, because the
  Claude CLI silently ignores an unknown effort.
- **Codex:** chips from the selected model's `supported_reasoning_levels`, plus unset. The model's
  `default_reasoning_level` is marked.
  - If the model is not listed, the full union of known efforts is offered, with a note that the effort is not
    checked for this model.
  - A saved effort that the newly chosen model does not support blocks Save with
    `<model> doesn't support <effort>. Pick another effort.` (06).
- **Cursor:** no chips; a note says Cursor model IDs carry the effort. An effort already present in the config is
  shown with a Remove action, because the runtime merges it as `model[effort=…]` and refuses a conflict.

### R7. Writing machine config

- **Mutation services.** New runtime services mutate `execution_matrix` and `compaction` in the machine config.
  They go through the existing `TelemetryConfigStore` port (`read`, `write`, `configPath`) with a key-scoped
  read-modify-write.
- **Validation before writing.** The candidate value is validated with `parseExecutionMatrix` or
  `parseCompactionSettings` before writing. A refusal returns the parser's key path and reason.
- **Other keys stay byte-for-byte.** Every other top-level key and value is kept unchanged in meaning, including
  `install_id`, `telemetry` and the external sources.
- **Atomic writes.** The file is written atomically.
- **Malformed file.** A malformed file is never written. The service refuses with the read error.
- **Saved and Undo.** Each save shows "Saved" with Undo. Undo writes back the previous value through the same
  service.
- **Watching.** The pane watches the machine config file and the project config file with the platform watch
  service, as `NioSpecDirectoryChanges` does, with no polling.
  - On a change with no editor open, the pane re-reads silently.
  - With an editor open, it shows a banner: `config.json changed outside Skill Bill. Reloading drops your unsaved
    edit to <field>.` The banner has Reload and Keep editing.
  - A save after Keep editing re-reads the file and applies only the edited key, so an outside change to another
    key is kept.

### R8. Compaction section

- **Controls:** an enabled switch, the window size in tokens, and a trigger-percent slider (1 to 100).
- **Readout:** a live line, `Compacts at <window/100*pct> tokens.`
- **Floor:** a bar that draws the 200,000-token floor. A trigger below the floor blocks Save with the runtime's
  reason, and so does a non-positive window.
- **Per-phase overrides:** a list with Add, using the same row and editor patterns as the Models section.
- **Disabled:** when compaction is disabled, the other controls are hidden.

### R9. Project section

The section works on the project of the selected work item, with a Change picker that uses the same project list as
the Specs pane. It edits that project's store `config.yaml`.

- **Spec source:** segmented control `Local files` or `Linear`, writing `spec_type`.
- **Gradle wrapper:** a path field writing `validation_gate.gradle_wrapper`. It is validated with
  `parseGradleWrapperPath` (repo-relative, no `..`) and checked to exist in the project repository. Empty removes
  the key.
- **Review context budget:** an Advanced disclosure showing all ten limits.
  - Defaults are muted, changed values are marked, and the units are shown (KiB, MiB, counts).
  - The policy's byte, execution and relationship rules are enforced before saving.
  - The relationship rule: evidence result ≤ lane evidence ≤ lane launch ≤ parent packet.
- **Phase instructions:** the files under `.skill-bill/phases/`, with size, Open, and "Add for a phase".
- **Open config.yaml:** a button for the file itself.
- **Owned keys only.** Saves rewrite only the keys the pane owns. If the file has comments, the first save asks
  `Save drops <n> comments` with "Save anyway" and "Edit the file instead". The answer is remembered for that file
  until it changes.
- **Removed key.** A file with the removed `code_review_parallel_agent` key shows the runtime's refusal as in R12.

### R10. System section

- **Telemetry level:** a segmented control (Off, Anonymous, Full) through the existing
  `TelemetryConfigMutations.setTelemetryLevel`. The note under it states that lowering the level clears the unsent
  queue.
- **Proxy and batch size:** shown read-only.
- **Install selection, read-only:** the installed agents, the platform pack selection and the MCP registration.
  "Copy command" copies the reinstall command.
- **External sources:** platform-pack and add-on sources are listed, with Register and Remove through the existing
  services behind `skill-bill config register-external-platform-pack` and `unregister-external-platform-pack`.
- **Files:** the paths of `config.json` and `install-selection.json`, each with Open (`FileEditorLauncherPort`).
- **Desktop preferences stay in the Settings window.** A line links to it.

### R11. CLI command and goal launch preflight

- **CLI command.** `skill-bill config check-models` checks every model directive in the machine `execution_matrix`
  with the R5 check.
  - It prints one line per directive: key path, value, state and suggestion.
  - It exits non-zero when any directive is a near miss, or a Codex effort is unsupported by its model.
  - `--format json` prints the same results as a payload.
- **Goal launch preflight.** Before any phase starts, a goal launch checks the directives that apply to the agent
  the goal will run on.
  - A near miss, or a Codex effort its model does not support, refuses the launch with the config key path, the
    value and the suggested model. The desktop shows this inline where the launch was requested, with
    "Use <id>" and "Open in Config".
  - Unlisted, deprecated, unchecked and kept-as-typed values do not block. They appear as warnings in the launch
    output.

### R12. Malformed or unreadable files

- **Machine config.** When the machine config cannot be read or a section does not parse, the affected section
  shows the runtime's error, with the key path and value where the parser gives them, plus Open file and Reload.
  Saving is disabled there.
- **Project config.** The Project section does the same for `config.yaml`.
- **Other sections keep working** when their own data reads.

## Acceptance Criteria

1. The desktop window has a Config toggle next to Specs. The Config pane and the Specs pane share one 320dp
   right-hand slot, and at most one is open. The open pane persists as a single none, specs or config setting, and
   a stored `specPaneOpen = true` migrates to specs.
2. The Config pane shows the Models, Compaction, Project and System tabs, each with its scope line. The footer
   states that changes apply to new runs and that a running work item keeps its recorded models.
3. The Models section shows:
   - agent tabs for the installed capable agents, with Junie disabled and explained;
   - both tier rows;
   - every runtime phase grouped by stage, each with its tier letter, its effective model and effort, and its
     inherited, override, moved-tier, instruction-file and tag markers as specified in R3.

   The phase filter narrows the list.
4. The inline editor saves a tier directive, a phase override and a phase tier move. It removes an override equal
   to its tier, removes a `phase_tiers` entry that matches the default, and omits an unset effort. Reset to tier
   removes the override.
5. The runtime model catalog reads Codex models from `codex debug models` (with supported and default efforts),
   Cursor models from `agent --list-models`, and Claude models from a shipped runtime resource. It reports a list
   that cannot be read as unavailable. No part of the feature sends a model request.
6. The shipped Claude list is the single source for the Claude aliases the launcher uses. Any remaining domain
   model constant agrees with the list.
7. The model check returns exactly one of known, near miss, unlisted, deprecated, unavailable, not checked or
   replaced, by the rules in R5. With Claude's list, `opus5-5` is a near miss suggesting `claude-opus-5-5`, and
   `claude-opus-6-0` is unlisted.
8. In the editor, a near miss blocks Save until the operator picks "Use <id>" or "Keep as typed". Unlisted,
   deprecated and unavailable values save with their tag or warning, and suggestions appear while typing.
9. Effort chips follow R6:
   - Claude offers the fixed list.
   - Codex offers the selected model's supported efforts with its default marked, and an unsupported effort blocks
     Save.
   - Cursor offers no chips, and an existing Cursor effort can be removed.
10. The machine-config mutations validate with `parseExecutionMatrix` and `parseCompactionSettings` before writing,
    write atomically, keep every other key, and never write a malformed file. Each save offers Undo.
11. The pane re-reads silently after an outside change to a watched config file. With an editor open it shows the
    reload banner instead, and a later save keeps the outside change to other keys.
12. The Compaction section edits enabled, window and trigger percent, shows the live trigger readout and the
    200,000-token floor, blocks values below the floor, and lists per-phase overrides with Add.
13. The Project section edits `spec_type`, `validation_gate.gradle_wrapper` (validated and checked to exist) and
    `review_context_budget` (with the policy's rules) in the selected project's store `config.yaml`. It lists the
    phase instruction files with Open and Add. It asks once before a save drops comments.
14. The System section changes the telemetry level through the existing mutation. It shows the install selection
    read-only with a copy-reinstall action, lists and edits the external platform-pack and add-on sources through
    the existing services, and opens the config files.
15. `skill-bill config check-models` reports every machine-matrix directive's check state with key path and
    suggestion. It exits non-zero on a near miss or an unsupported Codex effort, and supports `--format json`.
16. A goal launch refuses before any phase starts when a directive for the goal's agent is a near miss or has an
    unsupported Codex effort, naming the key path and the suggestion. Other check states only warn. The desktop
    shows the refusal inline with "Use <id>" and "Open in Config".
17. A malformed or unreadable machine or project config shows the runtime's error, with its key path, Open file
    and Reload, in the affected section, and disables saving there.

## Test Obligations

- **Model check:** unit tests cover every R5 state and both near-miss rules:
  - `opus5-5` is a near miss for `claude-opus-5-5`;
  - `sonet` is a near miss for `sonnet`;
  - `gpt-6-lunaa` is a near miss for `gpt-6-luna`;
  - `claude-opus-5-6` and `claude-opus-6-0` are unlisted;
  - an empty value is an error.
- **Catalog parsing:** tests use recorded `codex debug models` JSON and recorded `agent --list-models` text,
  including hidden Codex models, extra whitespace, unknown JSON fields, a non-zero exit and garbage output.
  Each degrades to unavailable, never to wrong.
- **Machine-config mutations** are tested against a temporary config file:
  - other keys survive;
  - a malformed file is never written;
  - an invalid matrix or compaction value is refused with the parser's key path;
  - an override equal to its tier is removed;
  - `phase_tiers` returns to the default;
  - an unset effort omits the key;
  - Undo restores the previous file content.
- **Project config writes:** only owned keys change, comment detection works, and the gradle wrapper and budget
  rules hold.
- **Preflight and CLI:** tests show a near miss refusing a goal launch before any phase runs, a warning-only state
  letting it run, and the `check-models` exit code and JSON payload.
- **Desktop:** ViewModel tests cover:
  - the mutual exclusion of the Specs and Config panes, and the `specPaneOpen` migration;
  - agent tab defaulting;
  - editor save, cancel and reset;
  - near-miss blocking and Keep as typed;
  - Codex effort blocking;
  - the reload banner when a watched file changes with an editor open.
- **Fakes:** tests that set an environment pass an explicit non-empty environment map, because an empty map falls
  back to the host environment.

## Constraints

- **Architecture docs.** Follow `desktop-tray/ARCHITECTURE.md` and the composeApp architecture guards. The pane is a
  feature module (for example `:feature:config`) with its own UiState, Action, Effect and ViewModel in commonMain.
  It depends only on core:common, core:domain, core:designsystem and core:ui. Data goes through core:data ports,
  and runtime calls stay in process, as the Specs pane's do.
- **Runtime rules.** Runtime changes follow `docs/architecture-guidelines.md` and `runtime-kotlin/ARCHITECTURE.md`:
  - ports in runtime-ports, adapters in runtime-infra, no `java.nio` or ports imports in runtime-domain;
  - wire keys in owning `*Keys` objects;
  - no `//` comments in runtime-kotlin.
- **Executables.** Agent CLIs are executed through the runtime's process adapters, never by locating an installed
  `skill-bill` CLI on PATH.
- **No repeating timers.** The desktop forbids repeating timers in data and feature code. File changes come from
  the platform watch service.
- **Planning rules.** Every subtask spec the planner writes is independent:
  - no subtask dependencies, and no ordering between subtasks;
  - each subtask runs on the current tree and applies its rule to what is present.

  Tests go under Test Obligations, never into acceptance criteria. Acceptance criteria are numbered lists.

## Non-Goals

- A live probe or test request to any model.
- Editing the install selection (agents, platform packs, MCP registration).
- Per-launch options: `qa:`, quality gate selection, `--phase-model`.
- Moving desktop preferences into the pane.
- Editing `telemetry.proxy_url`, `telemetry.batch_size` or `install_id`.
- A model list fetched from Anthropic's API.

## Planning Notes

These notes are non-binding. They suggest one decomposition into independent subtasks; the planner decides.

1. **Runtime model catalog and check.** The catalog port and the adapters for Codex, Cursor and Claude, the shipped
   Claude list as the single alias source, and the pure check (R4, R5, R6 rules).
2. **Runtime machine-config mutations** for `execution_matrix` and `compaction`, with Undo support (R7).
3. **Runtime project-config mutations** for `spec_type`, `validation_gate` and `review_context_budget`, with comment
   detection (R9 writes).
4. **`skill-bill config check-models` and the goal launch preflight check** (R11 runtime side). If the catalog from
   subtask 1 is not present on the tree, this subtask creates the minimal catalog and check it needs, in the same
   place subtask 1 would.
5. **Desktop data layer:** core:domain models, a core:data `ConfigRepository` port and runtime adapter, the file
   watching, and the right-pane setting migration.
6. **Desktop `:feature:config` pane:** the toggle and slot, the four sections, the editor and the states from the
   boards (R1 to R3, R8 to R10, R12, and the desktop side of R11).
