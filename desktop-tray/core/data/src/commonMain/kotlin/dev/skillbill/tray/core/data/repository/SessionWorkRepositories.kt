package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.data.session.SessionSlot
import dev.skillbill.tray.core.data.session.WorkStoreSessionManager
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.BoardRetention
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.IncidentDetailUnavailable
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreviewResult
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreStatusRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

// The repositories the UI injects are app-scoped facades: they forward to whichever database session is active, so a
// consumer built once keeps working across database selections. No session state lives here.

private fun <T> noDatabase(): Outcome<T> =
  Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "No database is selected.", StorageFailure.ACCESS))

private fun runtimeRefusal(problem: RuntimeBuildProblem): Outcome.Failure = Outcome.Failure(
  Problem(
    ProblemCode.RUNTIME_BUILD_MISMATCH,
    if (problem.reason == RuntimeBuildReason.INCIDENT_DETAIL_UNAVAILABLE) IncidentDetailUnavailable.MESSAGE
    else "The installed Skill Bill runtime is not the build this app was packaged with.",
    runtimeBuild = problem,
  ),
)

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SessionWorkBoardRepository(private val manager: WorkStoreSessionManager) : WorkBoardRepository {
  override val refreshEvents: Flow<Set<WorkSection>> = merge(
    manager.switched,
    manager.slot.flatMapLatest { slot -> if (slot is SessionSlot.Active) slot.parts.board.refreshEvents else emptyFlow() },
  )

  override fun observe(
    section: WorkSection,
    retention: Retention,
    filter: String,
    historyFilter: HistoryFilter,
    anchor: String?,
  ): Flow<Outcome<Board>> = manager.slot.flatMapLatest { slot ->
    if (slot is SessionSlot.Active) slot.parts.board.observe(section, retention, filter, historyFilter, anchor) else emptyFlow()
  }

  override suspend fun board(
    section: WorkSection,
    retention: Retention,
    cursor: String?,
    filter: String,
    historyFilter: HistoryFilter,
  ): Outcome<Board> = manager.settledParts()?.board?.board(section, retention, cursor, filter, historyFilter) ?: noDatabase()

  override fun retainPages(retention: BoardRetention) {
    (manager.slot.value as? SessionSlot.Active)?.parts?.board?.retainPages(retention)
  }

  override suspend fun refresh() {
    manager.settledParts()?.board?.refresh()
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SessionWorkDetailRepository(private val manager: WorkStoreSessionManager) : WorkDetailRepository {
  override fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>> = manager.slot.flatMapLatest { slot ->
    when (slot) {
      is SessionSlot.Active -> slot.parts.detail.observeDetail(identity)
      is SessionSlot.Refused -> flowOf(runtimeRefusal(slot.problem))
      SessionSlot.Idle, SessionSlot.Switching -> emptyFlow()
    }
  }

  override suspend fun detail(
    identity: WorkIdentity,
    cursor: String?,
    list: DetailList?,
    anchor: String?,
  ): Outcome<Detail> {
    val parts = manager.settledParts()
    if (parts != null) return parts.detail.detail(identity, cursor, list, anchor)
    return manager.settledRefusal()?.let(::runtimeRefusal) ?: noDatabase()
  }
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SessionWorkStoreStatusRepository(manager: WorkStoreSessionManager) : WorkStoreStatusRepository {
  // The session binds its path before the manager publishes the slot, so it is final while the slot is Active.
  override val effectiveDatabasePath: Flow<String?> =
    manager.slot.map { slot -> (slot as? SessionSlot.Active)?.parts?.session?.path }.distinctUntilChanged()
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SessionWorkControlRepository(private val manager: WorkStoreSessionManager) : WorkControlRepository {
  override suspend fun pause(item: WorkItem): Outcome<ControlConfirmation> =
    manager.settledParts()?.control?.pause(item) ?: noDatabase()

  override suspend fun stop(item: WorkItem): Outcome<ControlConfirmation> =
    manager.settledParts()?.control?.stop(item) ?: noDatabase()

  override suspend fun cancel(item: WorkItem): Outcome<Unit> = manager.settledParts()?.control?.cancel(item) ?: noDatabase()

  override suspend fun launch(item: WorkItem, agentId: String?): Outcome<Unit> =
    manager.settledParts()?.control?.launch(item, agentId) ?: noDatabase()

  override suspend fun recover(
    item: WorkItem,
    incidentToken: String,
    revision: Long,
    confirmedPrerequisites: Set<String>,
  ): Outcome<Unit> {
    val parts = manager.settledParts() ?: return noDatabase()
    return parts.control.recover(item, incidentToken, revision, confirmedPrerequisites)
  }

  override suspend fun recoveryPreview(
    item: WorkItem,
    incidentToken: String,
    confirmedPrerequisites: Set<String>,
  ): Outcome<IncidentRecoveryPreviewResult> {
    val parts = manager.settledParts() ?: return noDatabase()
    return parts.control.recoveryPreview(item, incidentToken, confirmedPrerequisites)
  }
}
