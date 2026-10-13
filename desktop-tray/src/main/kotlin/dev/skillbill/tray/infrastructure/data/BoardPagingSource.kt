package dev.skillbill.tray.infrastructure.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.domain.Board
import dev.skillbill.tray.domain.Problem

internal class BoardPageFailure(val problem: Problem) : Exception(problem.summary)

internal class BoardPagingSource(
  private val query: suspend (String?, Boolean) -> Outcome<Board>,
) : PagingSource<String, Board>() {
  override suspend fun load(params: LoadParams<String>): LoadResult<String, Board> {
    if (invalid || params is LoadParams.Prepend) return LoadResult.Invalid()
    return when (val result = query(params.key, params is LoadParams.Refresh)) {
      is Outcome.Failure -> LoadResult.Error(BoardPageFailure(result.problem))
      is Outcome.Value -> if (invalid && params !is LoadParams.Refresh) LoadResult.Invalid() else LoadResult.Page(
        data = listOf(result.value), prevKey = params.key, nextKey = result.value.nextCursor)
    }
  }

  override fun getRefreshKey(state: PagingState<String, Board>): String? =
    state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey }
}
