package dev.skillbill.tray.lifetime

import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import dev.skillbill.tray.core.ui.navigation.DatabaseGenerationRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the database session the windows read from stands. */
sealed interface SessionState {
  /** A session is open at [ref]; routes made for it, and only those, are valid. */
  data class Active(val ref: DatabaseGenerationRef) : SessionState

  /** The session is being replaced: nothing that names work may render or be opened until the next [Active]. */
  data object Transitioning : SessionState
}

/**
 * Owns the one transition between database sessions: a selection of another path, of the same path again, or a replaced file
 * found under the open connection. Before the old session is touched, and so before any new-session render, every listener is
 * told to drop what belongs to it (routes, results, cursors, selection and the notification baseline) with `null`; once the new
 * session is open the listeners rebuild from its generation and the state becomes [SessionState.Active].
 *
 * Switches run one at a time; a second one waits for the first to settle.
 */
class SessionTransitionCoordinator(
  scope: CoroutineScope,
  private val storeSession: WorkStoreSession,
  private val ui: CoroutineDispatcher,
) {
  private val mutable = MutableStateFlow<SessionState>(SessionState.Active(DatabaseGenerationRef(storeSession.generation.value)))
  private val listeners = mutableListOf<(DatabaseGenerationRef?) -> Unit>()
  private val lock = Mutex()

  val state: StateFlow<SessionState> = mutable.asStateFlow()

  /** The generation of the open session, or null while one is being replaced. */
  val current: DatabaseGenerationRef? get() = (mutable.value as? SessionState.Active)?.ref

  init {
    // A file replaced under the open connection advances the generation without a selection; it is a transition too.
    scope.launch {
      storeSession.generation.collect { generation ->
        val active = mutable.value as? SessionState.Active
        if (active != null && active.ref.generation != generation) {
          begin()
          settle()
        }
      }
    }
  }

  /** [listener] is called with null when a session starts being replaced, and with the new generation once it is open. */
  fun onReset(listener: (DatabaseGenerationRef?) -> Unit) {
    listeners += listener
  }

  /**
   * Replaces the session with the one at [path]; null selects the default database. The caller may be on any thread: the window
   * state is only touched on the UI dispatcher, and the new generation is published there even when the selection is cancelled.
   */
  suspend fun switchTo(path: String?) {
    lock.withLock {
      withContext(ui) { begin() }
      try {
        storeSession.selectDatabase(path)
      } finally {
        withContext(ui + NonCancellable) { settle() }
      }
    }
  }

  private fun begin() {
    mutable.value = SessionState.Transitioning
    listeners.forEach { it(null) }
  }

  private fun settle() {
    val ref = DatabaseGenerationRef(storeSession.generation.value)
    listeners.forEach { it(ref) }
    mutable.value = SessionState.Active(ref)
  }
}
