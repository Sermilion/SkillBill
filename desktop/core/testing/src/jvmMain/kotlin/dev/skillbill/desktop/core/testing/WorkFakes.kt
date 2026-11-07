package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailListOutcome
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.LoadMoreOutcome
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.ExportPreflightOutcome
import dev.skillbill.desktop.core.domain.model.ExportOutcome
import dev.skillbill.desktop.core.domain.model.ArchiveInspectOutcome
import dev.skillbill.desktop.core.domain.model.ImportOutcome
import dev.skillbill.desktop.core.domain.model.NewTaskStart
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardDismissOutcome
import dev.skillbill.desktop.core.domain.model.OperationCardLookup
import dev.skillbill.desktop.core.domain.model.OperationCardView
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.domain.repository.ArchiveFileChooser
import dev.skillbill.desktop.core.domain.repository.DesktopClipboard
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailResolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** One shared board flow for every query; records the queries observed and counts refreshes and page loads. */
class FakeWorkBoardRepository(initial: BoardState = BoardState.Loading) : WorkBoardRepository {
  val state = MutableStateFlow(initial)
  private val refreshVersion = MutableStateFlow(0L)
  val queries = mutableListOf<BoardQuery>()
  var refreshes = 0
    private set
  val loadMoreCursors = mutableListOf<String>()
  var loadMoreOutcome: LoadMoreOutcome = LoadMoreOutcome.End

  override fun observe(query: BoardQuery): Flow<BoardState> {
    queries += query
    return state
  }

  override fun observeRefreshes(query: BoardQuery): Flow<Unit> = refreshVersion.map { Unit }

  override suspend fun loadMore(query: BoardQuery, cursor: String): LoadMoreOutcome {
    loadMoreCursors += cursor
    return loadMoreOutcome
  }

  override fun refresh() {
    refreshes++
    refreshVersion.value++
  }
}

class FakeArchiveFileChooser : ArchiveFileChooser {
  var nextSave: String? = null
  var nextOpen: String? = null
  val saveNames = mutableListOf<String>()
  var openRequests = 0
  override fun saveArchive(suggestedName: String, onChosen: (String?) -> Unit) {
    saveNames += suggestedName
    onChosen(nextSave)
  }
  override fun openArchive(onChosen: (String?) -> Unit) {
    openRequests++
    onChosen(nextOpen)
  }
}

class FakeDesktopClipboard : DesktopClipboard {
  val copied = mutableListOf<String>()
  override fun copy(text: String) { copied += text }
}

/** Returns the configured [runOptions] per id from [prefetch] and records each prefetched id. */
class FakeWorkDetailRepository(var runOptions: Map<String, RunOptions> = emptyMap()) : WorkDetailRepository {
  val prefetched = mutableListOf<String>()
  var details: Map<String, Flow<DetailState>> = emptyMap()
  var favoriteResolver: suspend (String) -> WorkDetailResolution = { WorkDetailResolution.Missing }

  override fun observe(item: WorkItem): Flow<DetailState> = details[item.id] ?: emptyFlow()

  override suspend fun resolve(id: String): WorkDetailResolution = favoriteResolver(id)

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
  val newTaskRequests = mutableListOf<NewTaskStart>()
  var newTaskOutcome: NewTaskStartOutcome = NewTaskStartOutcome.Refused("unused")
  val operationCards = mutableMapOf<String, OperationCardView>()
  val dismissedCards = mutableListOf<String>()
  var dismissOutcome: OperationCardDismissOutcome = OperationCardDismissOutcome.Dismissed
  var exportPreflightOutcome: ExportPreflightOutcome = ExportPreflightOutcome.Refused("unused")
  var archiveInspectOutcome: ArchiveInspectOutcome = ArchiveInspectOutcome.Refused("unused")
  var exportOutcome: ExportOutcome = ExportOutcome.Refused("unused")
  var importOutcome: ImportOutcome = ImportOutcome.Refused("unused")
  val preflightRequests = mutableListOf<Pair<String, String?>>()
  val exportRequests = mutableListOf<Triple<String, String?, String>>()
  val inspectRequests = mutableListOf<String>()
  val importRequests = mutableListOf<String>()
  val importDetails = mutableListOf<Triple<String, String?, String>>()

  override suspend fun pauseAfterSubtask(item: WorkItem) = record(ControlCall("pauseAfterSubtask", item))

  override suspend fun pauseNow(item: WorkItem) = record(ControlCall("pauseNow", item))

  override suspend fun cancel(item: WorkItem) = record(ControlCall("cancel", item))

  override suspend fun launch(item: WorkItem, agentId: String?) = record(ControlCall("launch", item, agentId))

  override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome = notFaked("startGoal")

  override suspend fun startNewTask(request: NewTaskStart): NewTaskStartOutcome {
    newTaskRequests += request
    return newTaskOutcome
  }

  override suspend fun operationCardForLaunch(launchId: String): OperationCardView? = operationCards[launchId]

  override suspend fun operationCard(token: String): OperationCardView? = operationCards.values.firstOrNull { it.token == token }

  override suspend fun revalidateOperationCard(token: String): OperationCardLookup =
    operationCard(token)?.let(OperationCardLookup::Present) ?: OperationCardLookup.Missing

  override suspend fun dismissOperationCard(token: String): OperationCardDismissOutcome {
    dismissedCards += token
    return dismissOutcome
  }

  override suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome =
    notFaked("recoveryPreview")

  override suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome = notFaked("recover")

  override suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome = notFaked("purge")

  override suspend fun preflightExport(issueKey: String, repoRoot: String?): ExportPreflightOutcome {
    preflightRequests += issueKey to repoRoot
    return exportPreflightOutcome
  }

  override suspend fun export(issueKey: String, repoRoot: String?, outputPath: String): ExportOutcome {
    exportRequests += Triple(issueKey, repoRoot, outputPath)
    return exportOutcome
  }

  override suspend fun inspectArchive(archivePath: String, repoRoot: String?): ArchiveInspectOutcome {
    inspectRequests += archivePath
    return archiveInspectOutcome
  }

  override suspend fun import(archivePath: String, repoRoot: String?, fingerprint: String): ImportOutcome {
    importRequests += archivePath
    importDetails += Triple(archivePath, repoRoot, fingerprint)
    return importOutcome
  }

  private fun record(call: ControlCall): ControlOutcome {
    calls += call
    return outcome
  }

  private fun notFaked(name: String): Nothing = error("FakeWorkControlRepository does not fake $name")
}
