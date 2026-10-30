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

