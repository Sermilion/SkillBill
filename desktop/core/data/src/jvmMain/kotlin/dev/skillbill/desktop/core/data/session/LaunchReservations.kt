package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

internal val WorkItem.launchKey: String get() = issueKey ?: id

internal sealed interface LaunchBoardTarget {
  data class Item(val id: String, val kind: WorkKind?, val repositoryIdentity: String) : LaunchBoardTarget

  data class Goal(val issueKey: String, val repositoryIdentity: String, val workId: String? = null) : LaunchBoardTarget
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

  private data class Reservation(
    val handle: LaunchReservationHandle,
    val target: LaunchBoardTarget,
    val baseline: Lifecycle?,
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
    val selected = target.select(snapshot.page.items)
    val boundTarget = if (target is LaunchBoardTarget.Goal && selected != null) target.copy(workId = selected.id) else target
    val selectedBaseline = if (target is LaunchBoardTarget.Goal) selected?.lifecycle else baseline
    held[key] = Reservation(handle, boundTarget, selectedBaseline, snapshot.page.observedAt, Phase.InFlight)
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

  internal fun releaseSession(sessionId: Long) = synchronized(lock) {
    held.entries.removeAll { it.value.handle.sessionId == sessionId }
    latest.remove(sessionId)
    publish()
  }

  private fun settleFromBoard(key: String, reservation: Reservation?, snapshot: Snapshot) {
    if (reservation == null || reservation.phase != Phase.AwaitingBoard || reservation.handle.sessionId != snapshot.sessionId) return
    if (reservation.baselineObservedAt != null && snapshot.page.observedAt < reservation.baselineObservedAt) return
    val item = reservation.target.select(snapshot.page.items)
    if (item == null) return
    if (reservation.target is LaunchBoardTarget.Goal && reservation.target.workId == null) {
      held[key] = reservation.copy(target = reservation.target.copy(workId = item.id))
    }
    if (item.running || item.lifecycle != reservation.baseline) held.remove(key)
  }

  private fun LaunchBoardTarget.select(items: List<WorkItem>): WorkItem? = when (this) {
    is LaunchBoardTarget.Item -> items.firstOrNull {
      it.id == id && it.kind == kind && it.control.repositoryIdentity == repositoryIdentity
    }
    is LaunchBoardTarget.Goal -> items.singleOrNull {
      it.kind == WorkKind.Goal && it.issueKey == issueKey && it.control.repositoryIdentity == repositoryIdentity &&
        (workId == null || it.id == workId)
    }
  }

  private fun newest(first: Snapshot?, second: Snapshot): Snapshot =
    if (first == null || second.page.observedAt >= first.page.observedAt) second else first

  private fun publish() {
    mutableKeys.value = held.keys.toSet()
    val activeSessions = held.values.mapTo(HashSet()) { it.handle.sessionId }
    latest.keys.removeAll { it !in activeSessions }
  }
}
