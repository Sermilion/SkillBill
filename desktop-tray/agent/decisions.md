## [2026-10-12] Read work through the runtime store
Context: SKILL-423 replaces CLI work-data requests with an in-process view of the runtime-owned database.
Decision: Room opens an existing compatible store read-only. Shared runtime services project lifecycle and identity facts, admit exact-target controls, and own migrations. DataStore stores app preferences.
Reason: Reads must not start commands, create or repair workflow state, or establish a second schema authority. Reusing runtime policy avoids a desktop lifecycle mirror and duplicate runtime graph.
Alternatives considered: CLI fallback would restore executable discovery and output parsing dependencies. Destructive ORM migration and a private workflow copy violate runtime ownership.

## [2026-10-12] Confirm external commits through durable revisions
Context: Runtime writers use independent SQLite sessions. Room's in-process tracker alone cannot establish committed-write observation across desktop processes.
Decision: Register the filesystem watcher before baseline reads. Treat file/WAL events and overflow as hints, then check data version, file identity, and runtime-owned revision topics. Own and close the observer's read-only connection with its session.
Reason: SQLite confirms commit and coherent topic changes. This excludes rollback notifications and recovers committed state after event loss, reconnect, replacement, or writer exit without periodic database polling or a service daemon.

## [2026-10-12] Fence results and baseline every observation session
Context: Cancellation alone cannot exclude delayed reads or notification callbacks after a database change. Reconnect discovery can contain completions that occurred while disconnected.
Decision: Bind asynchronous result admission and notification authorization to the originating store epoch. Establish a silent transition baseline after all required live/recent sections arrive in a new observation session.
Reason: Old-store results must never replace current rows or trigger notifications. Reconnect establishes current facts; only later confirmed transitions may notify, subject to the current toggle and durable identity.

## [2026-10-12] Preserve working data behavior for the SKILL-424 architecture handoff
Context: The operator transferred seven remaining repair obligations to SKILL-424 AC-28 through AC-34 and retained eleven active data-layer criteria in SKILL-423.
Decision: Integrate the data layer into existing controller/windows with the required consumer changes. Preserve implemented bounded queries, paging, and ownership behavior while SKILL-424 owns remaining refinements and final KMP, generated DI, ViewModel, navigation, and native integration work.
Reason: The reduced scope supplies working data behavior without requiring the full application rewrite first. Placeholder modules, duplicate graphs, and a temporary backend would create work the final owners must undo.
Revisit when: SKILL-424 relocates these implementations into its final module and presentation owners.

## [2026-10-11] Keep the desktop build independent
Context: SKILL-418 requires a JDK 21 Compose Desktop app without changing the runtime or plugin build graphs.
Decision: Give desktop-tray its own Gradle settings, wrapper, dependency catalog, packaging tasks, and path-filtered CI workflow.
Reason: The spec requires desktop checks and installers to run independently and excludes the app from runtime-kotlin check. Existing plugin build isolation supplies the repository pattern.

## [2026-10-11] Keep work state authoritative in the CLI
Context: The app displays work across repositories and can pause or stop an active goal. Runs can share an issue key, and responses can arrive after selection or settings change.
Decision: Read board/detail contract 1.0 through the CLI, reject incompatible envelopes, identify selections by work and repository identity, and discard obsolete response generations. Mutations schedule a board refresh.
Reason: The spec forbids direct SQLite access and status derivation in the app. The next board poll establishes whether a mutation took effect; issue-key matching alone could display a different run.
Superseded by: Read work through the runtime store (2026-10-12)

## [2026-10-11] Retain process ownership after logical timeout
Context: CLI requests need bounded UI latency and output while the spec forbids killing processes, including during shutdown.
Decision: Use distinct one-slot runners for polling, detail, pause, and stop. A logical timeout returns a safe failure while daemon drain/reap owners retain the slot until natural exit.
Reason: Releasing a slot at timeout would let repeated refreshes accumulate children. Sharing the poll runner with a mutation can confuse their results, a failure already observed in the IntelliJ plugin.
Superseded by: Read work through the runtime store (2026-10-12)

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
