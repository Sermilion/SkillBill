# Skill Bill desktop app, Linear design: implementation handoff

This document and the prototype next to it are everything needed to plan and build a new Skill Bill desktop app. Nothing here depends on private links or on the machine where it was written. Paths are relative to the repository root.

- Prototype: `design/linear-study/index.html` (open it in a browser; see [README](README.md)).
- Current desktop app, the behavior reference: `desktop-tray/` (read `desktop-tray/ARCHITECTURE.md`).
- Runtime the app embeds: `runtime-kotlin/` (read `docs/architecture-guidelines.md` and `runtime-kotlin/ARCHITECTURE.md` before changing it).

Written 2026-10-27 against `main` at `1ef0c125b`.

Revised 2026-10-30 to match the Compose app in `desktop/`: collapsible detail sections, stacked and collapsible incident evidence, a folding run reason, spec bundle file order, ShortId for machine ids, the operator decision section without an incident, the refresh hint and stale indicator, editable review budget and compaction override rows, the purge dialog's safe default, menu focus and command menu hover, the main window's opening size, and corrections to the operations metrics, goal-wide pipeline steps and planning wave text. Later the same day: the goal's Subtasks list became a selectable index and the details pane became an inspector for the selected subtask (dependencies, validation, review passes and audit moved there from the document; the pane widens to 380 px), the details pane properties were redesigned (one-line values, short labels, breakdowns instead of joined strings), and the activity and History sections merged into one Activity feed. The inspector has no Status row, because its title row already shows the subtask's icon and state word. Goal assessment findings can be ordered by priority or status.

---

## 1. Outcome

A new desktop app that shows and controls Skill Bill work, built to this design, that feels instant: selecting a goal or spec paints in the same frame, the board follows the runtime within a fraction of a second, and an active goal does not make the app stutter.

The current app takes about 5 seconds to open a goal or spec. Section 5.2 explains why, with measurements, and section 5.4 lists the runtime changes that remove the cause.

## 2. Decisions already made

| Decision | Detail |
| --- | --- |
| New app | Built fresh, not a restyle of `desktop-tray`. `desktop-tray` stays the reference for behavior, edge cases and copy. |
| Feature scope | Only functionality Skill Bill has (section 4). Nothing invented. Everything the desktop app does today is included, plus the config screen and operator decisions. |
| Stack | Kotlin/JVM with Kotlin Flow for all observation. Compose Multiplatform Desktop is the natural toolkit (the current app uses it and its terminal, tray and notification integrations are proven), but the toolkit is not otherwise fixed by this document. |
| No CLI | The app never runs `skill-bill`, never parses CLI output and has no CLI fallback. |
| Embedded runtime | The app loads the runtime that `install.sh` installed in `.skill-bill/runtime/runtime-cli/lib` under the user's home directory into its own JVM and calls runtime services directly. There is one installed copy; the app ships no runtime jars. |
| No coordinator daemon | Considered and rejected (section 5.7). |
| Dark only | One theme, the tokens in section 6. |
| Config included | The config screen edits real runtime config keys. The runtime needs a write path for most of them (section 5.4, R5). |

## 3. How to read the prototype

The prototype is the visual and interaction spec. Its code is organized so each file answers one question:

| File | Answers |
| --- | --- |
| `ds/tokens.css` | Exact color, type, spacing, radius, shadow, motion and size values. |
| `ds/components.css` | Exact dimensions, paddings and states of every component. |
| `ds/components.js` | The component inventory and each component's inputs. One function per component. |
| `app/screens.js` | What each screen contains, in which order, and which component renders each part. |
| `app/main.js` | Every action, its preconditions, its feedback text, the keyboard map and the command menu. |
| `app/data.js` | The data each screen needs, shaped like the runtime and desktop models. Values are examples. |
| `app/library.js` | The tray popover, OS notifications, the component catalog and the source-of-truth table. |

In the browser: **Prototype** is the app, **Components** shows each component with live examples and the screens that use it, and **Foundations** shows tokens, the status vocabulary and where each feature comes from in this repository.

The prototype renders everything as HTML strings and keeps state in one object. That is a prototyping shortcut, not an architecture to copy.

---

## 4. Product scope

### 4.1 In the design

Each row is a feature the prototype shows, where it lives in the prototype, and the runtime entry point the new app calls. Service names are accessors on `skillbill.di.core.RuntimeComponent` (`runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt`); all listed accessors exist today.

| Feature | Prototype | Runtime entry point |
| --- | --- | --- |
| Work board: Needs you, Goal runs, Standalone phases, Operations, Finished today; text filter | Work view | `ideStatusService.board` / `projectBoardSelection`; ordering, counts and grouping from `IdeStatusPolicy` (see 5.3 for the faster read path) |
| Board item facts: lifecycle, freshness, stalled, pause requested, step, phase execution count, progress, planning progress, durations, model, effort, agent, branch, repository | Rows, detail header | `WorkBoardItem` / `IdeStatusSnapshot` (`runtime-ports/.../idestatus/model/WorkBoardModels.kt`) |
| Detail: subtasks, goal finish, phase pipeline, dependencies, latest activity, Execution, Runs and fixes, Validation, Phase results (review passes, findings, audit), Goal QA, Goal assessment, QA report, execution history, spec bundle files | Detail view | `ideStatusService.detail(WorkDetailRequest)`; lists page by anchor (`WorkDetailList`) |
| Run options (Resume / Run again / Start label, agent choices, cancel allowed) | Detail run actions | `workControlService.options(WorkDetailItem)` |
| Pause after subtask, Pause (stop now) | Detail, context menu, keys P and Shift+P | `workControlService.request(WorkControlTarget, PAUSE or STOP, storeIsCurrent)` |
| Cancel, Resume / Run again with agent | Detail, context menu, key R | `workControlService.request(WorkRunTarget, CANCEL or LAUNCH, agentId)` |
| Incident: diagnosis, cause, next action, prerequisites, evidence, report status | Detail incident section | `WorkDetailItem.incident`, `incidentHistory` |
| Recover with prerequisites, preview, Apply and resume | Detail incident, dialog | `workControlService.recoveryPreview(...)`, `workControlService.recover(...)`; prerequisite kinds: `credentials_configured`, `configuration_fixed`, `project_edit_completed`, `external_service_available` |
| Operator decision for a paused subtask: retry fix (instructions required), accept and advance, abandon subtask | Detail incident on a goal waiting for a decision | `goalOperatorDecisionService.record(GoalRunnerOperatorDecisionRequest)`; not in `desktop-tray` today |
| History: All, Goals, Phases, Operations; filter; retention window | History view | Board read with `finishedWithin` (`WorkBoardWindow`: 6h, 24h, 7d) |
| Specs pane: project picker, filter, status filter (All, Active, Pending, Done), tree, collapse all, reveal folder, open file | Specs pane | `specBrowserService.projects()`, `projectFor(repoRoot)`, `snapshot(projectId, preferredRepoRoot)` |
| Spec run toolbar: Start / Resume / Run again with agent, Pause, Cancel with inline confirmation, Purge with dialog | Specs pane toolbar | `specBrowserService.control(...)` for enablement and labels; `workControlService.startGoal(repoRoot, issueKey, agentId)`; pause and cancel as above; `goalRunnerStatusService.purge(GoalRunnerPurgeRequest)` |
| Terminal pane: tabs named per project, new, rename, close, resize; opens in the selected work's repository | Terminal pane | App-owned (pty4j + JediTerm, section 9.5); no runtime call |
| Settings: database path, Claude config directory, history retention, six notification types, start at login | Settings, App group | App preferences; Claude directories from `installAgentService.claudeRoots(...)` |
| Config, Models: tiers, phase tiers, per-agent phase overrides; junie takes no model | Settings, Runtime config | Read: `configResolutionService.resolveExecutionMatrix()`. Write: new (R5) |
| Config, Compaction: enabled, window tokens, trigger percent, per-phase overrides | Settings, Runtime config | Read: `configResolutionService.resolveCompactionSettings()`. Write: new (R5) |
| Config, Project: spec source, Gradle wrapper, review context budget, phase instruction files | Settings, Runtime config | Read: `RepoLocalConfigPort` via the runtime graph, `configResolutionService.resolveSpecType`; phase files read from `<repo>/.skill-bill/phases/`. Write: new (R5) |
| Config, Telemetry and agents: level, proxy URL, batch size; detected agents | Settings, Runtime config | `telemetryService` / `TelemetryConfigStore.writeTelemetryLevel` exist; proxy and batch writes new (R5); agents from `installAgentService.detectAgentTargets(...)` |
| Tray popover: Running, Needs you, Done 24h; Goals, Phases, Operations tabs; Open, Refresh, Settings, Quit | Components page, Surfaces | Same board read; `IdeStatusPolicy.arrange` counts and tray status |
| OS notifications for six lifecycle transitions | Components page, Surfaces | App-owned, driven by board transitions (desktop `TransitionNotifications.kt`) |

### 4.2 Runtime capabilities not designed yet

These exist in the runtime and are reachable through `RuntimeComponent`, but the prototype has no screen for them. Decide per item whether the new app needs them; each needs design before implementation.

| Capability | Runtime entry point |
| --- | --- |
| Goal preflight before start: verdict, confirmation gate, missing spec targets | `goalPreflightService` |
| Goal-wide unaddressed findings ledger | `unaddressedFindingsLedgerService` |
| Goal planning attempt log | `goalPlanningLogService` |
| Resume without starting child runs (clears pause and stale wedges) | `goalRunnerStatusService.resume` |
| Repair, reset, replan, accept-after-hard-reset | `goalRunnerStatusService.repair / reset / replan / accept` |
| Run a standalone phase (review, validation, qa, plan, pr, monitor) | Phase launch path used by the CLI's `PhaseCommand`; would need a service seam for an embedding app |
| Run an operation, including the confirmation round trip (`awaiting_confirmation` with a token) | `operationExecutor` |

### 4.3 Out of scope

Skill authoring and scaffolding, platform pack and add-on management, installation and agent linking, review import, triage and stats, learnings, telemetry stats and sync, update, uninstall and doctor. These are developer or installer tools, not work monitoring.

### 4.4 Explicitly not in the product

The first design pass invented these; they were removed and must not come back without a runtime feature behind them: an inbox with read/unread state, structured filter chips and display options, row multi-select and bulk actions, copy-command boxes, undo after an action, a light theme, model typo detection and model suggestion lists, a workspace switcher menu, a per-repository sidebar list.

---

## 5. Architecture

### 5.1 Process model and runtime embedding

One JVM. At startup:

1. Resolve the runtime install root: `SKILL_BILL_RUNTIME_DIR` if set, else `.skill-bill/runtime` under the user's home directory (desktop: `RuntimeInstallRoot.kt`).
2. Read `runtime-cli/build-identity.properties` (keys: `commit`, `dirty`, `release_tag`, `install_run_id`, `read_schema`, `incident_detail_version`, `min_incident_detail_read_schema`). Refuse to start when the runtime is missing, unreadable, or not the build the app was built for, and show the "runtime unavailable" state with the reinstall command (desktop: `InstalledRuntimeBuildGate.kt`, `RuntimeBuildProblem`).
3. Load `runtime-cli/lib/*.jar` through an application class loader (desktop: `DesktopBootstrap.kt`). The packaged app contains no runtime jar; keep a build check equivalent to desktop's `verifyPackagedRuntimeFree`.
4. Per selected database, build one `RuntimeComponent` and keep it for the session. Building it costs about 2 seconds of cold JVM time (section 5.2), so never build it per read.

```kotlin
val runtime: RuntimeComponent = RuntimeComponent::class.create(
  RuntimeContext(
    EnvironmentContext(
      databasePath,                       // null or blank uses the runtime's default path precedence
      environment = hostEnvironment,
      userHome = Path.of(userHome),
      repositoryRoot = Path.of(workingDirectory),
    ),
    TransportContext(),
    WorkflowOpsContext(),
    OptionalCallbacks(hostAgentHomesPort = /* Claude config directory chosen in settings */),
  ),
)
```

This is how `desktop-tray/core/data/src/jvmMain/.../runtime/RuntimeSessionProviders.kt` does it. The default database is `.skill-bill/review-metrics.db` under the user's home directory (`DatabasePaths.defaultDbPath`), overridden by the database path setting or `SKILL_BILL_DB`.

The app and the runtime are a matched pair. `install.sh` installs both; an upgrade of one without the other makes the app show the runtime-unavailable state.

### 5.2 Why the current desktop app is slow

Measured 2026-10-27 on a working machine (database 236 MB, WAL 106 MB, 64 tables, 113 indexes), read-only:

| Measurement | Result |
| --- | --- |
| `sqlite3` open plus a trivial query, including process start | 30–70 ms |
| Any runtime read through a fresh JVM (`work list`, `work board`, `work detail`) | 3.0–3.8 s, against 1.2 s for a JVM that only prints its version. About 2 s goes to building the runtime graph and its first read, independent of the query. |
| `worktree_edit_journal` | 228,932 rows across 194 workflows. Everything else is under 1,500 rows. |
| Worktree probe write rate during active runs | Up to 3,516 rows and 214 probe batches per hour |
| Journal WAL | 106 MB beside a 236 MB database: checkpoints are not keeping up |

From the code:

- **Every runtime read opens a new SQLite connection.** `SQLiteDatabaseSessionFactory.read` and `readIfPresent` call `DatabaseRuntime.openExistingReadDbAt` per call. Each open runs connection PRAGMAs, reads the migration ledger, checks the schema version and the desktop read identity, and takes file observations before and after. Each new connection also starts with a cold page cache, so every query rereads pages.
- **Opening a goal costs at least two of those reads.** The detail load runs `ideStatusService.detail` and then `workControlService.options`, each in its own read (`RuntimeWorkAdapter.kt`).
- **Change signals are coarse.** The runtime has two board topics, `goals` and `phases`, plus `activity`. Row triggers on 21 tables bump board topics on every insert, update and delete (`DesktopChangeRevisions.kt`). The high-frequency ones bump both board topics: `worktree_edit_journal`, `agent_activity_stamps` (which also bumps `activity`), `feature_task_runtime_worker_leases`, `ide_status_workflow_execution`, `feature_task_workflows` and `feature_task_phase_settlements`. A worktree probe or a lease heartbeat therefore invalidates every board section and every open detail.
- **Every owner re-reads.** The main window, the tray and the notification coordinator each keep their own board subscription, so one signal fans out to several projections, each paying the per-read cost.

The likely result: while a goal runs, a signal arrives every few seconds, each one triggers several full projections on fresh connections, and a selection's read competes with that churn or is restarted by it. Confirm this before building on it (first step of section 12): record the current app with Java Flight Recorder while a goal runs and open a goal.

```sh
# Example: add to the desktop app's JVM arguments, reproduce, then open the .jfr in JDK Mission Control
-XX:StartFlightRecording=filename=skillbill-desktop.jfr,settings=profile,dumponexit=true
```

### 5.3 Data access design for speed

Rules for the new app:

1. **One runtime graph and one long-lived read path per database session.** Reads reuse an open read-only connection (or a small pool) with a warm page cache. Schema compatibility is verified once at open and again only when the signal's `generation` changes or the database file identity changes (needs runtime change R1).
2. **Reads never hold a transaction open.** A read transaction is short and closes before the result is emitted, so writers can checkpoint the WAL.
3. **Observation is signal-driven, never polled.** The runtime writes `<database>.skill-bill-commit-signal` atomically after each commit (`DesktopCommitSignalPublisher.kt`, contract `DesktopCommitSignal` and `DesktopReadSchema` in `runtime-contracts`). Format, one `key=value` per line: `generation=<uuid>`, then one line per topic with its revision (`goals=…`, `phases=…`, `activity=…`). Watch the file's directory with `WatchService`, filter by file name, parse, and publish a `StateFlow<SignalSnapshot>`. A new `generation` means a full resynchronization. A watcher overflow means reread the file. Do not rely on Room or SQLDelight invalidation: it only sees writes made by its own process, and Skill Bill's writers are other processes.
4. **Each query observes only its topics.** Map the signal to the topic revision the query depends on, `distinctUntilChanged`, then re-query. With runtime change R2, queries also receive the changed item ids and re-read only those rows.
5. **One shared observation per query, not one per owner.** The main window, the tray and the notifications share the same board flow (`stateIn` / `shareIn` in the session scope, keyed by query parameters).
6. **Selection paints from memory.** The clicked row already holds the header facts (title, lifecycle, step, progress, durations, agent, model, branch). Render them in the same frame, then load detail lists in the background. Prefetch the detail of the focused row while the user moves with J and K.
7. **Detail lists load lazily and page by anchor.** Load the visible sections first; load subtasks, validation, phase results and execution history as their sections enter the viewport. Use the runtime's per-list cursors and anchors (`WorkDetailRequest.list`, `anchor`).
8. **Policy stays in the runtime.** The app never sorts work, judges lifecycle or freshness, or weighs a time window. It renders runtime verdicts (`displayGroup`, `displayPriority`, `requiresAttention`, `controlEligible`, `pauseEligible`, `running`, `doneWithinDay`, `membershipDeadline`). This is a rule in `desktop-tray` (`DesktopArchitectureTest`) and should carry over.
9. **Time-windowed facts re-read once at their deadline.** Schedule a single re-query at `membershipDeadline` or `freshnessDeadline` per owner; no repeating timers in data code.
10. **Clocks tick in the UI only.** Elapsed time is `activeDurationMs + (now − activeDurationAsOf)`, recomputed by one UI-level ticker; it never reads the database.
11. **Database work runs on a dedicated dispatcher** (for example `Dispatchers.IO.limitedParallelism(2)`) and never on the UI thread.
12. **Filtering runs in SQL before the limit,** as today, on indexed columns.

A sketch of one observed query:

```kotlin
fun observeBoard(scope: CoroutineScope): StateFlow<BoardState> =
  signals                                      // StateFlow<SignalSnapshot> from the file watcher
    .map { it.revisionOf(Topic.Board) }        // only the topics this query depends on
    .distinctUntilChanged()
    .conflate()                                // a burst of commits becomes one re-query
    .mapLatest { withContext(dbDispatcher) { reads.board(window, pageSize) } }
    .distinctUntilChanged()                    // equal results do not recompose
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), BoardState.Loading)
```

For a detail screen, do not let activity-topic churn restart the first load: paint from the row, load once, and apply later changes as incremental refreshes.

### 5.4 Runtime changes required

The new app needs these changes in `runtime-kotlin`. Each follows `docs/architecture-guidelines.md`, `runtime-kotlin/ARCHITECTURE.md` and `docs/code-principles.md`. Wire keys go in owning `*Keys` objects. A new migration bumps `DesktopReadSchema.VERSION`.

| Id | Change | Why |
| --- | --- | --- |
| R1 | A session-scoped read API: a read session that keeps one read-only connection open, verifies compatibility once, and runs many short read transactions; reopened on generation or file-identity change. | Removes the per-read open and verification cost and keeps the page cache warm. |
| R2 | Finer change signals: separate topics for board structure (lifecycle, step, progress, membership), activity (agent activity stamps, worktree edits, heartbeats) and specs; the signal also carries a bounded list of changed work ids per topic, with an overflow marker that means "re-read all". Worktree probes and lease heartbeats bump only the activity topic, and only when a displayed value changes. | Stops activity writes from invalidating the whole board and every detail. |
| R3 | A board read model: one table with a row per work item holding every fact the board and the detail header show (the `WorkBoardItem` fields plus display group, priority and deadlines), written in the same transaction as the source change. The board becomes one indexed `SELECT` ordered by priority. | Replaces multi-table projection on every read with a single cheap query. Time-dependent facts stay correct through stored deadlines. |
| R4 | WAL and journal hygiene: checkpoint (`wal_checkpoint(TRUNCATE)`) after goal completion and when idle; bound `worktree_edit_journal` to what the UI and goal status read (the latest probe per workflow, or a fixed history). | The 106 MB WAL and the 229k-row journal slow every read. |
| R5 | A config write service: read and write `execution_matrix` (agent tiers, `phase_tiers`, agent phase overrides), `compaction` (enabled, window, trigger percent, per-phase), `telemetry.proxy_url` and `telemetry.batch_size` in the user config. Path precedence is `SKILL_BILL_CONFIG_PATH`, then `.config/skill-bill/config.json`, then `.skill-bill/config.json`, with both paths relative to the user's home directory. Also write `spec_type`, `validation_gate.gradle_wrapper` and `review_context_budget` in the project store `config.yaml`. Validate with the existing parsers (`parseExecutionMatrix`, `parseCompactionSettings`, `parseValidationGateRepoConfig`, `ReviewContextBudgetPolicy`), keep unknown keys, write atomically, and refuse to overwrite a file that changed since it was read. Expose it through `RuntimeComponent`. | Today only telemetry level has a write path (`TelemetryConfigStore`). |

R1 to R4 are what make the app fast; R5 is what makes config editable. R2 and R3 are the largest. If they are deferred, R1 plus the app-side rules in 5.3 still remove most of the per-selection cost, but activity churn remains.

### 5.5 Writes and controls

- Controls call runtime services on the database dispatcher and never block the UI.
- Pause and Stop pass a `storeIsCurrent` check so a request against a replaced database is refused.
- Results are distinct and the UI says exactly what happened, never more (desktop `ControlConfirmation`): intent recorded, request accepted, exit confirmed, termination attempted, or a typed refusal (`WorkControlRejection`: unknown target, changed target, ineligible target, not found, ownership mismatch, foreign host, and others). Section 11 has the copy.
- A control does not optimistically change the row. The row changes when the commit signal brings the new state; until then the control shows its in-flight label (for example "Working…", "Pause requested").
- Launch requests (Resume, Run again, Start) are reserved app-wide per work identity, so the detail and the Specs pane disable together until the launch registers or fails (desktop `LaunchRequests`).

### 5.6 App structure

Carry over what works in `desktop-tray`:

- One screen per feature module with a typed `UiState`, `Action` and `Effect` contract, its view model and its UI. Features depend only on core modules, never on each other or on the data layer's internals.
- A data layer that owns the runtime graph, the signal watcher, the read session and the repositories. Repositories expose `Flow`s and suspend functions; nothing above them sees JDBC or runtime types.
- Lifetimes with named owners: process, database session (switching databases rebuilds the runtime graph and every observation), window, screen entry.
- A design-system module holding tokens and components (sections 6 and 7); screens add no styling of their own.
- Architecture tests for module edges and for "no policy in the app".

Suggested modules: `core:designsystem`, `core:ui`, `core:domain`, `core:data` (runtime bridge, signal watcher, read session, repositories), `core:settings`, `feature:work`, `feature:detail`, `feature:history`, `feature:specs`, `feature:terminal`, `feature:settings`, `feature:config`, `feature:tray`, and the `app` shell (windows, navigation, command menu, keyboard, notifications).

### 5.7 Coordinator daemon: considered and rejected

A local service that alone opens the database, with the CLI, goal runners and the app as socket clients, would give a single writer and real push. It is rejected because:

- Reads would not get faster: an in-process read is far below a millisecond once the connection is warm; IPC adds a hop.
- The change notification it would provide already exists (the commit signal); R2 makes it precise.
- Goal runners are long-running processes that write leases, heartbeats and phase records; all of that would become IPC. The runtime's persistence layer would move into the daemon, and every goal would stop when the daemon is down.
- It adds auto-start, upgrades, crash recovery and a versioned protocol.

The seam that keeps it possible later: the app reaches data only through repository interfaces in `core:data`. A future coordinator would replace their implementation without touching screens.

---

## 6. Design system

Linear's structure with Skill Bill's colors: a darker app frame, one inset content panel with a 12 px radius, dense 13 px interface type, hairline borders, and a single amber accent used for focus, the current step and the one primary action.

### 6.1 Color tokens (dark only)

| Token | Value | Use |
| --- | --- | --- |
| `bg-frame` | `#0B0B0D` | Window frame, sidebar |
| `bg-panel` | `#111114` | Content panel, panes |
| `bg-raised` | `#17171B` | Group headers, buttons, card headers |
| `bg-overlay` | `#1C1C21` | Menus, dialogs, toasts, tray |
| `bg-inset` | `#08080A` | Inputs, terminal |
| `bg-hover` | `rgba(255,255,255,0.035)` | Row and control hover |
| `bg-active` | `rgba(255,255,255,0.065)` | Focused row, active nav, selected tab |
| `bg-selected` | `rgba(233,168,28,0.08)` | Current subtask |
| `scrim` | `rgba(4,4,6,0.6)` | Behind dialogs and the command menu |
| `border-subtle` | `rgba(255,255,255,0.055)` | Row dividers, panel edges |
| `border` | `#26262C` | Controls, boxes |
| `border-strong` | `#383840` | Menus, dialogs, hover borders |
| `fg` | `#EDEDF0` | Primary text |
| `fg-2` | `#C4C4CC` | Secondary text |
| `fg-muted` | `#9C9CA6` | Labels, icons |
| `fg-faint` | `#6A6A74` | Ids, captions, timestamps |
| `accent` | `#E9A81C` | Primary button, focus bar, current step |
| `accent-hover` | `#F3C462` | Primary hover |
| `accent-fg` | `#14100A` | Text on accent |
| `accent-text` | `#F3C462` | Accent-colored text |
| `accent-tint` | `rgba(233,168,28,0.12)` | Current-step fill, attention badge |
| `accent-ring` | `rgba(233,168,28,0.5)` | Focus ring |
| `st-active` | `#E9A81C` | Active |
| `st-planning` | `#6FA8DC` | Planning |
| `st-blocked` | `#E2703C` | Blocked, stalled, needs you |
| `st-failed` | `#F0555B` | Failed, danger |
| `st-done` | `#3FB950` | Completed, passed, fixed |
| `st-paused` | `#9C9CA6` | Paused |
| `st-pending` | `#6A6A74` | Pending, canceled |
| `data-1`…`data-6` | `#E9A81C`, `#6FA8DC`, `#3FB950`, `#E2703C`, `#B48EAD`, `#9C9CA6` | Repository dots and agent avatars only |

The current desktop palette (`desktop-tray/core/designsystem/.../Palette.kt`) uses the same hues.

Shadows: popover `0 0 0 0.5px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.5), 0 2px 6px rgba(0,0,0,.3)`; modal `0 0 0 0.5px rgba(0,0,0,.5), 0 24px 64px rgba(0,0,0,.6)`. Panels are flat.

### 6.2 Type

Inter for interface text, with OpenType features `cv11`, `ss01`, `ss03` (in Compose: `TextStyle(fontFeatureSettings = "cv11, ss01, ss03")`). JetBrains Mono for ids, phase names, shas, paths, model names and config keys. Both fonts already ship in `desktop-tray/core/designsystem/src/commonMain/composeResources/font/`. Space Grotesk is no longer used.

| Token | Size / weight | Use |
| --- | --- | --- |
| `t-2xl` | 24 / 600, tracking −0.012em | Detail title |
| `t-xl` | 20 / 600 | Settings page title |
| `t-lg` | 16 / 400–600 | Command menu input, pane titles, metric values |
| `t-md` | 14 / 500 | Dialog title, empty-state title |
| `t-base` | 13 / 400–500 | Body, rows, navigation |
| `t-sm` | 12 / 400–500 | Buttons, meta, properties |
| `t-xs` | 11.5 / 500 | Labels, group captions |
| `t-2xs` | 10.5 mono / 500 | Phase steps, shas, kbd |

Digits that line up use tabular figures.

### 6.3 Space, radius, size, motion

- Space: 4 px grid (4, 8, 12, 16, 20, 24, 32, 40).
- Radius: 4 (kbd, steps), 6 (buttons, inputs, rows, tabs), 8 (menus, boxes, toasts), 12 (panel, dialogs, tray), full (labels, avatars).
- Sizes: row 36, compact row 32, header 44, sidebar 236, details pane 272 (`--props-w`), or 380 while it holds the subtask inspector (`--props-w-wide`), property row 28 with an 80 px label column (`--prop-label-w`), breakdown row 22, activity row 28, Specs pane 400, terminal default 200 (min 120, max 600 in the prototype; desktop allows 120–1200).
- Motion: 90 ms for hover, 160 ms for menus and toggles, easing `cubic-bezier(0.25,0.46,0.45,0.94)`. Respect reduced motion.

### 6.4 Status vocabulary

Shape carries the state and color reinforces it, so a list reads in grayscale. All icons draw in a 14 px square (16 px large variant); circle center (7,7).

| State | Shape | Color |
| --- | --- | --- |
| Active | Ring r=5.5 stroke 1.5, plus a pie: circle r=1.75 stroke 3.5 with dash `f*11, 11` from 12 o'clock, `f` = progress clamped to 0.12–1 | `st-active` |
| Planning | Dashed ring r=5.5 stroke 1.5, dash 2.4/1.9 | `st-planning` |
| Stalled | Dotted ring r=5.5 stroke 1.5, dash 0.6/2.6, round caps | `st-pending` |
| Paused | Ring plus two bars 1.4×4.8 | `st-paused` |
| Blocked | Filled r=6.25 with an exclamation in `bg-panel` | `st-blocked` |
| Failed | Filled r=6.25 with an × in `bg-panel` | `st-failed` |
| Completed | Filled r=6.25 with a check in `bg-panel` | `st-done` |
| Completed, no change | Ring with a check | `st-done` |
| Pending | Ring | `st-pending` |
| Skipped | Dashed ring with a slash | `st-pending` |
| Canceled | Ring r=5.5 stroke 1.5 with an × in `st-pending`, lines (4.9,4.9)–(9.1,9.1) and (9.1,4.9)–(4.9,9.1), stroke 1.5 with round caps | `st-pending` |

Mapping from runtime lifecycle: `ACTIVE` → Active (Planning while `planningState` is not prepared, Stalled when `stalledSince` is set), `PAUSED` → Paused, `BLOCKED` → Blocked, `FAILED` → Failed, `TERMINAL` → Completed, `CANCELED` → Canceled. Subtask statuses: `complete`, `completed_no_change`, `in_progress`, `pending`, `blocked`, `skipped`.

AC-004 amendment: Canceled uses the outlined shape above, replacing the earlier filled circle. Match the updated prototype for Canceled and preserve shape-only distinction in grayscale for IS-002. Failed remains filled.

### 6.5 Icons

Lucide (ISC license) line icons at 16 px with a 1.75 stroke, inheriting the text color. The prototype's set is in `ds/components.js` (`P` map): target, history, fileText, file, folder, terminal, sliders, search, pause, stop, play, rerun, refresh, copy, branch, commit, x, check, chevrons, arrowLeft, external, warn, alert, info, checkCircle, bot, cpu, gauge, repo, shield, user, bell, keyboard, panelLeft, panelRight, layers, zap, diff, pencil, plus.

---

## 7. Components

Every screen is built from these. Dimensions are in `ds/components.css`; inputs are in `ds/components.js`.

| Component | Purpose and key specs |
| --- | --- |
| Icon | 16 px line icon; sizes 12, 14, 16, 20. |
| BrandMark | The SB monogram, 16–32 px. |
| StatusIcon | Section 6.4. |
| Kbd | 17 px key cap, shown in tooltips, menus, buttons, command menu. |
| Button | 28 px (24 small). Variants: secondary, primary (accent), ghost, danger (red text), danger-solid. Optional icon and trailing kbd. Disabled at 45% opacity. |
| IconButton | 28 px square (24 small); always has a label for tooltip and accessibility. |
| Tooltip | Appears after 450 ms; text plus shortcut. |
| Segmented | 22 px options in a bordered track; for exclusive choices that apply at once. |
| ViewTabs | 24 px tabs with count and an attention dot. |
| Toggle | 28×16 switch. |
| Checkbox | 14 px; prerequisite confirmation. |
| Input, Field | 30 px input with optional leading icon; mono variant; error state. Field adds label and help. |
| IdTag | Muted issue key, phase or operation id, always shown in full. |
| ShortId | Machine ids: workflow ids, invocation ids, commit shas, evidence references. Shows the first 8 characters plus "…" (the whole id when that saves nothing) in faint 10.5 px mono, the full value in a tooltip, then a small copy button labelled "Copy `<what>`" ("Copy workflow id", "Copy invocation id", "Copy commit", "Copy reference"). Evidence references drop their leading `<kind>:` prefix before shortening, since the kind shows below. Used in the details pane (Workflow, Invocation, the inspector's Commit), subtask commits in the Subtasks index, evidence rows and the recovery dialog's Workflow. Activity entries keep the invocation id in their tooltip only. |
| StaleIndicator | "Stale" in 12 px `fg-muted` with the tooltip "Showing stale work data"; in the view header after a refresh failed. |
| Label | 22 px pill: colored dot, tinted (lifecycle), mono (runtime ids). |
| Avatar | 18 px agent initials: Cl, Cx, Cu, Ju. |
| RepoIcon | 16 px rounded square with the repository initial. |
| ProgressRing | 14 px ring with `n/m`; green when complete; blue for plans saved. |
| Clock | Live duration (section 5.3, rule 10). |
| MetricStrip | Equal cells: label, value, note. |
| PropertyList | Details pane properties. 28 px rows. Label column 80 px (`--prop-label-w`), sized to fit the longest label in the pane, 12 px `fg-muted`, one line with an ellipsis as the fallback; phase-name labels (Goal finish steps) in 11.5 px mono. 12 px gap, then the value cell: 24 px high, 6 px side padding pulled 6 px left so text aligns, one line, 12 px sans `fg-2`, tabular digits, never wraps (ellipsis). Mono only inside ShortId. An actionable value highlights `bg-hover` and shows a tooltip. Rows with a null, empty or zero value are left out; nothing draws "0" or a blank. An optional Breakdown sits under the value in the value column, 4 px bottom padding. |
| Breakdown | A total split by key under a property (Runs, Fixes, Relaunches). 22 px rows: key in 11.5 px mono `fg-faint` on the left (ellipsis), count right-aligned in 12 px `fg-muted` tabular, sorted largest first, zero counts dropped. At most 4 rows, then a ghost toggle "+N more" (11.5 px `fg-faint`, 22 px, `bg-hover` on hover) that reads "Show less" when open. Rows rather than chips: chips wrap at 272 px, rows never do. |
| PhasePipeline | 20 px mono steps joined by 6 px lines; done (check), current (accent), waiting (orange), failed (red), goal-wide (solid 1 px `border` outline). |
| ReasonBlock | Durable reason, mono, left rule in the lifecycle color, Copy button. The text is an ExpandableText (12 px mono `fg-2`, line height 1.55). |
| ExpandableText | Long runtime text folded to 3 lines with an ellipsis: block reasons (the subtask inspector's warn Hint, inline too), finding text and meta (FindingRow, compact titles), evidence summaries, the incident's Cause and Next lines, the audit summary, ReasonBlock (run reason, QA report). A small ghost "Show more" button (chevronDown) sits 2 px under the text only when it overflows; open it reads "Show less" (chevronUp). Short text adds no toggle and no gap. The open state resets when the text changes. Desktop `ExpandableText.kt`, one implementation that ReasonBlock uses. |
| FindingRow | Severity (uppercase mono, colored), ref, text, location, state label. Not used for evidence. Text and meta fold past 3 lines (ExpandableText). Compact variant in the subtask inspector: no divider, 5 px vertical padding, 8 px gap, 52 px severity column, text 12 px `fg-2` (wraps, folds past 3 lines), location below in 11.5 px mono `fg-faint` on one line, shortened to "…/" plus the last two path segments past 28 characters, the full path in a tooltip. |
| EvidenceToggle | The incident's "Evidence · N" caption as a button: 11.5 px faint text and a 12 px chevron (right when collapsed, down when open), `fg-muted` on hover. |
| EvidenceRow | One evidence record, stacked: the reference as a ShortId on its own line, the summary below at full width (12 px `fg-2`, line height 1.45), then the kind (11.5 px faint). Rows sit in a Box with dividers, padding 9 px 12 px. |
| Checklist | Checkbox rows for prerequisites. |
| Menu, MenuItem, Popover | 30 px items with icon or lead, check, shortcut; heads and separators; click-outside and Esc close. |
| CommandPalette, CommandList | 640 px modal at 14% from top; context label, input, grouped results, footer hints. |
| Dialog, Ledger | 460 px modal; Ledger is the deleted/kept two-column list in Purge. |
| ShortcutGrid | The keyboard sheet. |
| Toast, ToastStack | 340 px, bottom right, five seconds; also the OS notification look. |
| Callout, Hint | Callout for incident state and inline confirmation; Hint for one line of guidance or validation. |
| EmptyState | Icon, title, body, actions. |
| Sidebar, SidebarItem, SidebarSection, WorkspaceSwitcher | Navigation (section 8). |
| ViewHeader, Pager | 44 px header: breadcrumbs, middle slot (filter or tabs), actions; Pager for previous and next. |
| GroupHeader, ListRow | Sticky 36 px group header; 36 px row (32 compact) with id, status, title, summary, meta, time; focus bar on the left. |
| SubtaskList | The Subtasks index: a Box (hairline border, 8 px radius) that takes keyboard focus, role listbox. Focus ring only for keyboard focus (2 px `accent-ring`). |
| SubtaskRow | One index line, min 36 px, 12 px side padding, 10 px gaps: number (20 px), StatusIcon, title (13 px/500, ellipsis; pending rows 400 `fg-muted`), commit ShortId, live subtask clock, "no change" label. The whole line selects it. Selected: `bg-active` plus a 2 px `accent` bar on the left edge, full row height. Current (the running subtask) keeps the `bg-selected` tint while not selected. Hover `bg-hover` when not selected. Body (the inline inspector, narrow windows only) under the line: padding 4 px 16 px 16 px 62 px. Rows no longer show the block reason; the inspector does. The Goal finish row keeps its pr, monitor and monitor_fix chips (PhasePipeline, goal-wide steps) in its body, and the inspector lists the same steps. |
| SubtaskChip | A dependency edge: 20 px button, 1 px `border`, 4 px radius, 6 px padding, 4 px gap; a 14 px StatusIcon of that subtask, then its number in 11.5 px mono. Optional edges have a dashed border and the word "optional" in 11.5 px `fg-faint`. Hover `bg-hover` and `border-strong`. Click selects that subtask. Tooltip "Subtask 02: `<title>`" (plus "(optional)"). |
| SubtaskInspector | The selected subtask, section 9.2. 10 px between blocks: title 13 px/500 `fg`, line height 1.4, wraps; block reason as a warn Hint folded past 3 lines (ExpandableText); PropertyList with Commit, Spec file, Depends on, Needed by, Validation (no Status row, since the title row shows the state); then Review (6 px above): a 24 px "Review" caption (11.5 px/500 `fg-faint`), one block per pass with a 22 px line "Pass N · verdict · k unresolved" (Pass N in 12 px/500 `fg-2`, the rest `fg-muted`, tabular) and its compact FindingRows, 6 px between passes; then "Audit · verdict" and its summary in 12 px `fg-muted`, wrapping. "No review yet" in 12 px `fg-faint` when there are no passes. Inline (narrow windows) it drops the title, which the row already shows. |
| ActivityFeed | The detail's Activity. One 28 px line per entry, oldest first, the latest at the bottom: a 16 px icon (agent Avatar small, or a StatusIcon) on a 1 px `border-subtle` connector that runs through the icon column (from the first icon's center to the last's; the icon sits on a `bg-panel` disc), 10 px gap, then "actor action object" in 13 px: actor and object `fg-2`/500, the verb `fg-muted`/400, paths and runtime codes in 12 px mono `fg-2`. Then "· 4m ago" in 12 px `fg-faint`, kept visible while the text truncates with an ellipsis; the tooltip holds the full text. Past 8 entries the earlier ones fold behind a ghost toggle on top: chevronUp and "Show N earlier", 24 px, 12 px `fg-faint`. |
| TreeRow | Tree row with 18 px indent per level. In a PaneSection (Spec files) it is 28 px, pulled 8 px out on both sides with a 6 px radius, no caret column. |
| PaneSection | A details pane group. Title row 24 px, 11.5 px/500 `fg-faint`, 6 px gap, 2 px above the body. With a status it leads with that 14 px StatusIcon and ends with the state's word in the state color, 11.5 px/500 ("Blocked", "Done", "In progress", "Pending", "No change"). Sections stack 24 px apart with no dividers. |
| DocHeader, Section, Box | Detail building blocks. A detail Section header can collapse its body: the title, count and a 12 px faint chevron (chevronDown open, chevronRight collapsed, `fg-muted` on hover) form one button; actions in the end slot stay put. A collapsed section shows only its header, with no 10 px body gap. |
| SidePane | 400 px right pane with its own 44 px header. |
| TerminalPane | Bottom pane: grip, tab strip, actions, output. |
| SettingsHeader, SettingsGroup, SettingRow, TierBadge, ConfigRow | Settings and config. ConfigRow is any value edited through the inline editor (`.sb-cfg-ed`): the model-matrix layout for tiers and phases, or with a label the setting-row layout (label and key on the left, mono value on the right) for review context budget and per-phase compaction overrides. |
| TrayPopover | 360 px popover: brand, stats, tabs, rows, footer. |

---

## 8. Shell and layout

- **Window:** frame color `bg-frame`; sidebar on the left; the content panel inset 8 px with a 12 px radius and a hairline border. The main window opens unmaximized and centered, sized to show the sidebar, the document column and the details pane comfortably: 1400×900 content dp, capped at 90% of the usable screen.
- **Sidebar (236 px):** brand row with the command menu button; Work (count of live items) and History; a collapsible Board section listing Needs you (amber attention badge), Goal runs, Standalone phases, Operations and Finished today, each with its count; at the bottom Specs and Terminal (toggles, highlighted when open), Settings and Keyboard shortcuts.
- **Work area:** the view header and body on the left, the optional Specs pane on the right (it never changes what the main panel shows), the optional terminal docked at the bottom of the main column.
- **Details pane** on the right of a detail view: 272 px, or 380 px while it holds the subtask inspector (every goal with subtasks, since one is always selected). One scroll, padding 16 px 20 px 32 px, sections 24 px apart. Hidden while the Specs pane is open, when toggled off (⌘I) and below 1080 px; then the inspector opens inside the selected subtask row instead.
- **Settings** replaces the sidebar with its own navigation and a Back to app button.
- **Narrow windows:** below 1080 px the details pane folds away and the subtask inspector opens in place in the selected row; below 760 px the sidebar becomes a drawer and the Specs pane covers the panel.

---

## 9. Screens

Copy in quotes is the app's own wording, taken from `desktop-tray` unless marked as new.

### 9.1 Work

- Header: breadcrumb "Work" (or "Work › Needs you" when a Board section is chosen), a filter input ("Filter work"), then Refresh, Specs and Terminal toggles.
- Body: group headers in runtime order: Needs you, Goal runs, Standalone phases, Operations, Finished today. Each row: id (issue key, phase id or operation id), status icon, title, muted summary, then "Stalled" (tinted orange) when stalled, "Pause requested" when pending, step label (mono; "2/5 plans saved" while planning), repository label, progress ring (goals), agent avatar, and elapsed time (finished time for finished items).
- Empty: "No matching work" with Clear filter; per section "No …" / "Nothing finished today".
- Right-click a row: Open, Open terminal here, Copy reason (when there is one), Pause after subtask, Pause, Resume / Run again, Cancel, each enabled by the runtime's verdicts.

### 9.2 Detail

Top to bottom:

1. Chips: lifecycle (tinted), "Stalled", id, issue key for phases and operations, kind ("goal", "phase", "operation"), "fresh" or "stale" ("stale" also after a failed refresh).
2. Title: the runtime's title (`featureTitle`, else summary). The titles in `app/data.js` are examples.
3. Repository location: folder (opens the checkout) and branch (opens the branch on its web host; desktop `RepositoryWebLinks.kt`), or "no branch".
4. Run actions: "Pause after subtask" (or "Pause requested", disabled), "Pause" (danger; stops the runner now), and when the item is resumable the launch button with the runtime's label ("Resume", "Run again", "Start") plus "Agent: `<id>`" / "Choose agent", then "Cancel", then "Recover" when recovery is offered. Under them, "Starts the installed agent harness" when a launch is possible. When a refresh failed, every control is disabled and a warning hint reads "Refresh the detail before using run controls." The view header then shows the stale indicator ("Stale", tooltip "Showing stale work data") after the pager, and the screen keeps its last data.
5. "Pause recorded. The goal pauses at its next checkpoint." while a pause is pending.
6. Run reason (ReasonBlock, "Copy reason"), folded to 3 lines with "Show more" when it overflows.
7. Incident (when present, collapsible): status ("Needs your action", "Diagnosis ready", "Investigating block", "Investigation queued", "Partial diagnosis", "Diagnosis unavailable", "Recovery available", "Recovering", "Recovery failed", "Resumed"), cause, next action, certainty; prerequisite checkboxes (Recover is enabled once all are confirmed); operator decision when a decision is awaited (section 9.2.1); evidence behind an "Evidence · N" toggle, collapsed by default, with EvidenceRow rows; report status with "Open issue" when submitted. When a decision is awaited and there is no incident, an "Operator decision" section holds the decision block in this position instead.
8. Metrics: Subtask n/m ("x complete · y pending") or Goal stage ("Planning", "Goal finish"; planning shows no wave note, because the desktop domain model has no planning wave); Phase with execution label ("pass 2", "gate run 2") or "n/m plans saved"; Goal active (or "Total run time … finished `<time>`" when finished; "Phase elapsed" or "Operation elapsed" for those kinds); Subtask active ("ticking" or "last known duration").
9. Subtasks: an index of the goal's subtasks, then a final Goal finish row. Each row shows number, status, title, commit sha (ShortId), the current-subtask timer and "no change"; no block reason and no body, except the Goal finish row, which keeps its pr, monitor and monitor_fix chips under the title. Rows are selectable (section 7, SubtaskRow): clicking a row selects it, and the Goal finish row is selectable too. When a goal opens, the selection is the blocked subtask, else the current subtask, else the first. The selection is kept per item while paging (J/K, pager) and while Subtasks is collapsed. With more than 10 subtasks the section starts collapsed; its header count and progress ring still show progress. While planning, the section reads "n/m plans saved" and "Subtasks appear here once their plans are saved." with no "Planning wave N." text and nothing is selectable.
10. Phase pipeline (goals past planning; standalone phases and operations with more than one step). Operations show the Step metric and elapsed time, a pipeline only with more than one step, and no subtask timers.
11. Activity: one feed that replaces the former activity section ("Goal activity", "Phase activity", "Operation activity") and the History section. Entries come only from recorded facts, oldest first:
    - per execution in `executionHistory`: "Run N started" at `startedAt`, and when `finishedAt` is set "Run N `<lifecycleState>` `<result>`" with a StatusIcon for that state (completed, failed, interrupted and paused, blocked, canceled). With one execution the actor is "Goal run" for a goal, else the phase or operation id. The invocation id is in the tooltip only;
    - with no execution listed, "`<id>` started" from the item's `startedAt` and "`<id>` completed / failed / canceled" from `finishedAt`;
    - the last worktree edit: "`<agent>` edited `<path>` +i −d" at its `recordedAt`;
    - the latest event: "`<phase>` · `<worker role>` `<summary>`" at its `at`;
    - the last agent activity: "`<agent>` `<lastAgentActivityLabel>`" at `lastAgentActivityAt`.
    Times read relative ("12s ago", "4m ago", "3h ago", "2d ago"). Only the latest 8 show; "Show N earlier" reveals the rest for that item.
12. Goal QA: "QA found n findings; all fixed …" or "QA could not fix n findings. Review them yourself.", findings marked Fixed or Not fixed.
13. Goal assessment: "Completed · round n" or "Running · round n", findings with severity, ref, path and line, scenario, verification, state. With two or more findings the header's end slot has a Segmented order: Default (the runtime's order), Priority (blocker, critical, high, major, medium, minor, low, nit, info, then anything else; ties by status) and Status (open, refuted, fixed; ties by priority). Sorts are stable, and the choice holds while paging.
14. QA report (standalone QA) and plan report (standalone plan: "Planned n subtasks.").
15. Standalone phases and operations only: their own Validation, Phase results and Spec bundle sections when the runtime returns them, as before (bundle order: `decomposition-manifest.yaml`, `spec.md`, then every other file by name with digit runs compared as numbers). For goals these moved to the details pane.

Every section header from item 7 down can collapse its body (section 7, Section). Collapse state is kept per section while paging between items (J/K, pager); untoggled sections start from their default (Subtasks collapsed past 10 subtasks, Evidence collapsed).

Details pane, top to bottom in one scroll:

- **Execution:** Workflow and Invocation (ShortId), Agent (small Avatar and name), Model ("claude-opus-5-5 · high" in sans), Liveness, Started, Updated. Rows without a value are left out.
- **Runs and fixes:** Runs (total of `phaseAttempts`), Fixes (total of `fixIterations`) and Relaunches (total of `reAttemptCauses`), each with a Breakdown by phase or cause under it; Findings (`findingsInScope`, tooltip "Findings in scope") and Kills (`supervisorKills`, tooltip "Supervisor kills"). Zero or empty rows are left out; the section is left out when every row is.
- **Subtask inspector** (goals with subtasks), a PaneSection titled "Subtask 03" with the subtask's StatusIcon and status word. Inside (section 7, SubtaskInspector): the title; the block reason when blocked; Commit (ShortId with copy); Spec file (the file name with a fileText icon, the whole value a button that opens the file, tooltip "Open the spec file" with ↵); Depends on (SubtaskChips from `WorkSubtask.dependencies`, optional ones marked); Needed by (the reverse edges, same chips); Validation ("passed · 12 checks" or "failed · 3 checks" after a 6 px dot in `st-done` or `st-failed`, from `SubtaskValidation`); then Review, one block per `ReviewPass` ("Pass N · verdict · k unresolved" and its findings as compact FindingRows), then the audit (`PhaseConclusion` verdict and summary) when present. A chip selects that subtask, in the index too.
- **Goal finish**, instead, when the Goal finish row is selected: a PaneSection "Goal finish" with its status, and a PropertyList of pr, monitor and monitor_fix (labels in mono) with their states ("Done", "Running", "Pending", "Failed"), then one line each for Goal QA ("passed · 2 of 2 fixed") and Assessment ("Completed · round 1 · 2 findings") when the goal has them. Those two rows are buttons that scroll the document to the full sections; the inspector does not repeat their findings.
- **Spec files:** the goal-level files of `goalSpecBundle` (`decomposition-manifest.yaml`, `spec.md`, `traceability.md`, whichever exist; subtask specs open from the inspector), in that order and then by name, as 28 px file rows that open the file.

Phases and operations keep the 272 px pane with Execution and Runs and fixes.

Narrow windows and the Specs pane: whenever the details pane is not shown (below 1080 px, while the Specs pane is open, or toggled off with ⌘I), the selected subtask row expands in place with the same inspector content minus the title (the Goal finish row shows its steps and pointers). Only the selected row is expanded. Clicking the selected row again folds it; selecting another row expands that one.

#### 9.2.1 Operator decision (new in the app)

Shown when the runtime says the goal awaits an operator decision: inside the incident section, or in its own "Operator decision" section when there is no incident. A segmented choice of Retry fix, Accept and advance, Abandon subtask; Retry fix requires instructions ("What should the agent change?"); "Record decision" calls `goalOperatorDecisionService.record`. Afterwards: "Decision recorded … Resume the goal to apply it." (new copy). The decision takes effect when the goal resumes.

### 9.3 History

Header: tabs All, Goals, Phases, Operations and a filter. One group "Finished within `<retention>`". Rows: id, status, title, kind caption, repository, total run time, finished time. Empty: "No finished work" or "No matching work". Rows open the detail.

### 9.4 Specs pane

- Header: "Specs", project picker ("Choose project"), Collapse all, close ("Close specs pane").
- Toolbar, driven only by the runtime's `SpecBundleControl` verdicts:
  - Nothing or a plain folder selected: "Select a spec folder to start, pause or cancel it."
  - A file inside a bundle: "Actions run a whole spec folder, not one subtask. Select `<KEY>` to control it."
  - A bundle: key, title and run state, then Start / Resume / Run again with "Agent: `<id>`", Pause ("Pause requested" while pending), Cancel, and a Purge icon ("Purge `<KEY>` run history", or "Pause or cancel the goal before purging" when disabled).
  - Cancel confirms inline: "Cancel `<KEY>`? The run stops and the goal is marked canceled. Its branch and commits stay." with "Keep running" and "Cancel goal".
  - After Pause: "Pause recorded. The goal stops after its current subtask."
  - Refusals: "Couldn't start: <summary>", "Couldn't purge: <summary>".
- Filters: "Filter by key, title or file" and All / Active / Pending / Done.
- Tree: bundles with status and progress; expanded bundles list spec.md, subtask specs with status, traceability.md and decomposition-manifest.yaml; a `done` folder with its count. Clicking a folder or bundle selects and toggles it; clicking a file selects and opens it.
- Footer: the store path and Reveal.
- States: "No specs yet" ("Plan a feature with /skill-bill phase:plan and its bundle shows up here."), "No specs match" with Clear filters, "Specs unavailable" with Retry, "This project has no checkout on this machine."
- Purge dialog: "Purge `<KEY>`?", Deleted (goal and subtask run history, run evidence and task tracking, checkpoint refs, subtask progress back to not started) and Kept (every spec file in the folder, your branch and its commits, open pull requests), "This can't be undone …", "Keep run history" and "Purge `<KEY>`". Initial focus and Enter go to "Keep run history", the safe action.
- Speed: build the tree from `specBrowserService.snapshot` once per project, refresh it on spec-directory changes (desktop `NioSpecDirectoryChanges`) and on the specs-related signal, and fetch `control` for the selected bundle in the background; selection itself must not wait on it.

### 9.5 Terminal pane

- A bottom pane in the main window, toggled from the sidebar, the header and Ctrl+`.
- One shell per tab on a PTY: pty4j `org.jetbrains.pty4j:pty4j:0.13.10` (Maven Central) with JediTerm `org.jetbrains.jediterm:jediterm-core` and `jediterm-ui` 3.57 (from the JetBrains intellij-dependencies repository, not Maven Central), as in `desktop-tray`.
- A new tab opens in the repository root of the current selection (a work item or a spec's project checkout), or the home directory.
- Tabs are named after their project (`~` for home); a name gets a number only while another tab shows the same name; a custom name (double-click to rename, blank restores the default) shows as typed and is never numbered.
- Close a tab with its ×; closing the last tab closes the pane. Resize by dragging the top edge or with taller and shorter buttons; the height is remembered. A shell that exits closes its tab.
- Shells live as long as their tab and window; a database switch keeps them.
- While the terminal has focus, the app's single-key shortcuts are off (section 10).

### 9.6 Settings, App group

- General: "Database path, blank uses runtime default" with "Save settings" and "Current database: `<path>`"; "The path is only spaces. Clear it to use the default."; Claude account: the Claude config directories found ("Runs started here use it as CLAUDE_CONFIG_DIR.", "No Claude directory found"); history retention 6h, 24h, 7d; Start at login.
- Notifications: A goal is blocked; A goal or phase fails; A goal completes; A pause takes effect; A standalone phase finishes (off by default); QA could not complete. Keys: `blocked`, `failed`, `goal_completed`, `pause_took_effect`, `standalone_phase_finished`, `qa_incomplete`.

### 9.7 Settings, Runtime config

Each row shows its config key and default. Saving shows "Saved to config.json" (new copy).

- **Models:** agent tabs claude, codex, cursor; junie disabled ("junie cannot honor a model or effort directive"). Tiers Reasoning and Implementation. Phase groups with each phase's tier badge (R or I), resolved model and effort, and an "override" tag. Default phase tiers are `DEFAULT_PHASE_TIERS` in `ExecutionMatrixModels.kt`. Editing a row expands it in place: Tier (phases only; applies to every agent), Model (required, passed as typed), Effort (optional), Save, Cancel, Reset to tier. There is no model catalog check.
- **Compaction:** enabled (default on), window tokens (default 400,000), trigger percent (default 70; the trigger must be at least 200,000 tokens), per-phase overrides. Enabled, window and trigger keep inline controls (toggle, input, range); the "Compact at N%" range and its error hint sit side by side in `.sb-sr-ctl`. Each per-phase override row (value "`<window>` tokens · `<pct>`%") expands the same inline editor as the model rows: Context window, Trigger percent, Save, Cancel, Remove override.
- **Project:** project name and store path, "Open config.yaml"; spec source local or linear (default local); Gradle wrapper (repo-relative, no `.` or `..` segments, blank means the repository's `./gradlew`); phase instruction files in `<repo>/.skill-bill/phases/<phase-id>.md` (committed with the repository, 16 KiB cap, unknown phase ids are skipped); review context budget with defaults from `ReviewContextBudgetPolicy` (byte limits must be positive). Spec source and Gradle wrapper keep inline controls. Each budget row expands the inline editor (one field, Save, Cancel); the group header counts changed values ("n changed").
- **Telemetry and agents:** level off, anonymous, full (default anonymous); proxy URL (blank uses the default endpoint); batch size (default 50); detected agents (read-only); files `.config/skill-bill/config.json` and `.skill-bill/install-selection.json` under the user's home directory.
- **Edge states:** an unreadable config file shows the runtime's error with its key path and never overwrites the file; a file changed on disk while a row is open asks to reload or keep editing; YAML comments do not survive a rewrite, so ask once per file before the first save.

### 9.8 Tray popover and notifications

- Popover: brand, stats Running, Needs you, Done 24h; tabs Goals, Phases, Operations with counts and an attention dot; compact rows; "Finished in the last hour" under Phases and Operations; footer Open Skill Bill, Refresh, Settings, Quit. It opens on the last tab used.
- Notifications fire only on lifecycle transitions (the six keys above), respect the settings, and open the exact item when clicked.
- Platform integration (tray icon, macOS status item, notifications, start at login, KDE behavior) is solved in `desktop-tray`; see its ARCHITECTURE.md.

### 9.9 Problem states

"Loading your work", "Couldn't load your work" ("Check the database path in Settings, then try refreshing."), "Skill Bill runtime unavailable" / "Skill Bill runtime not installed." / "The installed Skill Bill runtime is not the build this app was packaged with." with "Install a matched runtime and desktop pair with `<reinstall command>`, then retry.", "Opening your database", "Skill Bill is switching to the selected database.", and the storage failures missing, access, busy, corrupt, incompatible schema, incompatible record (desktop `ProblemCode`, `StorageFailure`). Failed refreshes keep the last data with a stale indication.

---

## 10. Interaction model

Interaction patterns come from Linear; they only trigger the actions above.

| Keys | Action |
| --- | --- |
| ⌘K / Ctrl+K | Command menu |
| ? | Keyboard shortcuts |
| G then W / H / S / , | Work / History / toggle Specs pane / Settings |
| Ctrl+` | Toggle terminal |
| Ctrl+Shift+` | New terminal tab |
| / | Focus the filter |
| J / K or arrows | Next / previous row; in detail, next / previous item |
| Enter | Open the focused row |
| ↑ / ↓ (Subtasks list focused) | Move the subtask selection; the inspector follows. J / K still move between work items |
| Enter (Subtasks list focused) | Open the selected subtask's spec file (nothing for Goal finish) |
| P | Pause after subtask |
| Shift+P | Pause now |
| R | Resume / Run again |
| ⌘I / Ctrl+I | Toggle the details pane |
| [ | Toggle the sidebar drawer |
| Esc | Close the top layer (menu, dialog, command menu), cancel an open config edit, leave detail or settings |
| ⌘Enter | Save an open config edit |

- Single-key shortcuts are off while a text field or the terminal has focus.
- Command menu groups: the current item's actions (Open, Pause after subtask, Pause now, Resume, Cancel, Copy reason, Open terminal in `<repo>`), Navigation (Work, each Board section, History, Settings), Panes (Specs, Terminal, New terminal tab, Refresh), every work item by key and title, Config (Models, Compaction, Keyboard shortcuts). Arrow keys move, Enter runs, Esc closes.
- Opening a popover menu moves keyboard focus to its first item. In the command menu, hovering a row moves the highlight to it.
- Destructive actions confirm only where the app already does: Purge (dialog), Specs Cancel (inline), Recover (preview dialog). Pause and Cancel in the detail act immediately, as today.
- Feedback appears as a toast for five seconds (desktop: the window feedback popup) and never claims more than the runtime reported.

## 11. Copy reference for control feedback

| Situation | Text |
| --- | --- |
| Pause after subtask recorded | "Pause recorded. The goal pauses at its next checkpoint." |
| Stop with confirmed exit | "Stop confirmed. The goal runner has exited." |
| Stop without confirmed exit | "Stop signal sent. The goal runner's exit was not confirmed; refresh and check it before retrying." |
| Stop with no process | "Request accepted. No running goal process needed stopping." |
| Spec pause | "Pause recorded. The goal stops after its current subtask." |
| Recovery applied | "Recovery request finished. Incident details were refreshed." |
| Recovery preview stale | "The incident changed. Refresh it and review a new recovery preview." |
| Controls blocked by a failed read | "Refresh the detail before using run controls." |
| Settings saved | "Saved" |

The complete set is in `desktop-tray/feature/detail/.../DetailViewModel.kt` (`ControlConfirmation` mapping), `RunActionButtons.kt`, `IncidentPanel.kt` and `feature/specs/.../SpecsContract.kt`.

---

## 12. Performance budgets and measurement

Targets for the new app, measured on a copy of a large real database (the one measured in 5.2 is a good benchmark) and on a synthetic store with 5,000 terminal records (as in `desktop-tray`'s repository tests):

| Interaction | Target (p95) |
| --- | --- |
| Select a goal or spec: header painted | Same frame (under 16 ms) |
| Select a goal: first detail sections loaded | Under 150 ms |
| Commit signal to board updated | Under 100 ms |
| Filter keystroke to results | Under 50 ms |
| Cold start to first board | Under 2.5 s, including the runtime graph |
| An active goal writing probes and heartbeats | No visible stutter; app CPU near idle between signals |

Measurement plan:

1. Profile the current app with JFR (section 5.2) to confirm where the 5 seconds go.
2. Add timing around each read in the new data layer, behind a diagnostics flag, and log signal-to-render latency.
3. A benchmark harness that replays a burst of commits against a copy of a real database and measures the targets above. Keep it out of the unit test suite: AGENTS.md forbids timing-based tests.

## 13. Suggested plan

Plan with `/skill-bill phase:plan` on the target machine, using this document as the intake. Keep subtasks independent: each applies its rule to whatever is present in the tree, with no ordering between subtasks or bundles. Acceptance criteria go in a list; tests go under test obligations, never in acceptance criteria.

Two bundles fit the work: one for the runtime (R1–R5) and one for the app. Suggested workstreams:

**Runtime**

- R1 Session read API. Acceptance: one connection serves many reads; compatibility verified once per open; reopen on generation or file-identity change; read transactions stay short. Test obligations: repeated reads open one connection; a replaced database file is detected; a writer can checkpoint while the session is open.
- R2 Fine-grained signals. Acceptance: activity writes (worktree probes, activity stamps, heartbeats) bump only the activity topic; board topics change only with displayed board facts; the signal carries changed ids with an overflow marker; `DesktopReadSchema` bumped. Test obligations: a probe insert leaves board revisions unchanged; a lifecycle change bumps the board topic and lists the id.
- R3 Board read model. Acceptance: one row per work item with the board and header facts, written in the source transaction; board read is one indexed query; deadlines stored. Test obligations: each lifecycle transition updates the row in the same transaction; rows match the existing projection for a recorded store.
- R4 WAL and journal hygiene. Acceptance: checkpoint after goal completion and when idle; journal bounded to what readers need. Test obligations: the journal stays bounded after repeated probes; reads that need the latest edit still return it.
- R5 Config write service. Acceptance: writes the keys in 5.4 with existing validation, unknown keys kept, atomic replace, refusal on concurrent change; exposed on `RuntimeComponent`. Test obligations: an invalid value is refused with its key path; a file changed after read is not overwritten; unknown keys survive a write.

**App**

- Bootstrap and runtime bridge (5.1), signal watcher and read session use (5.3), repositories with shared flows.
- Design system: tokens (6) and components (7).
- Shell and navigation (8), command menu and keyboard (10).
- Work (9.1), Detail (9.2, including operator decision), History (9.3).
- Specs pane (9.4), Terminal pane (9.5).
- Settings (9.6), Runtime config (9.7).
- Tray and notifications (9.8), problem states (9.9), packaging and the runtime-free build check.
- Performance harness (12).

## 14. Open questions

- Which capabilities from 4.2 belong in the first release (preflight before Start and the findings ledger are the strongest candidates).
- Whether R2 and R3 are in the first release or follow R1.
- Packaging and distribution of the new app beside or instead of `desktop-tray`, and how `install.sh` installs it as the matched desktop.
- Light theme: not planned.

## 15. Source references

| Topic | Path |
| --- | --- |
| Desktop architecture, platform integration | `desktop-tray/ARCHITECTURE.md`, `desktop-tray/docs/` |
| Screen contracts | `desktop-tray/feature/*/src/commonMain/kotlin/.../*Contract.kt` |
| Desktop domain model | `desktop-tray/core/domain/src/commonMain/kotlin/dev/skillbill/tray/core/domain/model/WorkModels.kt`, `SpecModels.kt` |
| Runtime bridge, signal watcher | `desktop-tray/core/data/src/jvmMain/kotlin/dev/skillbill/tray/core/data/runtime/`, `.../signal/` |
| Runtime graph | `runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt` |
| Board and detail models | `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/idestatus/model/` |
| Controls | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/work/control/` |
| Spec browser | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/specbrowser/` |
| Operator decision | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/goalrunner/GoalOperatorDecisionService.kt` |
| Commit signal | `runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/DesktopCommitSignal.kt`, `DesktopReadSchema.kt`; `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/DesktopCommitSignalPublisher.kt`, `core/schema/DesktopChangeRevisions.kt` |
| Read sessions | `runtime-kotlin/runtime-infra/sqlite/src/main/kotlin/skillbill/infrastructure/sqlite/SQLiteDatabaseSessionFactory.kt`, `core/schema/DatabaseRuntime.kt` |
| Config models | `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/config/model/` |
| Config reads | `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/config/ConfigResolutionService.kt` |
| Telemetry config | `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/telemetry/TelemetryConfigRules.kt`, `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/telemetry/transport/TelemetryConfigStore.kt` |
| Phase instruction files | `runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/phase/prompt/compose/PhaseProjectInstructions.kt` |
| Earlier designs | `design/App/` (tray v2, spec browser, config pane; some ideas there were never built) |
