package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.domain.model.WorkDisplayGroup
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import dev.skillbill.desktop.core.domain.session.StorageFailure
import skillbill.di.core.RuntimeComponent
import skillbill.idestatus.model.IdeStatusBoardEntry
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusLiveSection
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.idestatus.model.IdeStatusTrayStatus
import skillbill.idestatus.model.IdeStatusWorkPolicy
import skillbill.ports.idestatus.model.WorkBoardRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

/** Mapped board rows from one or more pages, with the runtime's observation time and the cursor after the last page. */
internal data class BoardPage(
  val items: List<WorkItem>,
  val nextCursor: String?,
  val observedAt: Instant,
  val observation: skillbill.ports.work.model.WorkStoreObservation? = null,
)

/** The board reads an observation needs, behind an interface so its triggering can be tested without a runtime. */
internal interface BoardReads {
  /** Pages 1 to [pageCount] in one read; a failure after page 1 keeps the pages already read and ends the cursor. */
  suspend fun pages(query: BoardQuery, pageCount: Int): Outcome<BoardPage, ReadProblem>

  suspend fun complete(query: BoardQuery): Outcome<BoardPage, ReadProblem> = pages(query, Int.MAX_VALUE)

  suspend fun page(query: BoardQuery, cursor: String): Outcome<BoardPage, ReadProblem>

  /** The runtime's counts for [items], from `IdeStatusPolicy.arrange` as `RuntimeWorkPolicyRepository` computes them. */
  fun counts(items: List<WorkItem>): BoardCounts
}

internal class RuntimeBoardReads(
  private val reads: RuntimeReads,
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
  private val sessionId: Long,
) : BoardReads {
  override suspend fun pages(query: BoardQuery, pageCount: Int): Outcome<BoardPage, ReadProblem> {
    return when (val read = readOrProblem(runtime, diagnostics) { readPages(query, pageCount) }) {
      is Outcome.Failure -> read
      is Outcome.Success -> read.value?.let { Outcome.Success(it) } ?: Outcome.Failure(ReadProblem.Storage(StorageFailure.Missing))
    }
  }

  override suspend fun complete(query: BoardQuery): Outcome<BoardPage, ReadProblem> =
    when (val read = readOrProblem(runtime, diagnostics) { readComplete(query) }) {
      is Outcome.Failure -> read
      is Outcome.Success -> read.value?.let { Outcome.Success(it) } ?: Outcome.Failure(ReadProblem.Storage(StorageFailure.Missing))
    }

  private suspend fun readComplete(query: BoardQuery): BoardPage? = reads.read("board_reservations") { component ->
    val first = component.ideStatusService.board(query.request(cursor = null))
    if (first.absentDatabase) return@read null
    checkNotNull(first.observation) { "The complete board read has no store observation." }
    val items = first.items.mapTo(mutableListOf()) { it.toWorkItem(sessionId) }
    var cursor = first.nextCursor
    while (cursor != null) {
      try {
        val next = component.ideStatusService.board(query.request(cursor))
        if (next.observation == null || next.observation != first.observation) {
          throw IllegalStateException("The board changed while its complete snapshot was being read.")
        }
        next.items.mapTo(items) { it.toWorkItem(sessionId) }
        cursor = next.nextCursor
      } catch (failure: CancellationException) {
        throw failure
      } catch (failure: Exception) {
        diagnostics.record("desktop_board_page_failed")
        throw failure
      }
    }
    BoardPage(items, null, first.observedAt.toKotlinInstant(), first.observation)
  }

  /** Null when the runtime reports the database absent. */
  private suspend fun readPages(query: BoardQuery, pageCount: Int): BoardPage? =
    reads.read("board") { component ->
      val first = component.ideStatusService.board(query.request(cursor = null))
      if (first.absentDatabase) return@read null
      val items = first.items.mapTo(mutableListOf()) { it.toWorkItem(sessionId) }
      var cursor = first.nextCursor
      var pagesRead = 1
      while (pagesRead < pageCount && cursor != null) {
        cursor = try {
          val next = component.ideStatusService.board(query.request(cursor))
          next.items.mapTo(items) { it.toWorkItem(sessionId) }
          next.nextCursor
        } catch (failure: CancellationException) {
          throw failure
        } catch (_: Exception) {
          // The commit that changed the store publishes its own signal, and the next re-query restores the pages.
          diagnostics.record("desktop_board_page_failed")
          null
        }
        pagesRead++
      }
      BoardPage(items, cursor, first.observedAt.toKotlinInstant(), first.observation)
    }

  override suspend fun page(query: BoardQuery, cursor: String) = readOrProblem(runtime, diagnostics) {
    reads.read("board_page") { component ->
      val result = component.ideStatusService.board(query.request(cursor))
      BoardPage(result.items.map { it.toWorkItem(sessionId) }, result.nextCursor, result.observedAt.toKotlinInstant(), result.observation)
    }
  }

  override fun counts(items: List<WorkItem>): BoardCounts {
    val arranged = IdeStatusPolicy.arrange(items.map { it.toEntry() })
    return BoardCounts(
      trayStatus = when (arranged.trayStatus) {
        IdeStatusTrayStatus.FAILED -> TrayStatus.Failed
        IdeStatusTrayStatus.NEEDS_YOU -> TrayStatus.NeedsYou
        IdeStatusTrayStatus.RUNNING -> TrayStatus.Running
        IdeStatusTrayStatus.IDLE -> TrayStatus.Idle
      },
      running = arranged.running,
      needsYou = arranged.needsYou,
      doneWithinDay = arranged.doneWithinDay,
      goals = arranged.goals.size,
      livePhases = arranged.livePhases.size,
      finishedPhases = arranged.finishedPhases.size,
      liveOperations = arranged.liveOperations.size,
      finishedOperations = arranged.finishedOperations.size,
    )
  }
}

private fun BoardQuery.request(cursor: String?) =
  WorkBoardRequest(window.toWorkBoardWindow(), pageSize = pageSize, cursor = cursor, filter = filter.ifBlank { null })

private fun WorkItem.toEntry() = IdeStatusBoardEntry(
  key = id,
  repositoryIdentity = control.repositoryIdentity,
  lifecycle = when (lifecycle) {
    Lifecycle.Active -> IdeStatusLifecycleState.ACTIVE
    Lifecycle.Paused -> IdeStatusLifecycleState.PAUSED
    Lifecycle.Blocked -> IdeStatusLifecycleState.BLOCKED
    Lifecycle.Failed -> IdeStatusLifecycleState.FAILED
    Lifecycle.Terminal -> IdeStatusLifecycleState.TERMINAL
    Lifecycle.Canceled -> IdeStatusLifecycleState.CANCELED
    Lifecycle.Idle -> IdeStatusLifecycleState.IDLE
  },
  policy = IdeStatusWorkPolicy(
    liveSection = when (liveSection) {
      WorkLiveSection.NeedsYou -> IdeStatusLiveSection.NEEDS_YOU
      WorkLiveSection.Goals -> IdeStatusLiveSection.GOALS
      WorkLiveSection.Phases -> IdeStatusLiveSection.PHASES
      WorkLiveSection.Operations -> IdeStatusLiveSection.OPERATIONS
      null -> null
    },
    controlEligible = controlEligible,
    pauseEligible = pauseEligible,
    requiresAttention = requiresAttention,
    displayGroup = when (displayGroup) {
      WorkDisplayGroup.Goals -> IdeStatusDisplayGroup.GOALS
      WorkDisplayGroup.Phases -> IdeStatusDisplayGroup.PHASES
      WorkDisplayGroup.Operations -> IdeStatusDisplayGroup.OPERATIONS
      null -> null
    },
    displayPriority = displayPriority,
    running = running,
    recentlyFinishedPhase = recentlyFinishedPhase,
    doneWithinDay = doneWithinDay,
    membershipDeadline = membershipDeadline?.toJavaInstant(),
  ),
)
