# Desktop architecture

The desktop app uses the `desktop-tray` Gradle build and package prefix
`dev.skillbill.tray`. Its composite build links runtime libraries from
`../runtime-kotlin` for compilation and tests. The packaged app excludes runtime
modules. `DesktopBootstrapKt` checks the installed runtime's build identity, then
loads desktop jars with the jars in `runtime-cli/lib` through an application
class loader. Work reads and controls run in process. The modules are
`:core:common`, `:core:domain`, `:core:designsystem`, `:core:ui`, `:core:data`,
`:core:datastore`, `:core:testing`, the screens `:feature:work`, `:feature:detail`,
`:feature:history`, `:feature:settings` and `:feature:specs` (the spec-store browser pane, whose selection is pane-local), and the `:composeApp` shell. There is no
root source set: the root build is an aggregator whose `check` depends on every child
project's `check` and on the convention build's `:convention:check`, whose `test` runs
every module's `jvmTest`, and which aliases the `:composeApp` distribution tasks
(`run`, `createDistributable`, `stageAppImage`, `packageAppImage`, `packagedDataSmoke`).
[`docs/architecture-parity.md`](docs/architecture-parity.md) holds the module
inventory, the migration inventory for the retired single-module tree and the Readian
parity matrix.

Each feature owns one screen's typed `UiState`, `Action` and `Effect` contract, its
ViewModel and its Compose content, all in `commonMain`. A feature depends only on
`:core:common`, `:core:domain`, `:core:designsystem` and `:core:ui`; it never reaches
`:core:data`, `:core:datastore`, `:core:testing` in production, the shell, or another
feature. `:core:ui` holds what the screens share: the paged-list state, the section
pager, effect and feedback delivery, entry-scoped ViewModel stores, formatting and the
row and pagination components. `:composeApp` composes the windows, owns selection,
the tray and the notification and store-session coordinators, and builds ViewModels
through the generated `ViewModelFactory`. Architecture guards in `composeApp` enforce
these edges with rejection fixtures.

Every section list is a `PagedListState` with separate loading, empty, refreshing,
read-error and append-error states. The board keeps paging and liveness per
subscription, so each owner (the main window's `WorkViewModel`, the tray and the
notification coordinator) reads the live and recent sections through its own
`TrackedBoardSections`, the detail pane observes the sections it needs directly, and
`HistoryViewModel` owns history. Effects are per family: navigation
effects keep the newest one and transient effects hold at most four. Action feedback
travels in `UiState` until the window snackbar displays and acknowledges it by id. Each window hosts a bottom popup that dismisses after a few
seconds or when the user closes it. Read failures use the same popup; retry controls
stay available. Launch reservations are app scoped and keyed by work identity, so
the detail and Specs controls disable together until a launch registers or fails.
`LaunchRequests` owns the launch request and registration wait in an app-owned scope.
Entries await its result without owning or canceling the request. App shutdown cancels
and joins launches before closing the database session.

Four lifetimes are generated with kotlin-inject and Anvil, each with a named owner.
`AppScope` lives for the process and is owned by `AppOwner`, which ends it in order on
quit: windows, coordinators, then the database session and settings store.
`DatabaseSessionScope` lives for one selected database and is owned by the data layer's
`DatabaseSession`; switching or closing it releases the runtime graph, Room handles and
observers, and every window resets to the new generation. `WindowScope` lives while a
window is not disposed and is owned by its `WindowOwner`; hiding keeps it. `EntryScope`
lives while a route is on a window's back stack, through the entry's ViewModelStore.
Navigation 3's `NavDisplay` renders each window's `WindowNavigator`; stacks are saved
as versioned snapshots and restored only for a valid database generation. The tray, the
notifications and the host intents reach windows through `HostIntentRouter`, which lands
an intent in the window used last.

Guards run in `:composeApp:jvmTest` over Gradle-supplied manifests, each with nonempty
input assertions and synthetic rejection fixtures: `ModuleGraphRulesTest` (the settings
project list and module edges), `DesktopArchitectureTest` and `SourceRulesTest` (source
rules), `NavigationTypingRulesTest` (typed routes and arguments) and `DeletionRulesTest`
(no combined work port, no process launcher outside the approved native files, no retired
`cliOverride` or `pollSeconds` preference names, no repeating timers in data or feature
code, no retired `ReasonTooltip` renderer).
No baseline, exemption or suppression list backs any of them.

Windows and Linux use `dev.nucleusframework:composenativetray:2.1.6` with an
app-owned Compose window for the tray popover. Linux uses its
StatusNotifierItem backend. Its native library comes from the pinned 2.1.6
source archive with a verified SHA-256 and patches for `ItemIsMenu=false` and no exported
menu object.
This lets KDE send icon clicks to the activation handler. Windows and Linux
do not attach a native context menu. Icon activation toggles the designed
tray window, which stays above the main window. Linux builds need gcc,
pkg-config, libsystemd headers, curl, Python 3, and a JDK. macOS uses the app-owned AppKit status item so it
can set a template image and count title without creating a second tray icon.
GNOME's stock shell needs its
AppIndicator extension to expose StatusNotifierItem icons. The app keeps its
desktop window available when the platform reports no system tray.

The application convention transforms the Compose 1.12.1 runtime jar to reset
`SurfaceSkiaLayerComponent`'s disposed flag after its native layer reattaches.
Skiko recreates the native layer on peer replacement, but Compose otherwise keeps
dropping redraw requests after that replacement. The transform rejects a changed
Compose version or class shape. No automated test covers peer replacement, because
checking it needs real time against a native window.

Native notifications use `dev.nucleusframework:nucleus.notification-common`
2.5.18. It exposes activation callbacks for opening the exact selected item.
Start at login uses `nucleus.autolaunch` on macOS and Windows and a per-user XDG
autostart entry on Linux.

The typed tray projection gives failed work priority, then actionable work,
running work, and idle. Windows and Linux render the running count over the
state icon. macOS uses the app-owned AppKit status item, marks its symbol as a
template image, and puts the running count in the status title. The macOS
adapter source is `composeApp/src/jvmMain/native/macos/MacStatusItemBridge.m`.
macOS hosts compile it with `:composeApp:compileMacTrayNative` from the
desktop-application convention plugin, and it is packaged as a jvmMain
application resource. Its native menu groups the current goals, phases and operations and
exposes the shared app actions.

The main window opens on launch and remains available when its Linux window is
closed. With a registered tray, closing the main window hides it. Without a
tray watcher, the app keeps the main window visible. Tray icon anchoring, native rendering, menu behavior, notification
display, login-start registration and installer packages are not exercised by any
automated task on any host; verify them by hand on each supported host. CI runs the
checks, `createDistributable` and `packagedDataSmoke` on Linux, macOS and Windows runners.

The main window and tray popover follow `design/App/Skill Bill Tray v2.dc.html`.
Their fonts and icon ship as classpath resources. On Linux, scale detection
follows Context's `DesktopUiScale.kt`. It reads KDE's Xwayland scale when AWT
reports 1x, then applies the same correction to window bounds and Compose
content density. Native density takes precedence to prevent double scaling.
`SKILL_BILL_DESKTOP_UI_SCALE` or `-Dskillbill.desktop.uiScale` provides an
explicit override, with values such as `1.5` or `150%`.

Room 3.0.3 reads the runtime-owned SQLite store through the SQLite 2.7.1 driver
supplied by the installed runtime. Runtime migration 52 installs Room's
identity hash and committed change revisions. Room owns neither migrations nor
a private copy of workflow state. Missing, incompatible, and corrupt stores are
refused before queries run. The desktop bundles no runtime module or migration
list. Its bootstrap refuses to load the app when the installed runtime is absent
or its build identity differs. Apply migrations through the installed runtime's
supported write path, then retry the desktop.

The repository resolves the selected database through the existing runtime
composition root. A blank preference uses runtime path precedence. The app does
not discover an executable or invoke work commands. Shared status projection
supplies lifecycle, identity, planning, and ownership facts to the desktop. The
current Room board and detail paths then call `IdeStatusService` through a
separate JDBC read. They do not assemble both sets of facts in one Room snapshot.
Shared Stop and Pause admission compares the database identity, repository, goal,
execution identity, and lease fence in one write transaction. Progress revisions,
timestamps, and lease heartbeats may advance after the user reads the goal.
Changing the database, execution, or runner owner still rejects the request.
An accepted request does not claim completion.

Needs you, live goals, live phases, live operations, History, and recent completions have separate
desktop queries. Search runs in SQL before the ten-row result limit. The desktop
can inspect up to forty candidate rows per request and retries up to three times
when another process changes the observed snapshot. The shared status read is
bounded too: it takes at most 500 workflows and 500 eligible standalone rows per
request. Standalone execution history pages by a run-sequence anchor, so newer
runs recorded between reads neither repeat nor skip a row, and detail phases are
projected one page at a time. The Room query and the JDBC projection use
separate connections. History has its own retention and family filter. Sidebar,
history, and detail cursors are separate at the desktop boundary, and retained
windows cap at five pages. Detail cursors carry the store id, revision,
repository identity, list, and page size; a cursor from another store, list, or
revision is refused. Detail reads publish the runtime snapshot and its revision
without retrying when another writer commits during projection. Run options reuse
that detail snapshot; control requests still validate their target when invoked.
Generation-bound cursors reject stale appends and details after a path change or
same-path replacement. Failed reads retain prior data with a stale indication.

The runtime publishes changed work revision topics to a small sidecar file only
after its transaction commits. The file carries a generation token and the
latest revision snapshot. Writers serialize publication so a delayed writer
cannot replace a newer snapshot. The desktop watches only that file. It does not
watch the database, WAL, or shared memory files, and the signal watcher opens no
SQLite connection. Room's separate read-only snapshot connection still checks
query coherence. Rollbacks and unrelated table commits publish nothing.
Registration and reconnect read a fresh baseline; watcher overflow rereads the latest signal.
An observer failure leaves the last view stale until explicit refresh reconnects.
Lease and retention deadlines schedule one invalidation at the known boundary.
Each board section and each detail owner has its own `DeadlineScheduler`, and a
fired deadline reprojects only that owner. The runtime stamps time-windowed
facts with a `membershipDeadline`, so a surface rereads at the flip instant
instead of judging the window itself. Elapsed-duration display ticks without
database reads.

DataStore 1.2.1 stores app preferences in the injected settings file. Typed Flow
publishes committed settings. Atomic field updates preserve concurrent choices.
Legacy preferences migrate once after a durable commit. `cliOverride` and
`pollSeconds` are discarded, and neither becomes a database path. Corrupt files
remain on disk and report an error. DataStore stores no workflow rows.

The work-data path runs no installed CLI, does no PATH or login-shell discovery, and
parses no command output. Runtime services reached through the in-process graph
may run `git` or other processes (operator decision, 2026-10-15). The removed
`CliBoardSource` call sites in `Main.kt` were first replaced by `RoomBoardSource`,
which the feature split then retired in favour of per-section board, detail,
policy and control repositories. The old
`CliResolver` and `ProcessRunner` dependencies have no desktop consumers. The
`WorkPayloadDecoder` and `WorkPayloadKeys` transport path is gone because Room
rows map through `RuntimeWorkMapper`. The old CLI mutation, resolver, process,
and decoder test files were removed with those adapters. Desktop preferences no
longer expose `cliOverride` or `pollSeconds`; `LegacySettingsMigration` only
records and discards those saved values. The only process launcher in the
desktop source set is Linux AppImage `AppRun`, which starts the packaged app.
Native notification/autolaunch libraries, the macOS status-item JNI bridge, and
Linux AppRun are host integration code owned by `:composeApp`; `DeletionRulesTest`
limits process use to four named files. Desktop CI watches runtime-library and
build-identity changes and builds the runtime before packaging. The
`verifyPackagedRuntimeFree` task fails if the application image contains a Skill
Bill runtime module jar.

The runtime owns shared work policy and the desktop renders its typed verdicts.
Verification belongs to the operations group. The sidebar and tray list operations
separately from standalone phases, and History has an operations filter. Verify
detail shows its current step, elapsed time and operation activity. It omits phase
metrics, phase pipelines and subtask timers. Operations use the existing workflow
change topic for board and detail invalidation.
`IdeStatusPolicy.work` emits the per-item facts (`controlEligible`, which includes
freshness, `pauseEligible`, `running`, `recentlyFinishedPhase`, `doneWithinDay`, and
`membershipDeadline`), and `IdeStatusPolicy.arrange` owns the board order, the
live and finished phase and operation lists, the counts, and the tray status. The desktop reaches
it through `WorkPolicyRepository`, keeps the result in `TrayUiState.arrangement`,
and only resolves identities and renders. A declaration-aware source rule in
`DesktopArchitectureTest` walks the desktop and feature surfaces and rejects code that sorts
work, judges lifecycle or freshness membership, or weighs a time window itself.
Detail lists page by the identity of their last row (a per-list anchor), never by a
shared offset, and the desktop refreshes a retained page from that anchor. The status
read pages live work first within a fixed budget and counts the storage work it
spends. The database session
owner is `DatabaseSession`: it detaches under its mutex and disposes outside it,
and a separate lifecycle lock serialises replacement and teardown. Control
results reach the desktop as distinct outcomes (`IntentRecorded`, `Accepted`,
`ExitConfirmed`, `TerminationAttempted`, and a typed incomplete-termination
refusal). Some boundaries are still open: Room projection uses a separate runtime
JDBC snapshot, the desktop and runtime candidate queries still evaluate a union of
every work table before filtering and rely on SQLite to push the predicates down,
and the residuals listed in the SKILL-423 handoff record remain.
[`docs/skill-423-handoff.md`](docs/skill-423-handoff.md) holds the per-obligation
verdicts, owners, and regression evidence; the validation record keeps the
measured results.

The disposable repository tests include 5,000 terminal records, a live goal
with a real manifest and process lease, exact control rejection, external JVM
writers, rollback, reconnect, writer exit after commit, lease expiry, migration,
settings corruption, and same-path replacement. The Room compatibility test
compares durable schema and rows before and after read-only access.

Tray placement captures the native activation coordinates before the UI coroutine
runs. On KDE Wayland, it reads the current output layout with `kscreen-doctor`
and maps the activation point into AWT's display bounds using KWin's Xwayland
scale. Other desktops use the AWT point directly. The display query runs off the
UI thread. Placement uses AWT's display insets to keep the window beside the
nearest panel edge. Tray availability uses the JVM `SystemTray` API without a
`gdbus` query.
The header supports dragging and has a close button that hides the tray window.
Running work is green, attention is orange, and the tray icon uses the bundled
Skill Bill logo. Reports and run reasons use the same three-line preview and a
"Show more" button.
The button opens the full selectable text in a dialog with Copy and Close controls.

KDE hover uses the StatusNotifierItem tooltip description as escaped rich text,
with at most four goals and four live phases in the board urgency order. The
title remains Skill Bill. Native tooltip actions are not interactive, so the
clickable controls stay in the tray window.

Data-layer checks, Readian deviations, deletion evidence and the runtime
architecture review are recorded in [Data-layer validation](docs/data-layer-validation.md).
