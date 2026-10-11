# Desktop architecture

The desktop app uses the independent `desktop-tray` Gradle build and package
prefix `dev.skillbill.tray`. Application use cases own polling, selection,
settings, goal controls, notification transitions, and their coroutine scope.
Infrastructure owns CLI processes, preference storage, OS integration, and
tray dependencies. Presentation maps typed data to views. The UI does not read
the work database or invoke the CLI.

Windows and Linux use `dev.nucleusframework:composenativetray:2.1.6` and its
tray anchored popup artifact at the same version. Linux uses its
StatusNotifierItem backend. macOS uses the app-owned AppKit status item so it
can set a template image and count title without creating a second tray icon.
The Compose popup API is marked alpha. GNOME's stock shell needs its
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
closed, so stock GNOME users can reach every action without an AppIndicator
extension. Tray icon anchoring, native rendering, menu behavior, and packaged
installer behavior still need verification on each supported host.
