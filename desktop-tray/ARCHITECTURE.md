# Desktop architecture

The desktop app uses the independent `desktop-tray` Gradle build and package
prefix `dev.skillbill.tray`. Application use cases own polling, selection,
settings, goal controls, notification transitions, and their coroutine scope.
Infrastructure owns CLI processes, preference storage, OS integration, and
tray dependencies. Presentation maps typed data to views. The UI does not read
the work database or invoke the CLI.

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
desktop window available when no watcher owns that bus name.

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

Board and detail requests load ten records per page through the version 1.1
work payload contracts. The controller requests one page initially. Scrolling near the end requests
one more page. A Load more button covers lists too short to scroll. Refresh
reloads only the pages already requested. Goals, standalone phases, and History share the board pages. Detail
pages bound subtasks, phase records, execution history, and validation evidence.
Phase pipeline labels remain fixed workflow metadata. Refreshes retain the
previous rows until the replacement finishes loading. A later page failure keeps
the received rows and reports the failure.

Board cursors use the candidate's start time and durable identity, with one
observation time and retention window for the traversal. Pagination happens
before expensive status projection. A page can contain no retained items and
still have a continuation cursor. Detail cursors bind an offset and observation
time to the selected work id. The runtime projects completed subtask validation
only for the current page's subtask ids. Both CLI commands accept `--page-size`
and `--cursor`; their default page size is twenty, with a maximum of one hundred.

Board and detail requests allow 120 seconds per page. A timed-out request retains
its one-slot process owner until exit.

Tray placement captures the native activation coordinates before the UI coroutine
runs. On KDE Wayland, a bounded `kscreen-doctor --json` query maps each output's
native origin and size to AWT's Xwayland bounds. This accounts for mixed monitor
scales without relying on Xwayland's stale pointer position over native panels.
The window opens beside the nearest panel edge and stays within its display.
The header supports dragging and has a close button that hides the tray window.
Running work is green, attention is orange, and the tray icon uses the bundled
Skill Bill logo. Blocked rows show the reason with a three-line ellipsis.

KDE hover uses the StatusNotifierItem tooltip description as escaped rich text,
with at most four goals and four live phases in the board urgency order. The
title remains Skill Bill. Native tooltip actions are not interactive, so the
clickable controls stay in the tray window.
