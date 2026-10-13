# Desktop architecture

The desktop app uses the `desktop-tray` Gradle build and package prefix
`dev.skillbill.tray`. Its composite build links runtime libraries from
`../runtime-kotlin`. Work reads and controls run in process. Application use
cases own section subscriptions, selection, settings, control requests, and
notification transitions. Infrastructure owns Room, DataStore, and host adapters.
Presentation and UI consume typed models and repository contracts.

SKILL-424 owns the final KMP module graph, generated application DI, feature
ViewModels, Navigation 3, and native host integration. This data-layer change
keeps the existing windows and controller.

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

Native notifications use `dev.nucleusframework:nucleus.notification-common`
2.5.18. It exposes activation callbacks for opening the exact selected item.
Start at login uses `nucleus.autolaunch` on macOS and Windows and a per-user XDG
autostart entry on Linux.

The typed tray projection gives failed work priority, then actionable work,
running work, and idle. Windows and Linux render the running count over the
state icon. macOS uses the app-owned AppKit status item, marks its symbol as a
template image, and puts the running count in the status title. The macOS
adapter is built from `src/main/native/macos/MacStatusItemBridge.m` on macOS and
packaged as an application resource. Its native menu groups the current Goals
and Phases and exposes the shared app actions.

The main window opens on launch and remains available when its Linux window is
closed. With a registered tray, closing the main window hides it. Without a
tray watcher, the app keeps the main window visible. Tray icon anchoring, native rendering, menu behavior, and packaged
installer behavior still need verification on each supported host.

The main window and tray popover follow `design/App/Skill Bill Tray v2.dc.html`.
Their fonts and icon ship as classpath resources. On Linux, scale detection
follows Context's `DesktopUiScale.kt`. It reads KDE's Xwayland scale when AWT
reports 1x, then applies the same correction to window bounds and Compose
content density. Native density takes precedence to prevent double scaling.
`SKILL_BILL_DESKTOP_UI_SCALE` or `-Dskillbill.desktop.uiScale` provides an
explicit override, with values such as `1.5` or `150%`.

Room 3.0.3 reads the runtime-owned SQLite store through the bundled SQLite
2.7.1 driver with read-only connections. Runtime migration 52 installs Room's
identity hash and committed change revisions. Room owns neither migrations nor
a private copy of workflow state. Missing, incompatible, and corrupt stores are
refused before queries run. Apply migrations through the runtime's supported
write path, then reconnect the app.

The repository resolves the selected database through the existing runtime
composition root. A blank preference uses runtime path precedence. The app does
not discover an executable or invoke work commands. Shared status projection
supplies lifecycle, identity, planning, and ownership facts to the desktop. The
current Room board and detail paths then call `IdeStatusService` through a
separate JDBC read. They do not assemble both sets of facts in one Room snapshot.
Shared control admission compares the repository, goal, execution, revision, and
lease fence in one write transaction. An accepted request does not claim
completion.

Needs you, live goals, live phases, History, and recent completions have separate
desktop queries. Search runs in SQL before the ten-row result limit. The desktop
can inspect up to forty candidate rows per request and retries up to three times
when another process changes the observed snapshot. These limits do not bound the
shared runtime projection: `IdeStatusService.board` still loads the full work
list and standalone candidate set before selecting a page. The Room query and
the JDBC projection also use separate connections. History has its own retention
and family filter. Sidebar, history, and detail cursors are separate at the
desktop boundary, and retained windows cap at five pages. The shared detail
service still materializes some growing collections before paging them.
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
Elapsed-duration display ticks without database reads.

DataStore 1.2.1 stores app preferences in the injected settings file. Typed Flow
publishes committed settings. Atomic field updates preserve concurrent choices.
Legacy preferences migrate once after a durable commit. `cliOverride` and
`pollSeconds` are discarded, and neither becomes a database path. Corrupt files
remain on disk and report an error. DataStore stores no workflow rows.

The work-data path has no CLI or process adapter. The removed
`CliBoardSource` call sites in `Main.kt` now use `RoomBoardSource`; its old
`CliResolver` and `ProcessRunner` dependencies have no desktop consumers. Its
`WorkPayloadDecoder` and `WorkPayloadKeys` transport path is gone because Room
rows map through `RuntimeWorkMapper`. The old CLI mutation, resolver, process,
and decoder test files were removed with those adapters. Desktop preferences no
longer expose `cliOverride` or `pollSeconds`; `LegacySettingsMigration` only
records and discards those saved values. The only process launcher in the
desktop source set is Linux AppImage `AppRun`, which starts the packaged app.
Native notification/autolaunch libraries, the macOS status-item JNI bridge, and
Linux AppRun remain host integration code. Replacing host helpers belongs to
SKILL-424. Desktop CI watches runtime-library changes and builds the composite
dependencies from source.

The source does not yet meet every intended data-layer boundary. In particular,
the desktop still owns grouping and control-eligibility checks, Room projection
uses a separate runtime JDBC snapshot, and the shared live-board query still
walks historical rows. The validation record tracks the remaining query,
invalidation, pagination, cursor, and lifecycle gaps.

The disposable repository tests include 5,000 terminal records, a live goal
with a real manifest and process lease, exact control rejection, external JVM
writers, rollback, reconnect, writer exit after commit, lease expiry, migration,
settings corruption, and same-path replacement. The Room compatibility test
compares durable schema and rows before and after read-only access.

Tray placement captures the native activation coordinates before the UI coroutine
runs. It uses AWT's display bounds and insets to place the window beside the
nearest panel edge. Tray availability uses the JVM `SystemTray` API. This removes
the `gdbus` and `kscreen-doctor` runtime calls. KDE Wayland native-to-Xwayland
coordinate conversion still needs a platform API implementation; the current
placement falls back to AWT coordinates.
The header supports dragging and has a close button that hides the tray window.
Running work is green, attention is orange, and the tray icon uses the bundled
Skill Bill logo. Blocked rows show the reason with a three-line ellipsis.

KDE hover uses the StatusNotifierItem tooltip description as escaped rich text,
with at most four goals and four live phases in the board urgency order. The
title remains Skill Bill. Native tooltip actions are not interactive, so the
clickable controls stay in the tray window.

Data-layer checks, Readian deviations, deletion evidence and the runtime
architecture review are recorded in [Data-layer validation](docs/data-layer-validation.md).
