package dev.skillbill.desktop.core.domain.session

import kotlinx.coroutines.flow.StateFlow

/** The selected database's session. */
sealed interface DatabaseSessionState {
  /** [path] is the path setting being opened; null means the runtime default, which resolves only once the graph is built. */
  data class Opening(val path: String?) : DatabaseSessionState

  data class Open(val path: String, val sessionId: Long) : DatabaseSessionState

  data class Failed(val path: String, val failure: StorageFailure) : DatabaseSessionState
}

enum class StorageFailure { Missing, Access, Busy, Corrupt, IncompatibleSchema, IncompatibleRecord }

/** What features observe of the database session without depending on the data layer. */
interface DatabaseSessionController {
  val state: StateFlow<DatabaseSessionState>

  /** Rebuilds the session for the current selection, for example after the user fixed a missing database. */
  fun reopen()
}
