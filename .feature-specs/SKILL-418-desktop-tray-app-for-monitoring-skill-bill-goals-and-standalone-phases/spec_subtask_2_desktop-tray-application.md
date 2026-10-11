# Subtask 2. Desktop tray application

## Scope

Create top-level `desktop-tray`, a Compose Multiplatform Desktop Gradle build on JDK 21, not included in `runtime-kotlin/settings.gradle.kts` (there is no root `settings.gradle.kts`; do not add one that includes the runtime). Pattern: `intellij-plugin/settings.gradle.kts` (own `rootProject.name`, own `pluginManagement`) and `intellij-plugin/build.gradle.kts` `kotlin.jvmToolchain(21)`.

The app reads only through the skill-bill CLI: poll `work board`, fetch `work detail`, invoke existing `goal pause` / `goal stop`. It never opens SQLite.

Ship UI 2a–2j from `design/App/Skill Bill Tray v2.dc.html`, architecture tests, start at login, a path-filtered GitHub workflow analogous to `.github/workflows/plugin-ci.yml`, and installer files `.dmg`, `.msi`, `.deb`, `.rpm`, AppImage. Unsigned unless the operator supplies signing secrets.

## Implementation details

### Gradle sibling and layering

Packages, inward deps, matching `intellij-plugin/ARCHITECTURE.md`: domain, application, infrastructure (CLI adapter + prefs), presentation, ui, composition. Presentation must not import infrastructure. Domain and application must not import `ProcessBuilder`, `java.lang.Process`, or Compose/AWT windowing. No `skillbill.infrastructure.sqlite`, `skillbill.ports.db`, JDBC, or SQLite anywhere in the app.

Base package: `dev.skillbill.tray`. Implement confirms against the IntelliJ plugin group and keeps `dev.skillbill.*`.

Architecture tests copy the source-scan style of `intellij-plugin/src/test/kotlin/dev/skillbill/intellij/architecture/PluginArchitectureTest.kt` (presentation→infrastructure ban, forbidden process/JSON/filesystem tokens in presentation, forbidden runtime persistence imports). Do not edit that plugin test.

Compose Multiplatform Gradle plugin version is the current stable at implement time. Native tray: pick a maintained library that supports macOS, Windows, and Linux StatusNotifierItem and can position a Compose popup against the icon. Reject `java.awt.SystemTray`. Record the coordinate in `desktop-tray` Gradle version catalog or an architecture note in that tree.

### CLI infrastructure

Resolution order: settings override, then PATH including the login shell PATH (GUI apps do not inherit it; match IntelliJ plugin troubleshooting behavior), then `SKILL_BILL_BIN_DIR`, then `~/.local/bin`, plus `skill-bill.bat` and `skill-bill.cmd` on Windows.

Poll `skill-bill work board --format json --finished-within <retention>`. Fetch `skill-bill work detail <id> --format json` when the main window needs the extras. Poll interval 5, 15 (default), 30, 60 seconds.

Pause and stop: exactly `skill-bill goal pause <KEY> --repo-root <root>` and `skill-bill goal stop <KEY> --repo-root <root>`. Safety, copied from `intellij-plugin/README.md` Goal controls and `intellij-plugin/ARCHITECTURE.md` Goal controls:

- Show only for an active feature-goal with an issue key.
- Disable Pause when `pause_requested` is true.
- No Resume button.
- Each mutation on its own `ProcessRunner` instance, never the poll runner.
- Dispatch off the UI thread.
- Failures are bounded summaries with no stdout, stderr, exception text, or paths.
- The app never kills processes.
- The next board poll is authoritative.

Copyable resume on blocked view 2d is the text `skill-bill <KEY> resume` only. Copyable goal status on 2c is the existing goal status invocation with that key and repo root; implement confirms the exact argv from the goal CLI.

Incompatible board `contract_version` (not equal to `WORK_BOARD_CONTRACT_VERSION` `"1.0"`) is a dedicated problem state. Do not parse leniently.

### Tray popover 2a / 2a′ and icon 2b

- Header poll age: last successful board `observed_at` vs local now.
- Counts: Running; Needs you = lifecycle `blocked` or `failed` or pause code `awaiting_operator_decision`; Done 24h = items finished in the last 24 hours from the board payload.
- Goals and Phases tabs with counts and an attention dot. Last tab persisted and restored.
- Goals tab: feature-goal. Phases tab: `execution.scope == standalone_phase` and non-goal families (including `feature-verify`).
- Goal row sort: blocked, failed, active, planning, paused.
- Goal rows: step or phase, loop or pass count, goal and current-subtask clocks ticking locally from `active_duration_ms + now − active_duration_as_of` between polls (settled rows freeze), n/m subtask progress (`progress.completed/total`, or `planning.planned_subtask_count/total_subtask_count` and `planning_wave_subtask_ids` while planning), pause-requested and pause-reason lines, stale treatment when `freshness=stale`. Planning color when `planning.state != prepared`.
- Phase rows: `phase_id`, repo basename of `repository_root`, `branch_correlation`, `current_activity`, `current_phase_execution` count/total (example `monitor_fix 1/3`), plus phases finished in the last hour.
- Icon states: idle, running with count, needs you, failed. macOS template icon with count text; Windows and Linux overlay badge.
- Right-click menu grouped into Goals and Phases: Open Skill Bill, History, Refresh now, Settings, Quit.
- GNOME without AppIndicator, and KDE panels that swallow left click: menu 2b is the fallback to the same actions.

### Main window 2c–2e

One window, sidebar: Needs you, Goals running, Standalone phases, filter by repo, issue, or branch. Clicking a tray row or menu item opens the window with that item selected. Main window close hides to tray.

Goal detail: lifecycle, issue, kind and freshness chips; title; repo, path, branch; Pause after current subtask and Stop goal shown only while active; Pause disabled once a pause is requested; metrics (subtask, phase, goal active, subtask active); subtask list from the detail payload with phase pipeline chips for the current subtask and goal-finish pr, monitor; latest activity; execution, attempts and fixes, and validation panels hidden when they have no data; copyable goal status command.

Blocked 2d: pause reason first, copyable `skill-bill <KEY> resume`, no Resume button. Renders in the same detail pane.

Standalone phase 2e: `step_pipeline` from the detail payload, current activity, no stop control.

### History, notifications, settings, empty states

- History 2f: All, Goals, Phases over the retention window (6h, 24h default, 7d). Board `--finished-within` matches that setting.
- Notifications 2g: only transitions blocked, failed, goal completed, pause took effect, standalone phase finished. Each toggleable. Native mechanism per OS. Click opens that item.
- Settings 2h: CLI path override, poll interval, history retention, notification toggles, start at login (macOS login item, Windows current-user startup, Linux XDG autostart).
- Empty/problem/stale 2i: idle (empty board, CLI healthy), CLI not found, incompatible board contract version, no database yet, stale rows.

### CI and installers

Add a GitHub workflow analogous to `.github/workflows/plugin-ci.yml`: path-filter the `desktop-tray` tree and run `(cd desktop-tray && ./gradlew clean check --no-build-cache)` on `ubuntu-latest` with JDK 21 Temurin. Do not fold the app into `runtime-kotlin/gradlew check`. `plugin-release.yml` is the artifact-copy pattern; a tray release workflow can wait until installer tasks exist in this same subtask, then copy that pattern if it is a small addition. Do not publish to stores.

Installers: `.dmg`, `.msi`, `.deb`, `.rpm`, AppImage. Unsigned in CI. Table 2j's signed `.dmg` is overridden by the non-goal: no code signing beyond producing installer files, unless the operator supplies secrets (do not block on missing secrets).

### UI mapping authority

Behavioral mapping in this spec and the parent spec is binding. Visual layout authority is design section 2. Field-to-source mapping authority is design section 3 table t3. Implement reads those design sections when a pixel or label question remains. Do not read earlier `Skill Bill Tray.dc.html`.

## Acceptance Criteria

1. `desktop-tray/` is a Compose Multiplatform Desktop Gradle build with JDK 21 toolchain, own settings, and no include from `runtime-kotlin/settings.gradle.kts`. Packages are domain, application, infrastructure, presentation, ui, composition under `dev.skillbill.tray` (or the confirmed `dev.skillbill.*` prefix). Architecture tests ban presentation→infrastructure imports, process/JSON/filesystem tokens in presentation, process APIs in domain/application, and SQLite/JDBC/`skillbill.ports.db` anywhere in the app.
2. Native tray is not `java.awt.SystemTray`. It uses a StatusNotifierItem-capable backend on Linux and anchors the popover to the icon on macOS, Windows, and Linux. Icon states idle, running with count, needs you, failed; macOS template icon with count text; Windows/Linux overlay badge. Menu 2b groups Goals and Phases and includes Open Skill Bill, History, Refresh now, Settings, Quit.
3. Popover 2a/2a′ shows poll age from `observed_at`, Running / Needs you / Done 24h counts as defined in Implementation details, Goals and Phases tabs with counts and attention dot, persisted last tab, goal sort and row fields including locally ticking clocks and stale treatment, and phase rows including last-hour finished phases.
4. CLI resolution follows override, login-shell PATH, `SKILL_BILL_BIN_DIR`, `~/.local/bin`, plus `.bat`/`.cmd` on Windows. Board poll passes `--finished-within` matching retention. Detail is fetched for the main-window extras. Settings persist poll interval (default 15s) and retention (default 24h).
5. Main window 2c–2e matches Implementation details: sidebar and filters, selection from tray/menu, close-to-tray, goal detail chips and metrics, pause/stop eligibility, hidden empty panels, copyable goal status, blocked resume text without a Resume button, standalone step pipeline from the payload with no stop control.
6. History 2f, notification transitions and toggles 2g, settings 2h including start at login, and empty/problem/stale states 2i (idle, CLI not found, incompatible `contract_version`, `absent_database`, stale rows) exist as specified. Notifications use the native OS mechanism; a click opens that item.
7. Pause and stop invoke only those two CLI argv shapes, each on its own runner, never the poll runner, off the UI thread, with bounded failure summaries and no process output, stderr, exception text, or paths. Shown only for an active feature-goal with an issue key. Pause disabled when `pause_requested` is true. The app does not launch, resume, retry, abandon, or stop standalone phases, and does not kill processes.
8. Installer tasks or outputs for `.dmg`, `.msi`, `.deb`, `.rpm`, and AppImage exist in the `desktop-tray` build. They are unsigned unless secrets are present. A GitHub workflow path-filters `desktop-tray` and runs `(cd desktop-tray && ./gradlew clean check --no-build-cache)` on ubuntu-latest JDK 21 Temurin.
9. Tests listed in test obligations exist. The IntelliJ and VS Code plugin trees are unmodified.

## Non-goals

- Launching, resuming, retrying, or abandoning work.
- Resume button.
- Stopping standalone phases.
- Reading SQLite.
- Store publishing or blocking on code signing / notarization.
- Changing ide-status 0.3, work list/status, or the plugins.
- Including this project in `runtime-kotlin/gradlew check`.
- Re-deriving status in the app; the CLI payloads are the source.

## Dependency notes

Depends on subtask 1. The app binds to work-board and work-detail contract `"1.0"`. Treat mismatch as incompatible, not as a parse-tolerant upgrade.

Assumption: directory name `desktop-tray`. Assumption: package `dev.skillbill.tray`. Assumption: Compose plugin version chosen at implement as current stable. Assumption: native tray library chosen at implement; must meet StatusNotifierItem and popup-anchor requirements. Assumption: copyable goal status argv is the existing goal status command plus issue key and `--repo-root`. Assumption: Done 24h is a 24-hour count even when history retention is 7d; when retention is 6h the count cannot exceed the board window.

## Test obligations

1. Architecture tests modeled on `PluginArchitectureTest`: presentation must not import infrastructure; domain/application must not reference `ProcessBuilder` / `Process` / windowing; no SQLite/JDBC/runtime persistence imports in the app. Bug: presentation shelling out, or the app opening the database the CLI already reads.
2. CLI resolution consults login-shell PATH after override and before giving up. Bug: a desktop-launched app that only reads process PATH reports CLI not found.
3. Board `contract_version` other than `"1.0"` surfaces the incompatible-contract problem state and does not render items. Bug: lenient parse of a future or stale envelope.
4. Pause/stop adapter uses a runner instance distinct from the poll runner, and a failed mutation maps to a bounded summary with no stdout, stderr, exception text, or paths. Bug: sharing the poll runner returns a poll exit as a mutation result (already hit in the IntelliJ plugin).
5. Notification publisher emits only the five lifecycle transitions and suppresses a type whose toggle is off. Bug: notifying on every poll, or ignoring toggles.

No tests for Compose layout glue, icon painting, or installer file bytes. CI is a workflow file audit can read. Architecture tests are not omission candidates.

## Validation strategy

Validate phase runs the pack validation gate and `desktop-tray`'s `./gradlew check`. Implement and audit do not run those commands. Audit inspects the new tree, architecture tests, CLI adapters, UI mappings against design v2, the workflow file, and installer task wiring. Local `./gradlew run` may be used by a later validate or by an operator; it is not an implement acceptance receipt.

## Next path

```bash
skill-bill goal SKILL-418
```
