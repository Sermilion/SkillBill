package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.domain.model.StandaloneRelaunch
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.CompletionStage
import kotlin.time.Duration.Companion.seconds

private val START_CONFIRMATION_INTERVAL = 10.seconds

internal val WorkItem.launchKey: String get() = issueKey ?: id

internal sealed interface LaunchBoardTarget {
  data class Item(val id: String, val kind: WorkKind?, val repositoryIdentity: String, val relaunch: StandaloneRelaunch? = null) : LaunchBoardTarget

  data class Goal(val issueKey: String, val repositoryIdentity: String, val workId: String? = null) : LaunchBoardTarget

  data class PlanPhase(val issueKey: String, val repositoryIdentity: String) : LaunchBoardTarget

  data class StandalonePhase(val phaseId: String, val repositoryIdentity: String, val baselineIds: Set<String> = emptySet()) : LaunchBoardTarget
}

internal sealed interface ReservationAdmission {
  data class Reserved(val handle: LaunchReservationHandle) : ReservationAdmission

  data object AlreadyReserved : ReservationAdmission

  data object ChangedSession : ReservationAdmission
}

internal class LaunchReservationHandle internal constructor(val key: String, val sessionId: Long)

@Inject
@SingleIn(AppScope::class)
class LaunchReservations {
  private enum class Phase { InFlight, AwaitingBoard }

  private data class PlanPhaseBaseline(val lifecycle: Lifecycle, val executionId: String?, val runSequence: String?)

  private data class Reservation(
    val handle: LaunchReservationHandle,
    val target: LaunchBoardTarget,
    val baseline: Lifecycle?,
    val baselineExecutionId: String?,
    val baselineRunSequence: String?,
    val baselinePlanRows: Map<String, PlanPhaseBaseline>,
    val baselineObservedAt: kotlin.time.Instant?,
    val phase: Phase,
  )

  private data class Snapshot(val page: BoardPage, val sessionId: Long)

  private val lock = Any()
  private val held = LinkedHashMap<String, Reservation>()
  private val latest = HashMap<Long, Snapshot>()
  private val mutableKeys = MutableStateFlow<Set<String>>(emptySet())
  internal val keys: StateFlow<Set<String>> = mutableKeys.asStateFlow()

  internal fun isReserved(key: String): Boolean = synchronized(lock) { key in held }

  internal fun reserve(
    key: String,
    sessionId: Long,
    target: LaunchBoardTarget,
    baseline: Lifecycle?,
    page: BoardPage,
  ): ReservationAdmission = synchronized(lock) {
    if (key in held) return@synchronized ReservationAdmission.AlreadyReserved
    val handle = LaunchReservationHandle(key, sessionId)
    val snapshot = newest(latest[sessionId], Snapshot(page, sessionId))
    latest[sessionId] = snapshot
    val reservationTarget = if (target is LaunchBoardTarget.StandalonePhase) {
      target.copy(
        baselineIds = snapshot.page.items.filter {
          it.kind == WorkKind.Phase && it.control.phaseId == target.phaseId && it.control.repositoryIdentity == target.repositoryIdentity
        }.mapTo(HashSet()) { it.id },
      )
    } else target
    val selected = reservationTarget.select(snapshot.page.items)
    val boundTarget = when {
      reservationTarget is LaunchBoardTarget.Goal && selected != null -> reservationTarget.copy(workId = selected.id)
      reservationTarget is LaunchBoardTarget.Item && selected?.standaloneExecution == true ->
        reservationTarget.copy(relaunch = StandaloneRelaunch(selected, snapshot.page.items.mapTo(HashSet()) { it.id }))
      else -> reservationTarget
    }
    val selectedBaseline = if (reservationTarget is LaunchBoardTarget.Goal || reservationTarget is LaunchBoardTarget.StandalonePhase) selected?.lifecycle else baseline
    val planRows = if (reservationTarget is LaunchBoardTarget.PlanPhase) {
      snapshot.page.items.filter { reservationTarget.matches(it) }.associate { item ->
        item.id to PlanPhaseBaseline(item.lifecycle, item.control.executionId, item.control.runSequence)
      }
    } else emptyMap()
    held[key] = Reservation(
      handle, boundTarget, selectedBaseline, selected?.control?.executionId, selected?.control?.runSequence, planRows,
      snapshot.page.observedAt, Phase.InFlight,
    )
    publish()
    ReservationAdmission.Reserved(handle)
  }

  internal fun settle(handle: LaunchReservationHandle, succeeded: Boolean) = synchronized(lock) {
    val current = held[handle.key]?.takeIf { it.handle === handle } ?: return@synchronized
    if (!succeeded) {
      held.remove(handle.key)
    } else {
      held[handle.key] = current.copy(phase = Phase.AwaitingBoard)
      latest[handle.sessionId]?.let { settleFromBoard(handle.key, held[handle.key], it) }
    }
    publish()
  }

  /** Rechecks accepted launches even when no board change arrives. Failed reads wait for the next check. */
  internal suspend fun awaitStart(handle: LaunchReservationHandle, refresh: suspend () -> BoardPage?): Boolean {
    while (awaitingBoard(handle)) {
      delay(START_CONFIRMATION_INTERVAL)
      if (!awaitingBoard(handle)) return false
      val page = refresh() ?: continue
      if (releaseUnconfirmed(handle, page)) return true
    }
    return false
  }

  private fun awaitingBoard(handle: LaunchReservationHandle): Boolean = synchronized(lock) {
    held[handle.key]?.let { it.handle === handle && it.phase == Phase.AwaitingBoard } == true
  }

  private fun releaseUnconfirmed(handle: LaunchReservationHandle, page: BoardPage): Boolean = synchronized(lock) {
    val reservation = held[handle.key]?.takeIf { it.handle === handle && it.phase == Phase.AwaitingBoard }
      ?: return@synchronized false
    if (reservation.baselineObservedAt != null && page.observedAt < reservation.baselineObservedAt) return@synchronized false
    settleFromBoard(handle.key, reservation, Snapshot(page, handle.sessionId))
    val unconfirmed = held[handle.key]?.handle === handle
    if (unconfirmed) held.remove(handle.key)
    publish()
    unconfirmed
  }

  internal fun onBoard(sessionId: Long, page: BoardPage) = synchronized(lock) {
    val snapshot = Snapshot(page, sessionId)
    latest[sessionId] = newest(latest[sessionId], snapshot)
    val current = checkNotNull(latest[sessionId])
    held.toMap().forEach { (key, reservation) -> settleFromBoard(key, reservation, current) }
    publish()
  }

  internal fun release(handle: LaunchReservationHandle) = synchronized(lock) {
    if (held[handle.key]?.handle === handle) {
      held.remove(handle.key)
      publish()
    }
  }

  /** A run that exits before registering a board row never settles from the board, so its exit ends the reservation. */
  internal fun releaseOnExit(handle: LaunchReservationHandle, exit: CompletionStage<*>) {
    exit.whenComplete { _, _ -> release(handle) }
  }

  internal fun releaseSession(sessionId: Long) = synchronized(lock) {
    held.entries.removeAll { it.value.handle.sessionId == sessionId }
    latest.remove(sessionId)
    publish()
  }

  private fun settleFromBoard(key: String, reservation: Reservation?, snapshot: Snapshot) {
    if (reservation == null || reservation.phase != Phase.AwaitingBoard || reservation.handle.sessionId != snapshot.sessionId) return
    if (reservation.baselineObservedAt != null && snapshot.page.observedAt < reservation.baselineObservedAt) return
    if (reservation.target is LaunchBoardTarget.PlanPhase) {
      if (snapshot.page.items.any { item ->
          if (!reservation.target.matches(item)) return@any false
          val baseline = reservation.baselinePlanRows[item.id] ?: return@any true
          item.lifecycle != baseline.lifecycle ||
            (item.control.executionId != null && item.control.executionId != baseline.executionId) ||
            (item.control.runSequence != null && item.control.runSequence != baseline.runSequence)
        }
      ) held.remove(key)
      return
    }
    val item = reservation.target.select(snapshot.page.items)
    if (item == null) return
    if (reservation.target is LaunchBoardTarget.Goal && reservation.target.workId == null) {
      held[key] = reservation.copy(target = reservation.target.copy(workId = item.id))
    }
    val executionChanged = item.control.executionId != null && item.control.executionId != reservation.baselineExecutionId
    val runChanged = item.control.runSequence != null && item.control.runSequence != reservation.baselineRunSequence
    if (item.running || item.lifecycle != reservation.baseline || executionChanged || runChanged) held.remove(key)
  }

  private fun LaunchBoardTarget.select(items: List<WorkItem>): WorkItem? = when (this) {
    is LaunchBoardTarget.Item -> relaunch?.successor(items) ?: items.firstOrNull {
      it.id == id && it.kind == kind && it.control.repositoryIdentity == repositoryIdentity
    }
    is LaunchBoardTarget.Goal -> items.singleOrNull {
      it.kind == WorkKind.Goal && it.issueKey == issueKey && it.control.repositoryIdentity == repositoryIdentity &&
        (workId == null || it.id == workId)
    }
    is LaunchBoardTarget.PlanPhase -> null
    is LaunchBoardTarget.StandalonePhase -> items.firstOrNull {
      it.kind == WorkKind.Phase && it.control.phaseId == phaseId && it.control.repositoryIdentity == repositoryIdentity && it.id !in baselineIds
    }
  }

  private fun LaunchBoardTarget.PlanPhase.matches(item: WorkItem): Boolean =
    item.kind == WorkKind.Phase && item.control.phaseId == "plan" && item.issueKey == issueKey &&
      item.control.repositoryIdentity == repositoryIdentity

  private fun newest(first: Snapshot?, second: Snapshot): Snapshot =
    if (first == null || second.page.observedAt >= first.page.observedAt) second else first

  private fun publish() {
    mutableKeys.value = held.keys.toSet()
    val activeSessions = held.values.mapTo(HashSet()) { it.handle.sessionId }
    latest.keys.removeAll { it !in activeSessions }
  }
}
