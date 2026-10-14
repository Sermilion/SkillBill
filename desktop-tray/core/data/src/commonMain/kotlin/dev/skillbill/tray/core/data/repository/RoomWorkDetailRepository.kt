package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.session.CursorCodec
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.DetailCursor
import dev.skillbill.tray.core.data.session.OwnedRegistration
import dev.skillbill.tray.core.data.session.SessionParticipant
import dev.skillbill.tray.core.data.session.staleCursor
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Instant

private const val DETAIL_READ_ATTEMPTS = 3

/** Work detail pages and the live observation of one item for the selected database. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RoomWorkDetailRepository(
  private val session: DatabaseSession,
  private val dispatchers: DispatcherProvider,
  private val clock: Clock,
) : WorkDetailRepository {
  private var detailOwnerObservation: AutoCloseable? = null
  private var detailObservationToken = 0L

  init {
    session.register(
      object : SessionParticipant {
        override fun registrations(): List<OwnedRegistration> =
          listOfNotNull(detailOwnerObservation?.let { OwnedRegistration(it, "room_detail_owner_close_failed") })

        override fun invalidated(sections: Set<WorkSection>) = Unit

        override fun reset(closed: List<AutoCloseable>) {
          if (detailOwnerObservation in closed) detailOwnerObservation = null
        }
      },
    )
  }

  override fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>> = flow {
    coroutineScope {
      val token = session.mutex.withLock { ++detailObservationToken }
      session.acquireSubscriber()
      var timer: Job? = null
      try {
        var relevant = setOf(WorkSection.GOALS, WorkSection.PHASES)
        var previous: Map<WorkSection, Long>? = null
        session.detailSignals.collect { revisions ->
          val changed = previous == null || relevant.any { previous?.get(it) != revisions[it] }
          previous = revisions
          if (changed) {
            timer?.cancelAndJoin()
            val result = detail(identity)
            var nextDeadline: Instant? = null
            if (result is Outcome.Value) {
              relevant = if (result.value.item.family == WorkFamily.FEATURE_GOAL) {
                setOf(WorkSection.GOALS, WorkSection.PHASES)
              } else {
                setOf(WorkSection.PHASES)
              }
              nextDeadline = watchOwner(identity, result.value, token)
            }
            emit(result)
            nextDeadline?.let { deadline ->
              timer = launch {
                delay(waitMillis(deadline - clock.now()))
                if (token == detailObservationToken && !session.closed) session.invalidate(setOf(WorkSection.NEEDS_YOU))
              }
            }
          }
        }
      } finally {
        withContext(NonCancellable) {
          timer?.cancelAndJoin()
          session.mutex.withLock {
            if (token == detailObservationToken) {
              detailOwnerObservation?.close()
              detailOwnerObservation = null
            }
          }
          session.releaseSubscriber()
        }
      }
    }
  }.flowOn(dispatchers.io)

  /** Registers the item's owners and returns when the view next needs to be re-read, or null if it never does. */
  private suspend fun watchOwner(identity: WorkIdentity, detail: Detail, token: Long): Instant? = session.mutex.withLock {
    if (token != detailObservationToken || detail.item.databaseGeneration != session.generation) return@withLock null
    detailOwnerObservation?.close()
    val ownerGeneration = session.generation
    val registration = session.gateway.observeOwners(
      listOf(identity.id).takeIf { detail.item.lifecycle == Lifecycle.ACTIVE }.orEmpty(),
      clock.now(),
    ) {
      if (token == detailObservationToken && ownerGeneration == session.generation && !session.closed) {
        session.invalidate(setOf(WorkSection.NEEDS_YOU))
      }
    }
    detailOwnerObservation = registration
    listOfNotNull(registration.nextLeaseExpiry, detail.freshnessDeadline).filter { it > clock.now() }.minOrNull()
  }

  override suspend fun detail(
    identity: WorkIdentity,
    cursor: String?,
    list: DetailList?,
    startOffset: Int,
  ): Outcome<Detail> = session.mutex.withLock {
    session.safeRead {
      session.ensureSession()
      val parsed = cursor?.let { CursorCodec.decodeDetail(it) ?: return@safeRead staleCursor() }
      val generation = session.generation
      val observationSession = session.observationSession
      if (parsed != null && (
          parsed.generation != generation || parsed.session != observationSession ||
            parsed.revision != session.workObservation().revision || parsed.id != identity.id ||
            parsed.repositoryIdentity != identity.repositoryIdentity || parsed.list != list
          )
      ) {
        return@safeRead staleCursor()
      }
      repeat(DETAIL_READ_ATTEMPTS) {
        val before = session.workObservation().revision
        val loaded = session.gateway.existingSnapshot {
          detail(identity, clock.now(), parsed?.runtimeCursor, PAGE_SIZE, startOffset, list)
        }
        if (session.currentFileMatches()) {
          val found = loaded.detail ?: return@safeRead Outcome.Failure(
            Problem(ProblemCode.fromWire(loaded.problemCode ?: "unknown_work_item"), "This work item is no longer available."),
          )
          fun token(selected: DetailList?): String? =
            (if (selected == null) loaded.runtimeCursor else loaded.listCursors[selected])?.let { runtimeCursor ->
              CursorCodec.encode(
                DetailCursor(generation, observationSession, before, identity.id, identity.repositoryIdentity, runtimeCursor, selected),
              )
            }
          val mapped = found.copy(
            item = found.item.copy(databaseGeneration = generation),
            nextCursor = token(null),
            cursors = loaded.moreLists.associateWith { checkNotNull(token(it)) },
            runOptions = loaded.runOptions,
          )
          if (mapped.item.identity != identity) return@safeRead staleCursor()
          return@safeRead Outcome.Value(mapped)
        }
      }
      Outcome.Failure(Problem(ProblemCode.BUSY, "Detail changed during this read. Retry.", StorageFailure.BUSY))
    }
  }
}
