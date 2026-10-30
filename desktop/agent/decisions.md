## [2026-10-30] Require equal read_schema in the desktop runtime gate
Context: desktop-tray's gate accepts an installed runtime with the same commit but a different read_schema.
Decision: InstalledRuntimeBuildGate returns Available only when both identity records parse, describe the same build, carry equal read_schema and report a supported compatibility. Every refusal folds into NotInstalled, Unreadable or Mismatched with the reinstall command.
Reason: A same-commit runtime at another read_schema reads a different database shape and must be refused as Mismatched. The refusal panel distinguishes only those three problems.

## [2026-10-30] Make desktop check build the distribution
Context: In desktop-tray, verifyPackagedRuntimeFree runs only as a finalizer of createDistributable, so check never inspects the image.
Decision: In desktop/, check depends on verifyPackagedRuntimeFree as well as the shared-runtime version check. This is the one intended divergence from desktop-tray's wiring.
Reason: The validate gate runs check alone, and a dependency that drags a dev.skillbill runtime jar into the image must fail that gate.

## [2026-10-30] Pin shared runtime libraries with dependency constraints
Context: Compose lifecycle pulls kotlinx-serialization 1.7.3, and pty4j with jediterm pull newer jetbrains annotations and slf4j-api than the runtime uses, so verifySharedRuntimeVersions failed.
Decision: :app constrains kotlinx-serialization-core to the catalog version. feature/terminal pins annotations strictly and constrains slf4j-api to the runtime's versions. The version check stays unchanged.
Reason: The image excludes these libraries and the installed runtime supplies them at run time, so the desktop side must resolve the runtime's versions. Loosening the check would hide the next drift.
Alternatives considered: Excluding the transitive modules, which pty4j and jediterm still need at run time.

## [2026-10-30] Put the session id in every route's content key
Context: A database switch must drop every session-scoped view model and observation without closing the window or its terminal shells.
Decision: RouteEntry content keys are the route's entry key plus the session id, with a null session for Settings. A new or closed session resets the stack to Work, keeps Settings when it is on top and clears the selection.
Reason: New content keys clear each old entry's ViewModelStore without a separate entry scope. Terminal shells live outside NavDisplay, so they survive the switch.

## [2026-10-30] Fail a terminal tab whose directory is missing
Context: desktop-tray started the shell in home when the requested directory was gone, which looked like success.
Decision: PtyShellLauncher keeps the requested directory and returns Failed with a Directory not found message. The workspace shows one toast and removes the tab. Only a request without a directory falls back to the latest selection's root, then home.
Reason: A shell in home hides a deleted worktree or moved checkout.
Alternatives considered: An inline Retry in the pane, a second failure path for a rare case.

## [2026-10-30] Page detail lists by the runtime's per-list cursor
Context: The spec named the paging token an anchor, but in WorkDetailPagePosition an anchor marks where the current page started and only the cursor addresses the next page.
Decision: DetailSections.Loaded exposes listCursors. WorkDetailRepository.loadList keeps its anchor parameter name, documented as the token from listCursors, and data sends it as the cursor. A refresh re-reads each paged list from its head at its loaded size.
Reason: Sending the anchor would re-read the current page instead of the next one.

## [2026-10-30] Refresh failed spec snapshots explicitly
Context: The shared spec tree flow can keep an unavailable result after its first snapshot read, so resubscribing cannot implement Retry.
Decision: SpecRepository.refreshTree sends a per-project refresh signal into the session's tree observation.
Reason: Retry needs a fresh snapshot without polling and without changing who owns the directory watcher and commit signals.

## [2026-10-30] Carry runtime completion and QA facts for notifications
Context: Transition notifications need completion source, standalone execution and QA outcome, which WorkItem previously dropped.
Decision: Map those facts from runtime board records into WorkItem and keep notification classification in the app. Native tray and notification services stay in the app; the tray feature uses core models only.
Reason: The facts separate goal completion, standalone phase completion and incomplete QA without guessing from a terminal state.

## [2026-10-30] Map unknown subtask statuses to Pending
Context: The runtime can add a subtask status before the desktop recognizes it.
Decision: StatusKindMapping in core/ui maps any unrecognized subtask status string to Pending.
Reason: A rendering helper must not crash the board on a new status. core/ui has no logger, so the fallback records no diagnostic.

## [2026-10-30] Keep desktop preferences in DataStore without a Room database
Context: The new app needs preferences, but its runtime data remains owned by runtime services and its terminal tabs live in memory.
Decision: Store preferences in core:datastore and defer a Room database until the app has structured data of its own.
Reason: Adding another persistence model now would introduce behavior outside the handoff's scope.

## [2026-10-30] Keep the new desktop app beside the shipped tray
Context: The handoff leaves the choice of replacing desktop-tray in the installer open.
Decision: Build the new app as a standalone desktop build while the installer and release assets continue to ship desktop-tray.
Reason: Switching the shipped app requires an operator decision.

## [2026-10-30] Let settings own contributed page routing
Context: Multiple features contribute settings pages, while the navigation host selects one entry for a route.
Decision: Settings owns SettingsRoute and dispatches page bodies to their contributing FeatureEntry. Page selection remains local to Settings.
Reason: Route ownership must not depend on set iteration order, and Back and Esc should leave Settings in one step.

## [2026-10-30] Use one class loader for runtime refusal and retry
Context: The unavailable panel must retry the runtime gate without restarting the process.
Decision: Load the refusal panel and the app in one app class loader, adding runtime jars after a successful retry. The refusal entry avoids types absent before that retry.
Reason: Skiko binds its native library to the first class loader that loads it, so a bootstrap-loaded panel prevents an in-process retry.

## [2026-10-30] Close the old database session before opening another
Context: A database switch must stop old observations and avoid concurrent graphs holding read connections.
Decision: Serialize session changes, cancel and join the old session, release its reads, then open the new graph. Window-scoped terminal shells remain alive.
Reason: Joining before release prevents an old read from reopening connections and prevents stale observations reaching the new session.

## [2026-10-30] Follow runtime board placement and schedule deadline refreshes
Context: Membership and freshness may change without a database commit, and the runtime owns board placement.
Decision: Use runtime liveSection and order for board sections. Schedule one refresh at the earliest deadline strictly after observedAt, replacing it after each read.
Reason: Client placement would duplicate policy, while excluding past deadlines prevents an immediate refresh loop.

## [2026-10-30] Keep terminal control keys with the shell
Context: Compose does not receive keys while the Swing terminal has focus, and terminal control keys have shell meanings.
Decision: Handle only Ctrl+Backquote and Ctrl+Shift+Backquote over the terminal; send other keys to the PTY.
Reason: Taking Ctrl+K or Ctrl+I would break shell editing and Tab.

## [2026-10-30] Register login items before saving preferences
Context: OS registration or the following preference write can fail.
Decision: Apply the OS change first, persist after success, and attempt compensation if persistence fails. Report possible divergence if compensation also fails.
Reason: The saved toggle must not claim a registration the OS refused. The desktop uses its own Linux autostart entry to avoid overwriting the tray's entry.

## [2026-10-30] Isolate desktop benchmark data and execution
Context: Benchmark runs must not alter a supplied store or the user's settings, and routine checks should not execute timed workloads.
Decision: Snapshot supplied databases through a read-only SQLite connection, use a temporary home and an opt-in task, and use fresh class loaders for cold-start samples.
Reason: The snapshot includes WAL changes without writing the source; the temporary home isolates preferences.

