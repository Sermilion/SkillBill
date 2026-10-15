package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.session.Counter
import dev.skillbill.tray.core.data.session.CursorCodec
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.DeadlineScheduler
import dev.skillbill.tray.core.data.session.DetailAnchor
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

private const val DETAIL_READ_ATTEMPTS = 3
private const val DETAIL_OWNER_CLOSE_FAILED = "room_detail_owner_close_failed"

/** What one detail subscription last rendered: the shared signal revisions and its own deadline-reprojection count. */
private data class DetailSignal(val revisions: Map<WorkSection, Long>, val reprojections: Long)

/** Work detail pages and the live observation of one item for the selected database. */
@Inject
@SingleIn(DatabaseSessionScope::class)
class RoomWorkDetailRepository(
  private val session: DatabaseSession,
  private val dispatchers: DispatcherProvider,
  private val clock: Clock,
) : WorkDetailRepository {
  private val owners = Counter()
  private val ownerObservations = mutableMapOf<Long, AutoCloseable>()

  init {
    session.register(
      object : SessionParticipant {
        override fun registrations(): List<OwnedRegistration> =
          ownerObservations.values.map { OwnedRegistration(it, DETAIL_OWNER_CLOSE_FAILED) }

        override fun invalidated(sections: Set<WorkSection>) = Unit

        override fun reset(closed: List<AutoCloseable>) {
          ownerObservations.values.removeAll { it in closed }
        }
      },
    )
  }

  override fun observeDetail(identity: WorkIdentity): Flow<Outcome<Detail>> = flow {
    val owner = owners.next()
    val scheduler = DeadlineScheduler(clock)
    session.acquireSubscriber()
    try {
      var relevant = setOf(WorkSection.GOALS, WorkSection.PHASES)
      var previous: DetailSignal? = null
      combine(session.detailSignals, session.detailReprojections(owner), ::DetailSignal).collect { signal ->
        val last = previous
        previous = signal
        val changed = last == null || last.reprojections != signal.reprojections ||
          relevant.any { last.revisions[it] != signal.revisions[it] }
        if (changed) {
          val result = detail(identity)
          if (result is Outcome.Value) {
            relevant = if (result.value.item.family == WorkFamily.FEATURE_GOAL) {
              setOf(WorkSection.GOALS, WorkSection.PHASES)
            } else {
              setOf(WorkSection.PHASES)
            }
            watchOwner(owner, identity, result.value, scheduler)
          }
          emit(result)
        }
      }
    } finally {
      withContext(NonCancellable) {
        scheduler.stop()
        session.mutex.withLock { session.closeRegistration(ownerObservations.remove(owner), DETAIL_OWNER_CLOSE_FAILED) }
        session.releaseDetailOwner(owner)
        session.releaseSubscriber()
      }
    }
  }.flowOn(dispatchers.io)

  /**
   * Registers the item's runner and arms the subscription's single timer for the next time the view needs a re-read. Both
   * run in the connection generation's scope and reproject only this subscription, so retiring the generation joins them.
   */
  private suspend fun watchOwner(owner: Long, identity: WorkIdentity, detail: Detail, scheduler: DeadlineScheduler) =
    session.mutex.withLock {
      val generation = session.currentGeneration() ?: return@withLock
      if (detail.item.databaseGeneration != session.generation) return@withLock
      val token = session.token()
      session.closeRegistration(ownerObservations.remove(owner), DETAIL_OWNER_CLOSE_FAILED)
      val registration = session.gateway.observeOwners(
        listOf(identity.id).takeIf { detail.item.lifecycle == Lifecycle.ACTIVE }.orEmpty(),
        clock.now(),
      ) {
        session.launchIn(generation) { session.reprojectDetail(owner, token) }
      }
      ownerObservations[owner] = registration
      val now = clock.now()
      val deadline = listOfNotNull(registration.nextLeaseExpiry, detail.freshnessDeadline).filter { it > now }.minOrNull()
      scheduler.publish(generation.scope, deadline) {
        session.mutex.withLock { session.reprojectDetail(owner, token) }
      }
    }

  override suspend fun detail(
    identity: WorkIdentity,
    cursor: String?,
    list: DetailList?,
    anchor: String?,
  ): Outcome<Detail> = session.read { _ ->
    val parsed = cursor?.let { CursorCodec.decodeDetail(it) ?: return@read staleCursor() }
    val parsedAnchor = anchor?.let { CursorCodec.decodeAnchor(it) ?: return@read staleCursor() }
    val generation = session.generation
    val observationSession = session.observationSession
    repeat(DETAIL_READ_ATTEMPTS) {
      val before = session.workObservation()
      if (parsed != null && parsed.isStale(generation, observationSession, before.storeId, before.revision, identity, list)) {
        return@read staleCursor()
      }
      if (parsedAnchor != null && parsedAnchor.isStale(generation, observationSession, before.storeId, identity, list)) {
        return@read staleCursor()
      }
      val loaded = session.gateway.existingSnapshot {
        detail(identity, clock.now(), parsed?.runtimeCursor, PAGE_SIZE, parsedAnchor?.runtimeAnchor, list)
      }
      if (!session.currentFileMatches() || session.workObservation() != before) return@repeat
      val found = loaded.detail ?: return@read Outcome.Failure(
        Problem(ProblemCode.fromWire(loaded.problemCode ?: "unknown_work_item"), "This work item is no longer available."),
      )
      fun token(selected: DetailList?): String? =
        (if (selected == null) loaded.runtimeCursor else loaded.listCursors[selected])?.let { runtimeCursor ->
          CursorCodec.encode(
            DetailCursor(
              generation, observationSession, before.storeId, before.revision, identity.id, identity.repositoryIdentity,
              runtimeCursor, PAGE_SIZE, selected,
            ),
          )
        }
      val mapped = found.copy(
        item = found.item.copy(databaseGeneration = generation),
        nextCursor = token(null),
        cursors = loaded.moreLists.associateWith { checkNotNull(token(it)) },
        anchors = loaded.listAnchors.mapValues { (selected, runtimeAnchor) ->
          CursorCodec.encode(
            DetailAnchor(generation, observationSession, before.storeId, identity.id, identity.repositoryIdentity, selected, runtimeAnchor),
          )
        },
        runOptions = loaded.runOptions,
      )
      if (mapped.item.identity != identity) return@read staleCursor()
      return@read Outcome.Value(mapped)
    }
    Outcome.Failure(Problem(ProblemCode.BUSY, "Detail changed during this read. Retry.", StorageFailure.BUSY))
  }

  /** A detail cursor only continues the read that issued it: same connection, store revision, item, list and page size. */
  private fun DetailCursor.isStale(
    generation: Long,
    observationSession: Long,
    storeId: String,
    revision: Long,
    identity: WorkIdentity,
    list: DetailList?,
  ): Boolean = this.generation != generation || session != observationSession || this.storeId != storeId ||
    this.revision != revision || id != identity.id || repositoryIdentity != identity.repositoryIdentity ||
    this.list != list || pageSize != PAGE_SIZE

  /** An anchor re-reads a page after the store changed; it only refuses another connection, database, item or list. */
  private fun DetailAnchor.isStale(
    generation: Long,
    observationSession: Long,
    storeId: String,
    identity: WorkIdentity,
    list: DetailList?,
  ): Boolean = this.generation != generation || session != observationSession || this.storeId != storeId ||
    id != identity.id || repositoryIdentity != identity.repositoryIdentity || this.list != list
}
