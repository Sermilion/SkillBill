package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/** One shared board flow for every query; records the queries observed and counts refreshes and page loads. */
class FakeWorkBoardRepository(initial: BoardState = BoardState.Loading) : WorkBoardRepository {
  val state = MutableStateFlow(initial)
  val queries = mutableListOf<BoardQuery>()
  var refreshes = 0
    private set
  val loadMoreCursors = mutableListOf<String>()
  var loadMoreOutcome: LoadMoreOutcome = LoadMoreOutcome.End

  override fun observe(query: BoardQuery): Flow<BoardState> {
    queries += query
    return state
  }

  override suspend fun loadMore(query: BoardQuery, cursor: String): LoadMoreOutcome {
    loadMoreCursors += cursor
    return loadMoreOutcome
  }

  override fun refresh() {
    refreshes++
  }
}

/** Returns the configured [runOptions] per id from [prefetch] and records each prefetched id. */
class FakeWorkDetailRepository(var runOptions: Map<String, RunOptions> = emptyMap()) : WorkDetailRepository {
  val prefetched = mutableListOf<String>()
  var details: Map<String, Flow<DetailState>> = emptyMap()

  override fun observe(item: WorkItem): Flow<DetailState> = details[item.id] ?: emptyFlow()

  override suspend fun refresh(id: String) = Unit

  override suspend fun loadList(id: String, list: DetailList, anchor: String?): DetailListOutcome = DetailListOutcome.End

  override suspend fun prefetch(id: String): RunOptions? {
    prefetched += id
    return runOptions[id]
  }
}

/** One recorded run control: its name and the item it ran on. */
data class ControlCall(val control: String, val item: WorkItem, val agentId: String? = null)

/** Records each run control with its item and answers [outcome]; the goal writes are not faked and fail loudly. */
class FakeWorkControlRepository(var outcome: ControlOutcome = ControlOutcome.IntentRecorded) : WorkControlRepository {
  override val reservations = MutableStateFlow<Set<String>>(emptySet())
  val calls = mutableListOf<ControlCall>()

  override suspend fun pauseAfterSubtask(item: WorkItem) = record(ControlCall("pauseAfterSubtask", item))

  override suspend fun pauseNow(item: WorkItem) = record(ControlCall("pauseNow", item))

  override suspend fun cancel(item: WorkItem) = record(ControlCall("cancel", item))

  override suspend fun launch(item: WorkItem, agentId: String?) = record(ControlCall("launch", item, agentId))

  override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome = notFaked("startGoal")

  override suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome =
    notFaked("recoveryPreview")

  override suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome = notFaked("recover")

  override suspend fun recordOperatorDecision(item: WorkItem, decision: OperatorDecision, instructions: String?): DecisionOutcome =
    notFaked("recordOperatorDecision")

  override suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome = notFaked("purge")

  private fun record(call: ControlCall): ControlOutcome {
    calls += call
    return outcome
  }

  private fun notFaked(name: String): Nothing = error("FakeWorkControlRepository does not fake $name")
}
