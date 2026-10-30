package dev.skillbill.desktop.core.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * One database session's selection. [databasePath] null means the runtime default. Every session-scoped observation runs
 * in [scope], which the session manager cancels and joins before it releases the graph.
 */
class SessionSelection(
  val id: Long,
  val databasePath: String?,
  val claudeConfigDir: String?,
  val scope: CoroutineScope,
)

/** The runtime graph a session owns, seen without runtime types so the switching logic can be tested with a fake. */
interface SessionGraph {
  /** The resolved database path when it resolves, the raw setting otherwise. */
  fun displayPath(): String

  /** Upgrades an existing store, probes it and starts the signal watcher; returns the watcher's job and throws on failure. */
  fun open(): Job

  /** Closes the runtime's kept read connections. Safe to call more than once. */
  fun release()
}

fun interface SessionGraphFactory<G : SessionGraph> {
  fun create(selection: SessionSelection): G
}
