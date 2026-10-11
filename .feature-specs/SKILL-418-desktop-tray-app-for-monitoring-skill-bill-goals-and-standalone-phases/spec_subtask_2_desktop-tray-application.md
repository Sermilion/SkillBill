# Subtask 2. Desktop tray application

## Scope

Create top-level `desktop-tray`, a Compose Multiplatform Desktop Gradle build on JDK 21, not included in `../../runtime-kotlin/settings.gradle.kts` (there is no root `settings.gradle.kts`; do not add one that includes the runtime). Pattern: `intellij-plugin/settings.gradle.kts` (own `rootProject.name`, own `pluginManagement`) and `intellij-plugin/build.gradle.kts` `kotlin.jvmToolchain(21)`.

The app reads only through the skill-bill CLI: poll `work board`, fetch `work detail`, invoke existing `goal pause` / `goal stop`. It never opens SQLite.

Ship UI 2a–2j from `design/App/Skill Bill Tray v2.dc.html`, architecture tests, start at login, a path-filtered GitHub workflow analogous to `../../.github/workflows/plugin-ci.yml`, and installer files `.dmg`, `.msi`, `.deb`, `.rpm`, AppImage. Unsigned unless the operator supplies signing secrets.

## Implementation details

### Gradle sibling and layering

Packages, inward deps, matching `../../intellij-plugin/ARCHITECTURE.md`: domain, application, infrastructure (CLI adapter + prefs), presentation, ui, composition. Presentation must not import infrastructure. Domain and application must not import `ProcessBuilder`, `java.lang.Process`, or Compose/AWT windowing. No `skillbill.infrastructure.sqlite`, `skillbill.ports.db`, JDBC, or SQLite anywhere in the app.

Base package: `dev.skillbill.tray`. Implement confirms against the IntelliJ plugin group and keeps `dev.skillbill.*`.

Architecture tests copy the source-scan style of `../../intellij-plugin/src/test/kotlin/dev/skillbill/intellij/architecture/PluginArchitectureTest.kt` (presentation→infrastructure ban, forbidden process/JSON/filesystem tokens in presentation, forbidden runtime persistence imports). Do not edit that plugin test.

Compose Multiplatform Gradle plugin version is the current stable at implement time. Native tray: pick a maintained library that supports macOS, Windows, and Linux StatusNotifierItem and can position a Compose popup against the icon. Reject `java.awt.SystemTray`. Record the coordinate in `desktop-tray` Gradle version catalog or an architecture note in that tree.

### CLI infrastructure

Resolution order: settings override, then PATH including the login shell PATH (GUI apps do not inherit it; match IntelliJ plugin troubleshooting behavior), then `SKILL_BILL_BIN_DIR`, then `~/.local/bin`, plus `skill-bill.bat` and `skill-bill.cmd` on Windows.

Poll `skill-bill work board --format json --finished-within <retention>`. Fetch `skill-bill work detail <id> --format json` when the main window needs the extras. Poll interval 5, 15 (default), 30, 60 seconds.

Pause and stop: exactly `skill-bill goal pause <KEY> --repo-root <root>` and `skill-bill goal stop <KEY> --repo-root <root>`. Safety, copied from `../../intellij-plugin/README.md` Goal controls and `intellij-plugin/ARCHITECTURE.md` Goal controls:

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

Add a GitHub workflow analogous to `../../.github/workflows/plugin-ci.yml`: path-filter the `desktop-tray` tree and run `(cd desktop-tray && ./gradlew clean check --no-build-cache)` on `ubuntu-latest` with JDK 21 Temurin. Do not fold the app into `runtime-kotlin/gradlew check`. `plugin-release.yml` is the artifact-copy pattern; a tray release workflow can wait until installer tasks exist in this same subtask, then copy that pattern if it is a small addition. Do not publish to stores.

Installers: `.dmg`, `.msi`, `.deb`, `.rpm`, AppImage. Unsigned in CI. Table 2j's signed `.dmg` is overridden by the non-goal: no code signing beyond producing installer files, unless the operator supplies secrets (do not block on missing secrets).

### UI mapping authority

Behavioral mapping in this spec and the parent spec is binding. Visual layout authority is design section 2. Field-to-source mapping authority is design section 3 table t3. Implement reads those design sections when a pixel or label question remains. Do not read earlier `Skill Bill Tray.dc.html`.

## Implementation Details

This ordered plan covers only subtask 2 and consumes the upstream preplan digest. AC-001 through AC-009 refer to the numbered acceptance criteria below. Subtask 1 supplies the governed CLI contracts; this plan does not change that dependency or plan its implementation. Existing screen requirements, test obligations, scope, and validation strategy remain binding.

### 1. Establish the independent desktop build and boundaries

Serves AC-001 and AC-009. Create `desktop-tray/settings.gradle.kts`, `build.gradle.kts`, the project's Gradle wrapper, version catalog, resources, and source/test roots. Use Compose Multiplatform Desktop and `kotlin.jvmToolchain(21)`, with package root `dev.skillbill.tray`, confirmed by the digest. Keep the project independent of the runtime settings and do not create a root settings file that includes the runtime. Limit production additions to the desktop tree and the desktop CI workflow. Leave IntelliJ and VS Code plugin trees unchanged.

Create domain value models, application use cases and ports, infrastructure CLI/preferences/native adapters, presentation state/projections, Compose UI, and composition wiring. Application owns polling, selection, mutations, preferences, notifications, and shutdown lifetimes. Composition constructs concrete adapters. Presentation consumes typed application/domain data and never decodes JSON, accesses files, or starts processes. Native windowing stays outside domain/application. Do not access runtime persistence from any layer.

Add architecture guards under `desktop-tray/src/test/kotlin/dev/skillbill/tray/architecture` for every mandated dependency/token ban, including process/JSON/filesystem tokens in presentation and windowing in domain/application. Preserve these guards through later repairs. Implement may consult the digest's read-only plugin architecture patterns; this plan does not rediscover them. No tests for ordinary Compose wiring.

### 2. Bind the governed contracts and persist settings

Serves AC-003, AC-004, AC-005, AC-006, and AC-009. Add typed board/detail transport decoding in `infrastructure/cli`, domain work models, application settings/clock ports, and an infrastructure preference store. Require envelope version `"1.0"` independently for board and detail, and retain the nested ide-status snapshot version `"0.3"`. Reject malformed required data and incompatible envelopes before exposing items to presentation. Treat `absent_database` as its dedicated expected problem, distinct from a healthy empty board and CLI-not-found.

Use subtask 1's emitted schema as the wire authority. Planning assumption: each board item and detail result wraps the unchanged snapshot with repository root and completion metadata, with detail extras outside the snapshot. Implement confirms the actual nesting and completion-provenance names against the supplied contracts instead of altering runtime schemas. Explicitly map flattened snapshot execution keys such as `execution_scope`, `execution_id`, `status_store_id`, `run_sequence`, `status_revision`, `invocation_id`, and `phase_id` to typed models. Keep decimal-string sequence/revision precision. Never derive completion from `updated_at`, a heartbeat, or poll time. Preserve an estimated completion provenance when provided; if completion evidence is absent, exclude that item from finished-window counts rather than inventing it.

Persist CLI override, poll interval restricted to 5/15/30/60 seconds with default 15, retention restricted to 6h/24h/7d with default 24h, the five individual notification switches, login-start choice, and last popover tab. Assumption: absent notification preferences default enabled for the five specified transitions, and login-start defaults off. Implement confirms labels/defaults against design v2; explicit design values take precedence. Save only app settings, not runtime state. Preference failures produce a bounded typed problem and a diagnostic record rather than silently claiming persistence.

### 3. Own CLI resolution, polling, detail requests, and process cleanup

Serves AC-004, AC-006, AC-007, and AC-009. Implement executable resolution and a bounded runner in `infrastructure/cli`; expose typed results through application ports. Resolution tries an explicit override, process PATH plus login-shell PATH, `SKILL_BILL_BIN_DIR`, and `~/.local/bin` in that order. An invalid configured override is a settings problem rather than a silent fallback. Include Windows `.bat`/`.cmd` candidates and confine their required platform launch handling to infrastructure. Pass validated argument vectors, never user commands interpolated into a shell. Login-shell discovery has the same bounded lifetime/output rules as other subprocesses.

Create one application-owned polling coroutine, a coalesced manual-refresh signal, and a poll runner with one outstanding process slot. Its only board argv is `skill-bill work board --format json --finished-within <retention>`. Selection changes trigger `skill-bill work detail <id> --format json` on a separate one-slot detail runner. Identify work by workflow id or standalone invocation id and repository identity, not issue key. Tag requests with settings and selection generations; obsolete responses cannot replace current detail, board settings, or selection. Changing retention or executable invalidates the relevant generation and requests one refresh after the current slot is available.

Set an initial 10-second logical request deadline and a 4 MiB stdout decoding limit, with a small bounded stderr diagnostic buffer that never reaches user-facing failures. These are app-owned implementation defaults to confirm for realistic detail payload sizes. Drain both streams off the UI thread; after exceeding a limit, discard further bytes while continuing to drain until the child exits. Timeout returns a safe typed result to the caller without terminating the process. Keep the slot occupied and retain the drain/reap owner until natural exit. Coalesce or reject new requests while occupied, including after timeout, so repeated refreshes cannot create unbounded children or threads. Cancellation stops response delivery, not the child. Quit cancels scheduling and UI delivery; daemon cleanup owners must not block application exit or call process destruction. No `destroy`, `destroyForcibly`, OS kill command, or equivalent process-kill behavior is permitted.

Retain the last successful board on refresh failures, flag it stale, and freeze its clocks. Poll age remains based on that board's `observed_at`, never the failed attempt. An incompatible replacement is a dedicated problem and is not rendered; any retained last-success data is clearly stale. Missing timing remains unavailable. Record each timeout, limit breach, malformed response, and fallback/degradation through sanitized diagnostic categories without leaking child output or paths into failure text.

### 4. Implement safe goal controls

Serves AC-005, AC-007, and AC-009. Add application eligibility/actions and infrastructure mutation adapters. Permit only `skill-bill goal pause <KEY> --repo-root <root>` and `skill-bill goal stop <KEY> --repo-root <root>`, using typed issue/root arguments. Construct distinct runner instances for pause, stop, polling, and detail, each with its own single occupied slot and the lifecycle policy above. Dispatch off the UI thread and report bounded predefined failure categories such as unavailable, timeout, busy, or command failed. Do not expose stdout, stderr, exception messages, or paths.

Show controls only for a snapshot with active lifecycle, feature-goal family, and an issue key. Disable Pause when `pause_requested` is true; prevent duplicate in-flight actions. A completed command only schedules a coalesced refresh. It does not change lifecycle or establish that pausing/stopping took effect. Do not implement launching, resuming, retrying, abandoning, standalone stop, or process killing. Copy text remains separate from executable actions: `skill-bill goal status <KEY> --repo-root <root>` and, for blocked goals, `skill-bill <KEY> resume`. Quote copied root arguments for the supported platform shell so paths with spaces remain usable.

### 5. Select native integrations and implement tray/menu behavior

Serves AC-002 and AC-006. Put native tray, notification, login-start, and anchor adapters behind application ports in `infrastructure`, wired by `composition`; add icons under desktop resources. Keep `java.awt.SystemTray` excluded. Choose the current stable Compose plugin and a maintained native tray dependency during implement, record exact coordinates/versions in the desktop catalog or architecture notes, and qualify the backend against Linux StatusNotifierItem, icon anchoring on all three OSes, menu access, template/count support, badges, and click callbacks before committing to it. The digest supplies no coordinate or stable version; naming an unverified dependency here would invent evidence. The decision is capability-based selection during implement, with thin platform adapters where the chosen backend needs them, rather than weakening any native criterion.

Use macOS template icons plus count text and Windows/Linux overlay badges. Set deterministic icon priority: failed, needs you, running with count, idle. Failed applies when an authoritative item is failed; needs-you applies to the remaining attention condition. Poll problems use their own problem state and stale indication rather than inventing work lifecycle. Anchor the Compose popover from native icon geometry/callback data. Provide the grouped Goals/Phases right-click menu and Open Skill Bill, History, Refresh now, Settings, Quit. Item/menu selection opens the single main window. If a panel suppresses primary-click activation, the menu must still reach these actions. Qualify GNOME without AppIndicator and KDE behavior explicitly; do not count an invisible tray or missing menu as an acceptable fallback.

Implement native notification delivery/click routing and start-at-login using macOS login items, Windows current-user startup, and Linux XDG autostart. Preference changes must reflect whether the OS operation succeeded. Native failures have safe user summaries and recorded diagnostic categories. Native-host evidence is deferred to validate/operator verification; declared support alone is insufficient.

### 6. Project board state into the popover

Serves AC-002, AC-003, AC-006, and AC-009. Add pure presentation projections and Compose popover views under `presentation` and `ui`. Running counts authoritative active items, including active goals with planning modifiers. Needs you counts each item once when blocked, failed, or pause code equals `awaiting_operator_decision`. Done 24h counts returned completed items with completion evidence inside the last 24 hours relative to the board observation time. A 6h retention window limits that evidence; do not fetch a wider window. A 7d setting does not widen the Done count.

Goals contains feature-goals; Phases contains standalone execution scope and non-goal families, including feature-verify, without duplicate rows. Restore last tab, show tab counts and attention dots, and use stable item identities. Goal ordering is blocked, failed, active without planning modifier, planning, paused. Finished goals belong in history. Use deterministic identity tie-breaks within a category. Phase rows include live/relevant unfinished items and completions from the last hour; history retains the wider configured window.

Render the specified row fields, progress/planning wave, counts, pause lines, freshness, and age. Planning derives from the provided planning projection, not inferred workflow artifacts. A UI ticker adds a nonnegative elapsed delta to supplied accumulated durations only for eligible live, fresh clocks with anchors. Freeze settled/stale clocks at their last displayed trustworthy value and leave missing durations unavailable. Never write ticking values back into authoritative models. Visual spacing, labels, and field mapping follow design v2 sections 2 and table t3 during implement.

### 7. Build the main window, history, settings, and notification transitions

Serves AC-005, AC-006, and AC-009. Implement one main window with sidebar categories, repo/issue/branch filters, stable selection from popover/menu/notification clicks, and close-to-tray. Keep selected detail loading/error state separate from the board. Display lifecycle/issue/kind/freshness chips, manifest title, repo/path/branch, metrics, subtasks/dependency flags, current-subtask phase chips, goal-finish pr/monitor, latest activity, active agent/liveness, ledger and completed-validation extras. Hide optional execution, attempts-and-fixes, and validation panels when empty.

Place blocked pause reason first with copyable resume text and no Resume button. Render standalone `step_pipeline` from detail, including pr, monitor, plan, review, and validation pipelines provided by the CLI, with activity and no stop control. Do not manufacture pipelines in presentation. Add history All/Goals/Phases over configured retention, settings for every persisted choice, and idle, CLI-not-found, incompatible-contract, absent-database, and stale states.

Compare successful authoritative observations by stable work identity. First observation establishes a baseline; unchanged polls, errors, disappearance, stale data, and history hydration do not notify. Emit only blocked, failed, goal completed, pause took effect, and standalone phase finished transitions, gated by the corresponding current toggle. Pause-requested alone does not mean pause took effect. Use one notification per item/transition observation, retain bounded deduplication state over the retention window, and do not replay history on startup or retention changes. Clicks select the exact item, fetching detail by id even if it is outside the current board retention. Detail-not-found is a bounded unavailable-item state, not selection of another run with the same issue key.

### 8. Add focused regression obligations

Serves AC-001, AC-003 through AC-007, and AC-009. Add tests in desktop architecture, infrastructure, application, and presentation test packages. Each obligation names the wrong behavior it catches; use typed fixture ports, controlled clocks, and boundary outcomes. Keep existing five test obligations intact and cover them here without duplicating branches for different literals.

- Architecture guard tests reject every mandated forbidden dependency/token. Bug: a future refactor starts a process in presentation/application or reads runtime persistence directly.
- Resolution test makes the executable available only through login-shell PATH and proves it wins over later fallback directories. Bug: desktop launch reports CLI missing even though the user's login environment resolves it. Cover configured override precedence and Windows launcher candidates within the same focused resolution boundary where platform fixtures permit.
- Contract rejection tests exercise board and detail version mismatch, malformed required data, and absent-database versus empty-success results. Bug: a future envelope renders incorrect rows, or first-use absence appears as healthy idle. Do not duplicate runtime schema parity tests here.
- Runner/control tests interleave poll and mutation results, assert their distinct observable outcomes, exact pause/stop argv, eligibility, and sanitized bounded failures. Bug: a poll completion is consumed as a mutation result or child stderr leaks into the UI. Prove disabled pause cannot dispatch after `pause_requested` and standalone controls cannot dispatch.
- Timeout/output-lifetime test keeps a child pending past the logical deadline, floods bounded streams, and requests refresh repeatedly. Bug: timeout destroys the child, blocks the UI, or accumulates processes. Assert bounded outstanding executions, timeout outcome, continued draining, natural-exit slot release, and shutdown behavior without killing.
- Selection/settings-generation test returns item A detail after selecting item B and returns an old-retention board after changing settings. Bug: the window shows the wrong repository/run or replaces current settings with obsolete data. Assert current selected detail and board state rather than call order.
- Projection test uses a fixed observed time, a 6h or 7d retention fixture, recent completion evidence, and live/stale/missing-duration clocks. Bug: Done 24h counts older history, freshness timestamps become completion dates, or stale clocks keep increasing. Assert counts, categories, ordering, and displayed durations at the presentation boundary.
- Notification test covers the five allowed transitions and disabled toggles with first-observation and unchanged-poll suppression, plus click selection by identity. Bug: every poll notifies, turning a switch off has no effect, or clicking selects a different run with the same issue key.

Do not write tests for Compose layout glue, icon painting, installer bytes, or trivial composition. Native geometry, menu accessibility, template/badge rendering, native clicks, and login-start require host verification rather than mock-only proof. This plan phase executes no tests. Full repository checks belong only to validate; later phases follow their own command authority when repairing or verifying focused behavior.

### 9. Wire packaging and desktop CI

Serves AC-008 and AC-009. Add `.github/workflows/desktop-tray-ci.yml` with path filters for `desktop-tray/**` and its own workflow file, `ubuntu-latest`, Temurin JDK 21, and `(cd desktop-tray && ./gradlew clean check --no-build-cache)`. Keep runtime and plugin build graphs independent.

Configure Compose native distributions for `.dmg`, `.msi`, `.deb`, and `.rpm` on supported native hosts. Choose AppImage tooling explicitly: a separate Gradle packaging task stages the Linux app image and uses a pinned `appimagetool` release with desktop entry/icon metadata. Record its version and acquisition/verification policy in the desktop tree during implement. Provide equivalent operator tasks for host-specific packaging; a separate publishing/release workflow is optional and no store publishing is planned. Packaging defaults unsigned, with signing credentials only as optional operator inputs. Do not create install-refresh or uninstall work in this child.

### 10. Prepare the later acceptance evidence

Serves AC-001 through AC-009. Implement produces repository end states and test/packaging wiring. Audit inspects each criterion, architecture boundaries, design v2 mapping, test value, CI, and packaging tasks without running validation commands. Review and repair remain allowed to correct production wiring, tests, formatting, and lint while preserving behavior, assertions, explicit operator constraints, and architecture rules.

Validate owns the pack gate, the separate desktop check, behavioral test execution, and packaging/native verification commands. It must obtain real host evidence for Linux StatusNotifierItem and GNOME/KDE menu access, macOS/Windows/Linux anchoring, icon treatments, notification clicks, login-start, and close-to-tray, plus the five installer outputs or runnable host-specific task proof required by AC-008. Missing native hosts are an evidence limitation to report, never a fabricated pass. No compilation, build, tests, full checks, native launch, install synchronization, branch preparation, history writing, commit, PR, or workflow continuation occurs in this plan. Their owners retain responsibility; no acceptance criterion is proven here.

## Acceptance Criteria

1. `desktop-tray/` is a Compose Multiplatform Desktop Gradle build with JDK 21 toolchain, own settings, and no include from `../../runtime-kotlin/settings.gradle.kts`. Packages are domain, application, infrastructure, presentation, ui, composition under `dev.skillbill.tray` (or the confirmed `dev.skillbill.*` prefix). Architecture tests ban presentation→infrastructure imports, process/JSON/filesystem tokens in presentation, process APIs in domain/application, and SQLite/JDBC/`skillbill.ports.db` anywhere in the app.
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
