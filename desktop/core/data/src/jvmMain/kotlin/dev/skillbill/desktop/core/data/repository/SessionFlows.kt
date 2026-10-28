package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.data.session.DatabaseSessionComponent
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.session.StorageFailure
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take

/**
 * Follows the database session: [opening] while it opens, [failed] when it failed, and the open session's own flow
 * otherwise. A session change cancels the previous session's flow, and its scope drops everything that session held.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> DatabaseSessionManager.sessionFlow(
  opening: T,
  failed: (StorageFailure) -> T,
  open: (DatabaseSessionComponent) -> Flow<T>,
): Flow<T> = state.flatMapLatest { state ->
  when (state) {
    is DatabaseSessionState.Opening -> flowOf(opening)
    is DatabaseSessionState.Failed -> flowOf(failed(state.failure))
    is DatabaseSessionState.Open ->
      sessions.filterNotNull().filter { it.selection.selection.id == state.sessionId }.take(1).flatMapLatest(open)
  }
}

