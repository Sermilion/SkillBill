## [2026-10-11] Keep the desktop build independent
Context: SKILL-418 requires a JDK 21 Compose Desktop app without changing the runtime or plugin build graphs.
Decision: Give desktop-tray its own Gradle settings, wrapper, dependency catalog, packaging tasks, and path-filtered CI workflow.
Reason: The spec requires desktop checks and installers to run independently and excludes the app from runtime-kotlin check. Existing plugin build isolation supplies the repository pattern.

## [2026-10-11] Keep work state authoritative in the CLI
Context: The app displays work across repositories and can pause or stop an active goal. Runs can share an issue key, and responses can arrive after selection or settings change.
Decision: Read board/detail contract 1.0 through the CLI, reject incompatible envelopes, identify selections by work and repository identity, and discard obsolete response generations. Mutations schedule a board refresh.
Reason: The spec forbids direct SQLite access and status derivation in the app. The next board poll establishes whether a mutation took effect; issue-key matching alone could display a different run.

## [2026-10-11] Retain process ownership after logical timeout
Context: CLI requests need bounded UI latency and output while the spec forbids killing processes, including during shutdown.
Decision: Use distinct one-slot runners for polling, detail, pause, and stop. A logical timeout returns a safe failure while daemon drain/reap owners retain the slot until natural exit.
Reason: Releasing a slot at timeout would let repeated refreshes accumulate children. Sharing the poll runner with a mutation can confuse their results, a failure already observed in the IntelliJ plugin.

## [2026-10-11] Adapt native tray capabilities per platform
Context: The chosen StatusNotifierItem backend has an alpha popup API and cannot supply the required macOS template/count treatment. Stock GNOME can have no tray watcher.
Decision: Use Compose Native Tray on Windows/Linux, one app-owned AppKit status item on macOS, and keep the main window reachable when Linux tray registration is unavailable.
Reason: Platform adapters preserve the specified native icon treatment without a second macOS tray icon. An invisible tray cannot provide the required fallback actions, so the desktop window remains available.
Revisit when: Host verification or backend changes alter anchoring, menu access, template rendering, or watcher availability.

## [2026-10-11] Require explicit AppImage tool preparation
Context: The spec requires AppImage alongside native installer tasks and makes signing optional unless the operator supplies secrets.
Decision: Package unsigned by default and require the operator to verify and place pinned appimagetool 1.9.1 before running the separate AppImage task.
Reason: The implementation keeps packaging-tool downloads and execution out of Gradle configuration. Separate AppDir staging avoids requiring Debian or RPM packaging tools for AppImage.

## [2026-10-11] Use the native tray API with AWT windows
Context: TrayApp requires the Tao backend, while the desktop windows use Compose's AWT Window. Mixing them caused startup thread errors and an absent Linux tray icon.
Decision: Use the native Tray API with an app-owned popover window. Keep the main Window mounted when hidden. Apply Context's Linux scale correction to window bounds and content together.
Reason: The native Tray API registers with KDE independently of Tao. The mounted main window keeps the application alive after Hide to tray. KDE reports 1.5x Xwayland scaling on this host while AWT reports 1x.

## KDE tray activation

ComposeNativeTray 2.1.6 returns `ItemIsMenu=true` unconditionally. KDE opens
the native menu instead of calling the icon activation handler. Build its
pinned, checksum-verified source with `ItemIsMenu=false`, advertise no
menu object, and omit the native menu. The activation handler opens the designed tray window directly.

## KDE monitor origins

KDE reports tray activation relative to each output native origin. AWT uses
Xwayland bounds with a global scale. On monitors stacked at different scales,
these origins differ. Map the native click using current KScreen output
geometry and AWT screen bounds. Xwayland pointer coordinates can be stale
while the pointer is over a native Wayland panel.
