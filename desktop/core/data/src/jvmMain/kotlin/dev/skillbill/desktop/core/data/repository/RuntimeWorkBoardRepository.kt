package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The app-scoped board: it follows the database session and delegates to that session's shared observations. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeWorkBoardRepository(private val sessions: DatabaseSessionManager) : WorkBoardRepository {
  override fun observe(query: BoardQuery): Flow<BoardState> = sessions.sessionFlow(
    opening = BoardState.Loading,
    failed = { BoardState.Failed(ReadProblem.Storage(it)) },
  ) { it.board.observe(query.clamped()) }

  override suspend fun loadMore(query: BoardQuery, cursor: String): LoadMoreOutcome =
    sessions.sessions.value?.board?.loadMore(query.clamped(), cursor) ?: LoadMoreOutcome.End

  override fun refresh() {
    sessions.sessions.value?.board?.refresh()
  }

  private fun BoardQuery.clamped() = copy(pageSize = pageSize.coerceIn(1, WorkPaginationLimits.MAX_PAGE_SIZE))
}
