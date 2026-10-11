# SKILL-418 Desktop tray app for monitoring Skill Bill goals and standalone phases

## Outcome

A cross-platform desktop tray app monitors every concurrent Skill Bill feature goal and standalone phase (review, validation, plan, pr, monitor) on the machine, across repositories. It reads only through the skill-bill CLI. Two new governed JSON commands feed it. Design authority is `design/App/Skill Bill Tray v2.dc.html` sections 2 (ids 2a, 2a2 as 2a′, 2b–2j) and 3 (table id t3). `design/App/Skill Bill Tray.dc.html` is superseded.

## Scope

1. Governed `skill-bill work board` and `skill-bill work detail` JSON commands, schemas, Kotlin contract versions, payload keys, failure codes, loud-fail parse seams, and query paths that reuse `IdeStatusProjector`, `IdeStatusService`, `GoalRunnerStatusService`, the goal manifest store, and the standalone phase status repository.
2. A new top-level Gradle sibling `desktop-tray` (Compose Multiplatform Desktop, JDK 21) that is not included in the `runtime-kotlin` build. Native tray on macOS, Windows, and Linux StatusNotifierItem. Not `java.awt.SystemTray`.
3. Tray popover 2a/2a′, menu 2b, main window 2c–2e, history 2f, notifications 2g, settings 2h, empty/problem/stale 2i, per-OS table 2j including start at login and installer files.
4. App mutations limited to `skill-bill goal pause <KEY> --repo-root <root>` and `skill-bill goal stop <KEY> --repo-root <root>`, with the same safety rules as `../../intellij-plugin/README.md` Goal controls.

## Subtasks

1. Governed work board and detail CLI. Schemas, engine query paths, and commands land together so the JSON exists before the app.
2. Desktop tray application. New Gradle sibling consumes those commands, plus pause/stop, and ships UI 2a–2j, CI, and installer files.

Condition for the split: the app cannot be implemented against contracts that do not exist yet. The two surfaces ship independently. Layer order inside each surface is not a further split.

## Acceptance Criteria

1. `skill-bill work board --format json` lists every live feature goal and standalone phase across repositories, plus items finished within the requested retention window, each item carrying the ide-status 0.3 snapshot fields plus decoded `repository_root`. The command does not take `--repo-root`.
2. `skill-bill work detail <workflow-id|invocation-id> --format json` returns one item in full: the same snapshot plus `repository_root`, manifest feature title, subtasks (id, name, status, commit sha, blocked reason, dependencies), phase records / step pipeline for the current subtask, active agent and execution liveness, goal ledger (phase attempt counts, cumulative fix iterations, findings in scope, re-attempt causes, supervisor kill count), latest observability event, latest worktree edit, and completed subtask validation.
3. Each new payload has a contract schema under `../../orchestration/contracts`, a Kotlin contract version starting at `"1.0"`, a payload key object for keys not already on `IdeStatusPayloadKeys` or `SharedPayloadKeys`, a parity test, `WorkFailureCode` entries, and loud-fail parse seams. Board and detail do not extend ide-status contract 0.3 and do not add fields to `IdeStatusSnapshot`.
4. Board and detail reuse `IdeStatusService` / `IdeStatusProjector` (including `GoalRunnerStatusService` and `FeatureTaskRuntimeStatusService` calls the projector already makes), the goal manifest store, and the standalone phase status repository. They do not re-derive lifecycle, freshness, step, progress, planning, pause, or activity, and they do not call `IdeStatusService.status()` for the machine-wide board.
5. A new top-level directory `desktop-tray` holds a Compose Multiplatform Desktop Gradle build on JDK 21, not included from `../../runtime-kotlin/settings.gradle.kts`. Package layering matches the IntelliJ plugin (domain, application, infrastructure/cli, presentation, ui, composition) with architecture tests that ban presentation→infrastructure imports, process/JSON/filesystem tokens in presentation, and any SQLite/JDBC/`skillbill.ports.db` use in the app.
6. The tray popover matches design 2a/2a′: header poll age from last successful board `observed_at`; counts Running, Needs you, Done 24h; Goals and Phases tabs with counts and an attention dot; last tab restored on reopen. Goal rows sort blocked, failed, active, planning, paused and show step or phase, loop or pass count, locally ticking goal and current-subtask clocks, n/m subtask progress (planning wave while planning), pause-requested and pause-reason lines, and stale treatment. Phase rows show phase id, repo basename, branch, current activity, step and bounded counts, plus phases finished in the last hour.
7. Tray icon states are idle, running with count, needs you, and failed. Right-click menu 2b is grouped into Goals and Phases and includes Open Skill Bill, History, Refresh now, Settings, and Quit. Native tray integration anchors the popover to the icon (StatusNotifierItem on Linux).
8. The main window 2c has a sidebar (Needs you, Goals running, Standalone phases, filter by repo, issue, or branch). Clicking a tray row or menu item opens the window with that item selected. Goal detail shows lifecycle, issue, kind and freshness chips, title, repo, path, branch, Pause after current subtask and Stop goal only while active with Pause disabled once pause is requested, metrics, subtask list with phase pipeline chips for the current subtask and goal-finish pr/monitor, latest activity, execution / attempts-and-fixes / validation panels hidden when empty, and a copyable goal status command.
9. Blocked goal view 2d shows pause reason first and a copyable `skill-bill <KEY> resume` command, with no Resume button. Standalone phase view 2e shows the step pipeline from the detail payload (pr: commit_push, pr, monitor; monitor: commit_push, monitor; plan: preplan, plan; review and validation single step), current activity, and no stop control. Both render in the main window detail pane.
10. History 2f has All, Goals, and Phases tabs over the configured retention window. Notifications 2g fire only on blocked, failed, goal completed, pause took effect, and standalone phase finished, each toggleable, using the native OS mechanism; clicking one opens that item.
11. Settings 2h persist CLI path override; resolution order is override, then PATH including the login shell PATH, then `SKILL_BILL_BIN_DIR`, then `~/.local/bin`, plus `.bat` and `.cmd` on Windows; poll interval 5, 15 (default), 30, 60 seconds; history retention 6h, 24h (default), 7d; notification toggles; start at login (macOS login item, Windows current-user startup, Linux XDG autostart).
12. Empty, problem, and stale states 2i cover idle (empty healthy board), CLI not found, incompatible board `contract_version`, no database yet (`absent_database`), and stale rows (`freshness=stale`).
13. Per-OS table 2j: popover anchoring, macOS template icon with count text, Windows and Linux overlay badge, main window close hides to tray, native notifications, start at login, and installer files `.dmg`, `.msi`, `.deb`, `.rpm`, and AppImage. Installers are produced unsigned unless the operator supplies signing secrets.
14. Pause and stop are the only mutations, each a bounded CLI invocation on its own runner, never the poll runner, dispatched off the UI thread. Failures are bounded summaries with no process output, stderr, exception text, or paths. The next board poll is authoritative. Show pause/stop only for an active feature-goal with an issue key. The app never kills processes.
15. `WorkListCommand`, `WorkStatusCommand`, ide-status contract 0.3 semantics, `IdeStatusSnapshot`, and the IntelliJ and VS Code plugin trees are unchanged.

## Non-goals

- Launching, resuming, retrying, or abandoning work from the app.
- A Resume button.
- Stopping standalone phases.
- Opening the SQLite database from the app.
- Store publishing or code signing beyond producing installer files.
- Changing ide-status contract 0.3 semantics or adding board fields onto `IdeStatusSnapshot`.
- Changing the IntelliJ or VS Code plugins.
- Including `desktop-tray` in the `runtime-kotlin` Gradle build.

## Constraints

- Runtime changes follow `../../AGENTS.md` Runtime Contract Schemas, `docs/architecture-guidelines.md`, `runtime-kotlin/ARCHITECTURE.md`, and `docs/code-principles.md`: wire keys only via owning Keys objects, no `//` or block comments in runtime Kotlin, KDoc only on interfaces, loud-fail typed `SkillBillRuntimeException`.
- The desktop app follows IntelliJ plugin layering with architecture tests. It never imports `skillbill.infrastructure.sqlite`, `skillbill.ports.db`, JDBC, or SQLite.
- Design v2 is the screen and field map. Implement confirms wire key names against section 3 table t3 when a name in this spec is ambiguous, preferring the design table.
- Linear comments were unavailable at preplan. Pasted tracker notes later are overrides only when they conflict with this spec.
- Assumption: repository default branch is `main`. Feature branch name in the decomposition manifest is `feat/SKILL-418-desktop-tray-app-for-monitoring-skill-bill-goals-and-standalone-phases`.

## Settled decisions

- Directory name is `desktop-tray`. Base package is `dev.skillbill.tray` unless the IntelliJ plugin's published group id requires a different prefix; implement confirms against `intellij-plugin` and keeps `dev.skillbill.*`.
- Board flag is `--finished-within` with values `6h`, `24h`, `7d`, default `24h`. The app passes the user's history retention setting.
- Schema files are `orchestration/contracts/work-board-schema.yaml` and `orchestration/contracts/work-detail-schema.yaml`. Kotlin constants `WORK_BOARD_CONTRACT_VERSION` and `WORK_DETAIL_CONTRACT_VERSION` start at `"1.0"`, beside `IdeStatusContractVersions`. Locators follow `IdeStatusSchemaPaths`: SOURCE `orchestration/contracts/<file>.yaml`, CLASSPATH `skillbill/infrastructure/contracts/<file>.yaml`, EXPECTED_SCHEMA_ID `https://skill-bill.dev/contracts/<file>.yaml`.
- New schema errors live on a dedicated `WorkFailureCode` in `skillbill.error.shellcontent` (`INVALID_WORK_BOARD_SCHEMA`, `INVALID_WORK_DETAIL_SCHEMA`), not on `InstallFailureCode`.
- If `WireVocabularyGovernedSeamInventory` already lists work status, register board and detail the same way.
- Repository root is decoded from `FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX` (`repo-root-realpath-v1:`) plus an absolute Git top-level path. A missing or non-absolute identity is omitted from `items` and recorded in a board `diagnostics` array. The command does not invent a path and does not fail the whole board for one bad row.
- Board `status()` is the wrong entry: it is single-winner, requires one repo root, and scopes standalone rows to the process checkout branch. Add a board method on `IdeStatusService` in the same package so it can call internal projector APIs, without `IdeStatusSelectionPolicy` and without the current-branch filter. Reuse `collectCandidates` / `toCandidate` / `projectStandaloneLease` / `standaloneLifecycle` / `isExcludedGoalChild` and keep `ideStatusValidator.validate(snapshot, sourceLabel)`.
- Standalone board data needs a new `StandalonePhaseStatusRepository` read (and SQLite store query) that returns live rows plus rows whose `finishedAt` is inside the retention window, all identities. Do not reuse `readEligible` as the board query.
- Detail matches `workflowId` on work/goal rows or `invocationId` on standalone phase records.
- Popover "Done 24h" counts items finished in the last 24 hours. History 2f lists items inside the configured retention window. Board `--finished-within` matches retention so history is complete. If retention is `6h`, the Done 24h count can only include what the board returned; do not widen the CLI window past the user setting.
- Goals tab is `feature-goal`. Phases tab is `execution.scope == standalone_phase` and any non-goal workflow family (including `feature-verify`).
- Needs you = lifecycle `blocked` or `failed`, or pause code `awaiting_operator_decision`.
- Native tray: implement picks a maintained library that can show a StatusNotifierItem on Linux and position a Compose popup against the icon. `java.awt.SystemTray` is rejected. Record the chosen coordinate in the desktop-tray architecture notes if the tree has an equivalent of `ARCHITECTURE.md`; otherwise leave it in the Gradle catalog. Compose Multiplatform Gradle plugin version is the current stable at implement time.
- Unsigned installers in CI. Do not block the app on notarization, Authenticode, or store publishing.
- Copyable resume text is `skill-bill <KEY> resume`. Copyable goal status is the existing goal status invocation with that issue key and `--repo-root`; implement confirms the exact argv from the existing goal CLI.
- Hide-when-empty: CLI may omit empty optional objects; the app also hides execution, attempts-and-fixes, and validation panels when they have no data.
- GNOME without AppIndicator, and KDE panels that swallow left click: menu 2b remains the way to reach the same actions.

## Validation strategy

Validate phase runs the pack validation gate and `desktop-tray`'s own `./gradlew check`. It does not fold the app into `runtime-kotlin/gradlew check`. This plan phase runs no compile, tests, or check. Installers are proven as files and Gradle packaging tasks in the tree; local `./gradlew run` of the Compose app is enough during implement to reason about tray and window, but implement and audit do not treat a running app as an acceptance receipt.

## Next path

```bash
skill-bill goal SKILL-418
```
