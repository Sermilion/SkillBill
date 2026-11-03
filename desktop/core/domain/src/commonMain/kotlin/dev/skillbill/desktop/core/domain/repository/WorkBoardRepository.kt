package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import kotlinx.coroutines.flow.Flow

/** The board as the runtime reads it. Every owner of one [BoardQuery] shares one observation and one read per change. */
interface WorkBoardRepository {
  fun observe(query: BoardQuery): Flow<BoardState>

  /** Emits when the session or query inputs can change detail headers, even if the board state stays equal. */
  fun observeRefreshes(query: BoardQuery): Flow<Unit>

  /** Reads the page after [cursor] and appends it to [query]'s observation. */
  suspend fun loadMore(query: BoardQuery, cursor: String): LoadMoreOutcome

  /** Re-queries every live observation, for the Refresh button and command. */
  fun refresh()
}
