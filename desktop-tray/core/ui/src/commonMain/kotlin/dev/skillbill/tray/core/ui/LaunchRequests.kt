package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.hasStartedOrFinished
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

class LaunchRequests(
  private val scope: CoroutineScope,
  private val controls: WorkControlRepository,
  private val boards: WorkBoardRepository,
  private val clock: Clock,
) {
  private val mutable = MutableStateFlow<Set<WorkIdentity>>(emptySet())
  val pending = mutable.asStateFlow()

  fun launch(item: WorkItem, agentId: String?): Deferred<Outcome<WorkIdentity?>>? {
    if (!reserve(item.identity)) return null
    return scope.async(start = CoroutineStart.LAZY) {
      val requestedAt = clock.now()
      val knownAttempts = if (item.scope == ExecutionScope.STANDALONE_PHASE) {
        withTimeoutOrNull(5.seconds) { candidates().first() }.orEmpty().map { it.identity }.toSet()
      } else {
        emptySet()
      }
      val result = controls.launch(item, agentId)
      boards.refresh()
      when (result) {
        is Outcome.Failure -> result
        is Outcome.Value -> Outcome.Value(withTimeoutOrNull(120.seconds) {
          candidates().map { items ->
            items.firstOrNull { candidate -> isSuccessor(candidate, item, knownAttempts, requestedAt) }?.identity
          }.filterNotNull().first()
        })
      }
    }.also { request ->
      request.invokeOnCompletion { release(item.identity) }
      request.start()
    }
  }

  fun cancel() {
    scope.cancel()
  }

  suspend fun closeAndJoin() {
    scope.coroutineContext.job.cancelAndJoin()
  }

  private fun reserve(identity: WorkIdentity): Boolean {
    while (true) {
      val current = mutable.value
      if (identity in current) return false
      if (mutable.compareAndSet(current, current + identity)) return true
    }
  }

  private fun release(identity: WorkIdentity) {
    while (true) {
      val current = mutable.value
      if (mutable.compareAndSet(current, current - identity)) return
    }
  }

  private fun candidates(): Flow<List<WorkItem>> = combine(
    boardItems(WorkSection.NEEDS_YOU),
    boardItems(WorkSection.GOALS),
    boardItems(WorkSection.PHASES),
    boardItems(WorkSection.RECENT),
  ) { needsYou, goals, phases, recent ->
    ((needsYou + goals + phases).filter { it.liveSection != null } + recent).distinctBy { it.identity }
  }

  private fun boardItems(section: WorkSection): Flow<List<WorkItem>> =
    boards.observe(section, Retention.DAY).map { (it as? Outcome.Value)?.value?.items.orEmpty() }
}

private fun isSuccessor(candidate: WorkItem, item: WorkItem, knownAttempts: Set<WorkIdentity>, requestedAt: Instant): Boolean {
  if (candidate.identity.repositoryIdentity != item.identity.repositoryIdentity || candidate.branch != item.branch ||
    candidate.phaseId != item.phaseId || candidate.issueKey != item.issueKey
  ) {
    return false
  }
  return if (item.scope == ExecutionScope.STANDALONE_PHASE) {
    candidate.identity !in knownAttempts && candidate.startedAt?.let { started ->
      started >= requestedAt && (item.startedAt?.let { started > it } ?: (candidate.updatedAt > item.updatedAt))
    } == true
  } else {
    candidate.identity == item.identity && candidate.updatedAt > item.updatedAt &&
      (candidate.executionId != item.executionId || candidate.hasStartedOrFinished())
  }
}
