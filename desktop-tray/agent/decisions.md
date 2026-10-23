## [2026-10-23] Open spec bundle files through suspend openFile returning Outcome
Context: The spec asked for a plain openFile(path) on RepositoryNavigation, but opening a file can fail and must not reach the compose thread.
Decision: openFile is suspend and returns Outcome<Unit>, like openRepository and openDirectory, and runs through the existing navigate failure path on the IO dispatcher.
Reason: Failures reach the UI through the same Outcome path as other navigation links, and the click handler launches it in a coroutine.
Alternatives considered: A fire-and-forget function would hide a missing file or failed launch.

## [2026-10-23] Match File( as a whole word in the presentation guard
Context: The architecture guard banned the raw substring File(, which also matches the openFile( call the spec requires in presentation code.
Decision: DesktopArchitectureTest uses a whole-word pattern for File(, leaving the other storage tokens unchanged.
Reason: It still rejects File(path) and java.io.File(path) but accepts openFile(path), so the guard keeps its intent without renaming the required API.
Alternatives considered: Renaming openFile would contradict the spec; dropping the token would weaken the guard.

## [2026-10-21] Identify packaged Skill Bill runtime by modules and classes
Context: The single-runtime packaging guard rejected Compose and Lifecycle jars because their filenames also start with runtime-. Renaming a Skill Bill jar could bypass a filename-only guard.
Decision: The image verifier checks declared Skill Bill runtime module names and skillbill/ class entries. Packaged smoke and release archive checks inspect class entries. Module discovery includes composite project components.
Reason: A shared filename prefix does not identify Skill Bill code. Class inspection accepts required third-party runtime libraries and detects renamed Skill Bill code; declared module names also catch resource-only runtime modules.
Alternatives considered: Rejecting every runtime-* jar breaks valid desktop images. Checking only filenames misses renamed runtime classes.

## [2026-10-19] Load the installed runtime after checking build identity
Context: The desktop previously bundled the runtime modules, so both the desktop and installed CLI could own schema compatibility and migrations. The desktop still needs runtime services for in-process reads and controls.
Decision: The packaged desktop starts through a JDK-only bootstrap. It resolves `SKILL_BILL_RUNTIME_DIR` or the runtime directory under the user's home `.skill-bill` directory, compares the installed runtime and desktop build identity records, then loads desktop and runtime jars together from a separate application class loader. Runtime migrations remain owned by the installed runtime. This changes [2026-10-12] Read work through the runtime store by requiring the installed runtime at launch; [2026-10-11] Keep the desktop build independent still applies to build ownership.
Reason: One installed runtime must own migrations and schema compatibility. The identity gate prevents a desktop from calling runtime code from another build.
Alternatives considered: Bundling runtime modules preserves standalone launch but keeps a second copy of runtime code in the desktop image.
Revisit when: The desktop no longer calls runtime services in process.

## [2026-10-19] Allow the desktop bootstrap to invoke the app entry point by handle
Context: The bootstrap must load the app after creating a class loader that contains the installed runtime. The source graph cannot see a method handle lookup.
Decision: Operator-approved deviation: `composeApp/src/jvmMain/kotlin/dev/skillbill/tray/bootstrap/DesktopBootstrap.kt` uses `MethodHandles.publicLookup().findStatic` for the delegated entry point. The parent spec constraint is the operator approval. The reflective lookup guard covers `java.lang.invoke` everywhere else.
Reason: A static source edge would load the application entry point before the installed runtime class loader exists.
Alternatives considered: A direct call would resolve application classes in the bootstrap loader and bypass the isolated runtime class loader.
Revisit when: The bootstrap no longer needs to defer loading the application entry point.

## [2026-10-21] Refuse the work store when the installed runtime is a different build
Context: On 2026-10-19 a desktop paired with a runtime from another build migrated the shared database. Compatible-looking schema versions did not prove the two halves agreed.
Decision: Compare the installed runtime's build-identity record with the desktop's own record before SessionFactory.create. Any mismatch, missing or unreadable record publishes runtime_build_mismatch and opens nothing. Retry rereads both records.
Reason: The gate must sit before the session factory, or a refused build has already migrated. Rereading on retry lets a reinstall recover without restarting the app.
Alternatives considered: Compatibility by schema or contract version was rejected; the spec supports only an exact build pair.

## [2026-10-17] Count and notify only successful goal completion
Context: Tray treated any FEATURE_GOAL TERMINAL transition as success, so abandoned, cancelled, and superseded rows incremented Done 24h and emitted goal_completed.
Decision: successfulGoalCompletion requires family FEATURE_GOAL, lifecycle TERMINAL, and finishedAtSource goal_completion. TransitionTracker uses that helper. After the SKILL-423 module split the 24h Done count is runtime-owned: IdeStatusPolicy.work counts doneWithinDay only when the board item's completion source is goal_completion.
Reason: Generic terminality includes non-success closures. Existing completion provenance already distinguishes success; a new public discriminator was not required.

## [2026-10-15] Repair Compose redraw state after native peer replacement
Context: The frozen installed app had a live AWT event queue, active data reads, and a pending Compose recomposition. Its Skiko layer was active, but SurfaceSkiaLayerComponent retained isDisposed=true. Clearing that flag restored the running window. Compose 1.12.1 sets the flag when Skiko removes its peer and never clears it when addNotify recreates the layer.
Decision: Transform the application and test runtime dependency to clear the wrapper flag after addNotify. Pin the patch to Compose 1.12.1 and reject a changed target class or method. Verify redraws and clicks across repeated real native peer replacement.
Reason: The patch preserves the existing window, navigation, ViewModels, and GPU renderer. It needs no runtime reflection, redraw timer, or whole-window replacement.
Revisit when: upgrading Compose. Remove the transform once the dependency handles native peer reattachment itself.

## [2026-10-15] Let desktop reach runtime services that run git
Context: The governed-edge walk banned any desktop path reaching a runtime process adapter, but the in-process runtime services behind work reads and controls run git.
Decision: Operator-approved deviation: desktop may reach runtime services that run git or other processes through the in-process graph. The packaged app still never runs an installed CLI, discovers PATH or a login shell, or parses command output.
Reason: The runtime owns that behavior, and banning it would block shared policy. The ban now sits at the CLI and PATH boundary the app must not cross.
Alternatives considered: An allow-list entry or skipped root was rejected; the rule was deleted, not suppressed, and no baseline grew.
Revisit when: the walk reports an unresolved governed edge into runtime code.

## [2026-10-15] Key navigation entries by database generation
Context: Windows can outlive a database selection or a same-path file replacement, and saved routes can name rows from the previous database.
Decision: Every route entry key includes the database generation, and restore drops routes from an obsolete generation or with invalid arguments, falling back to a safe root.
Reason: An entry, its component and its ViewModel can then never survive into the next database.

## [2026-10-15] Allow serializable routes; keep JSON types banned in presentation
Context: Navigation 3 routes must be @Serializable, but the presentation guard banned all of kotlinx.serialization.
Decision: The forbidden token narrowed to kotlinx.serialization.json, and a typing scan rejects string-keyed Any maps and unchecked-cast suppressions in navigation sources.
Reason: Routes stay typed while JSON payload handling stays out of presentation; no exemption or baseline grew.

## [2026-10-15] Share one app-scoped WorkViewModel; relay from the shell
Context: The work board allows one observer per WorkSection, but the window, sidebar and tray popover all need work state.
Decision: One app-scoped WorkViewModel serves them all; the composeApp shell relays the history filter and board items to other features.
Reason: Separate instances per surface would break the single-observer constraint; features stay independent because only the shell connects them.
Alternatives considered: One ViewModel per surface was rejected for the observer limit; direct sibling-feature dependencies are banned by the source guards.

## [2026-10-15] Must-not-miss feedback lives in UiState, not effects
Context: One-shot effect channels can drop values when full, which is wrong for notices the user must see.
Decision: Navigation (capacity 1) and transient (capacity 4) effects are bounded one-shots; anything that must not be missed goes in UiState pendingFeedback (max 8, monotonic ids, acknowledged by id).
Reason: State survives recomposition and slow collectors; bounded effects cannot grow without limit.
Revisit when: a feedback kind needs more than eight pending items.

## [2026-10-15] Runtime owns work-control policy; desktop projects it
Context: The desktop re-derived pause eligibility and collapsed control outcomes into a generic accept, hiding what the runtime had done.
Decision: The runtime policy emits pauseEligible and typed control results (intent recorded, exit confirmed, accepted); the desktop only projects them.
Reason: One policy owner keeps CLI, MCP and desktop consistent; typed outcomes let the UI distinguish a recorded pause intent from a confirmed exit.
Revisit when: the desktop can drop its remaining freshness check beside the runtime facts.

## [2026-10-15] Inject host and legacy preferences into the application component
Context: The generated component must run in tests without touching the real user home, environment or java.util.prefs.
Decision: ApplicationComponent takes the host environment and the legacy preferences source as constructor arguments; the factory defaults them to the real process.
Reason: Providers that read the process directly made the smoke test mutate and clean real user state; constructor arguments let tests supply disposable ones.
Alternatives considered: Test-only overrides inside providers were rejected as production test hooks.

## [2026-10-15] Keep Room declarations in jvmMain behind a store-access port
Context: Room 3 types and the runtime gateway are JVM-only, while repositories and paging belong in commonMain.
Decision: Repositories in commonMain depend on ports (store access, commit signals, runtime gateway, failure classifier); Room and runtime code stay in jvmMain adapters.
Reason: Keeps commonMain free of runtime, sql and Room imports (enforced by source rules) and lets tests wrap behavior at the port seams instead of adding production hooks.
Revisit when: Room declarations can be shared in commonMain without JVM-only types.

## [2026-10-15] Scope per-database session components behind app-scoped facades
Context: Selecting another database must not leak connections or observers from the previous one.
Decision: A per-database session component is created per selection and held behind app-scoped repository facades, with DatabaseSelection and generation counters app-scoped.
Reason: Consumers keep stable injected repositories while session lifetimes close cleanly; detail owner registrations end with the detail collector, the rest at session close.
Alternatives considered: Passing the database as a factory argument to each repository was rejected for churn in injected consumers.

## [2026-10-15] Enforce module boundaries from generated manifests
Context: SKILL-424 splits the desktop app into core modules, and source-only path checks cannot see Gradle edges, targets or plugins.
Decision: A quality convention plugin writes each module's targets, plugins, KSP configurations and project dependencies to a manifest. Architecture tests read the manifests plus a PSI scan of imports and qualified names against an allowed-edge table.
Reason: Manifests and parsed references catch aliased or star imports and wrong edges that text greps miss. Synthetic rejection fixtures prove each rule fails when broken.
Alternatives considered: Grep-only path tests miss aliased imports; a third-party dependency-rule plugin adds a build dependency for a small ruleset.

## [2026-10-15] Keep core domain free of platform time and file types
Context: Domain models used java.time and java.nio, which blocks a pure multiplatform domain and the guard that bans platform imports.
Decision: Domain uses kotlin.time for instants and durations and a DatabaseFileIdentity value (path, file key) for store identity. Conversion to java types happens only in runtime-facing edges such as the runtime mapper and Room board source.
Reason: The domain stays portable and testable with a fake clock; platform types live where the platform is used.
Revisit when: Adapters still calling Dispatchers.IO directly move behind the injected DispatcherProvider.

## [2026-10-12] Read work through the runtime store
Context: SKILL-423 replaces CLI work-data requests with an in-process view of the runtime-owned database.
Decision: Room opens an existing compatible store read-only. Shared runtime services project lifecycle and identity facts, admit exact-target controls, and own migrations. DataStore stores app preferences.
Reason: Reads must not start commands, create or repair workflow state, or establish a second schema authority. Reusing runtime policy avoids a desktop lifecycle mirror and duplicate runtime graph.
Alternatives considered: CLI fallback would restore executable discovery and output parsing dependencies. Destructive ORM migration and a private workflow copy violate runtime ownership.

## [2026-10-12] Confirm external commits through durable revisions
Context: Runtime writers use independent SQLite sessions. Room's in-process tracker alone cannot establish committed-write observation across desktop processes.
Decision: Register the filesystem watcher before baseline reads. Treat file/WAL events and overflow as hints, then check data version, file identity, and runtime-owned revision topics. Own and close the observer's read-only connection with its session.
Reason: SQLite confirms commit and coherent topic changes. This excludes rollback notifications and recovers committed state after event loss, reconnect, replacement, or writer exit without periodic database polling or a service daemon.

## [2026-10-13] Signal committed work changes outside SQLite
Context: Room's desktop query Flow cannot hear writes from another process's JDBC connection. Watching SQLite and WAL files also opened another database connection and could fail during WAL recovery.
Decision: After a runtime transaction changes the desktop work revision topics, SQLite takes a per-store lock and atomically replaces a small sidecar signal file beside the selected database. The file carries a generation token and the latest topic revisions. The desktop watches only that file and invalidates the matching Room-backed Flow queries. Registration and reconnect start with a fresh baseline. Overflow rereads the latest revisions. Rollbacks and unrelated table commits emit nothing.
Reason: The runtime transaction confirms committed state before publishing. The sidecar contains only topic revision numbers. Room stays read-only and owns no tracking table or trigger.
Supersedes: Confirm external commits through durable revisions (2026-10-12).

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

## [2026-10-13] Reconnect a failed database observer only on explicit refresh
Context: A lost database watcher triggered timed reconnects and full invalidations. Reads also reopened failed sessions, so background failures repeatedly reset the desktop views.
Decision: Keep the observer failure typed and the last successful view stale. Remove the timed reconnect loop and retain failed sessions until explicit refresh or a database change requests a new connection. Keep healthy observation driven by committed database changes and known lifecycle deadlines. Record the observer failure class and provider error code without logging database content.
Reason: Database observation failure must not become periodic data refresh or a successful read with no working observer. Explicit refresh makes recovery visible and preserves a stable stale view between attempts.

## [2026-10-13] Launch runs through runtime admission
Context: Resume and Run again now start work from an explicit desktop action. Reading work still uses Room and shared runtime projections.
Decision: WorkRunActions validates the selected database, execution revision, process owner, recorded branch, and launch arguments before calling WorkRunLauncher. The desktop cannot use this port directly. Cancellation stays in the runtime and preserves history.
Reason: Starting work needs a process boundary. The desktop architecture guard continues to forbid CLI read proxies and process adapters in desktop code. It checks the full graph up to the runtime launch port and limits that port to the admission owner, its infrastructure implementation, and DI.
Superseded by: Let desktop reach runtime services that run git (2026-10-15)

## [2026-10-13] Start the installed agent harness from desktop controls
Context: The operator wants Resume and Run again to start the installed headless agent, such as Claude Code or Codex, rather than start the Skill Bill CLI directly.
Decision: Keep admission and cancellation in the shared runtime. The launch adapter resolves the selected harness locally and uses the existing native command builders with a briefing for the installed skill. It makes no provider API requests. The recorded agent is the default, and the operator can choose another harness. Standalone rows show their start time and short invocation ID so older blocked runs are distinct from newer active runs.
Reason: The installed harness owns authentication and skill execution. The app starts that executable and keeps run identity visible.

## [2026-10-13] Keep retries in history and follow a newly launched attempt
Context: Keyless phase retries used invocation IDs as their group, so older blocked attempts stayed in Needs you. Launch admission also finished before the harness registered its new attempt.
Decision: Group keyless phases by repository, phase, and branch. Show the latest attempt in each sidebar section and retain earlier attempts with their recorded status in the selected phase's activity history. Keep launch controls pending until a new attempt appears, then select it if the operator is still viewing the original entry. A two-minute registration timeout reports a visible problem and diagnostic without polling the database. Confirmed cancellation removes live rows immediately and rejects stale section replays. Explicit Run again can reopen canceled durable work or start another standalone invocation.
Reason: Process startup is only launch admission. It does not mean the selected invocation resumed, and cancellation must not disappear when a slower read finishes.

## [2026-10-13] Cancel the installed harness as well as its phase worker
Context: Canceling a phase worker left its desktop-launched harness alive. A pending harness could then register another attempt. App restarts also released the old process-duration launch locks.
Decision: Save the harness PID, process birth, and optional managed scope in an atomic ownership file for the selected database and work group. Use a transient admission lock for launch, cancellation, and owner cleanup. Refuse duplicates while the saved harness lives. Reserve phase cancellation before stopping its harness and child processes, then settle the canceled record. Canceling an older historical attempt does not stop a newer invocation's harness. Keep both launch and stop behind the runtime admission owners in the desktop call-graph guard.
Reason: Cancel must stop the process that can start more work, and process ownership must survive reopening the app. PID reuse must never turn cancellation into a signal to an unrelated process.


## [2026-10-13] Show one phase entry across the whole sidebar
Context: Showing separate canceled and completed attempts in History repeated the same phase after retries.
Decision: Apply the same latest-attempt grouping to History and Recent as the live sections. A newer active attempt replaces its earlier finished entries in the sidebar. Keep all stored attempts available in the phase detail activity history.
Reason: The sidebar represents work the operator can select. Its activity history holds the individual attempts.


## [2026-10-13] Copy the full goal block reason
Context: Finalization failures kept only a blocked status after the runner exited, so Copy reason copied the generic goal summary.
Decision: Save the runner's full stopped reason in goal control state and project it separately from the bounded status text. Show blocked goals in Goal activity with the existing three-line preview, delayed tooltip, and copy button. Keep the header summary short. Disable copying when an older attempt has no saved full reason and state that absence in Goal activity.
Reason: Clipboard text must contain the recorded failure, and a missing reason must remain visible rather than become a generic status sentence.

## [2026-10-14] Apply supported upgrades when selecting a database
Context: An installed desktop app rejected an older runtime store with an incompatible-database message. Installation upgrades the runtime's default store, but the app can select a different store or open one restored after installation.
Decision: Database selection calls the shared runtime schema port before opening Room or registering observation. The runtime checks the migration ledger, version, and installed Room identity before applying pending migrations. Missing databases stay absent, and newer or unrecognized stores remain unchanged. Ordinary reads and observation retain their read-only behavior.
Reason: An older supported store should open with its saved work intact. Runtime migrations remain the only schema authority, and upgrading belongs to the connection boundary rather than each read.

## [2026-10-14] Head rows with the run, show the branch as it is now
Context: Sidebar rows were headed by the worktree folder name, so a WE-5108 goal running in a `we5116-…` worktree read as WE-5116. Goal detail showed the branch recorded when the execution registered and never followed the run.
Decision: Head each sidebar row with its issue key and move the repository name to the second line. Keyless rows keep the repository name as their heading. Goal detail shows the worktree's checked-out branch at read time and falls back to the recorded branch. Standalone phases keep their recorded branch because it is part of their identity.
Reason: A run is identified by its issue key, not by where it was started. The branch is display context and must be current.

## [2026-10-15] Serialize detail subscriptions across cancelled selections
Context: macOS CI caught overlapping detail reads during rapid reselection. Waiting for the previous selection job lost the original read when an intermediate job was cancelled before its body started.
Decision: Hold a controller-owned mutex for each detail subscription's lifetime. Cancelled intermediate selections cannot release the active subscription's lock. Inject the controller dispatcher and use a test scheduler to hold the first read through cancellation while two reselections and refresh run.
Reason: Cancellation requests do not prove a read has finished. The regression must prove the next subscription waits for the active read without depending on thread timing.

## [2026-10-16] Resume an existing goal with the installed runtime, not an agent session
Context: Resume for a paused goal started a headless `claude --print` session that ran `skill-bill goal <KEY>` as a foreground Bash tool call. The Bash tool stops foreground commands after 10 minutes, so the runner died during goal-finish. Company permission policy also turned `--dangerously-skip-permissions` into a no-op, so the session could not recover without prompts nobody could answer.
Decision: `WorkRunLaunchRequest` carries a sealed `WorkRunLaunchKind`. An existing goal (no standalone phase record) resumes as `RuntimeCommand`: the launcher starts the installed `skill-bill` from the user's local bin directory or PATH with `goal <KEY> --agent <agent> --repo-root <root> --no-live-output`, or `phase plan <KEY> --agent <agent>` for a durable plan workflow, with no stdin. Admission, ownership, logs, startup failure detection, systemd scoping, the database variable, and the recorded agent home stay the same. A missing `skill-bill` refuses the launch. Standalone phases and goals launched from a recorded raw intake keep the `AgentHarness` path, because the agent may need to rehydrate the tracker issue.
Reason: The runtime already launches child agents itself. Starting it directly removes the Bash time limit and the outer permission surface, and a resume needs no tracker access.
Supersedes in part: Start the installed agent harness from desktop controls (2026-10-13)
