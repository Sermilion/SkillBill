package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.model.RuntimeBuildProblem
import dev.skillbill.tray.core.domain.model.RuntimeBuildRefusedException
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.SpecBrowserRepository
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** What one selected database exposes: its session lifetime and the repositories bound to it. */
class SessionParts(
  val session: DatabaseSession,
  val board: WorkBoardRepository,
  val detail: WorkDetailRepository,
  val control: WorkControlRepository,
  val specs: SpecBrowserRepository,
)

/** Creates the parts of a session for [path]; the generated session component is the production implementation. */
fun interface SessionFactory {
  fun create(path: String?): SessionParts
}

internal sealed interface SessionSlot {
  data object Idle : SessionSlot

  data object Switching : SessionSlot

  data class Refused(val problem: RuntimeBuildProblem) : SessionSlot

  class Active(val parts: SessionParts) : SessionSlot
}

/**
 * Owns which database the app reads. Selecting a database closes the previous session, builds a fresh one and publishes
 * it, so state of an old database can never be read against a new one. A selection the [gate] refuses ends with no
 * session: nothing is created for the new database, so no connection opens and no runtime migrates it. A refresh asks
 * [checkRuntimeBuild] first and reselects on a refusal, so a runtime replaced by another build closes the open session.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class WorkStoreSessionManager(
  private val factory: SessionFactory,
  private val generations: DatabaseGenerations,
  private val gate: RuntimeBuildGate,
) : WorkStoreSession {
  private val lock = Mutex()

  override val generation: StateFlow<Long> = generations.current

  private val closed = MutableStateFlow(false)
  private val mutableSlot = MutableStateFlow<SessionSlot>(SessionSlot.Idle)
  private val switchEvents = MutableSharedFlow<Set<WorkSection>>(extraBufferCapacity = 1)

  internal val slot: StateFlow<SessionSlot> = mutableSlot.asStateFlow()

  /** Emitted after every selection so views of the previous database reload. */
  internal val switched: Flow<Set<WorkSection>> = switchEvents.asSharedFlow()

  /** The active session's parts once any selection in progress has settled; null when no database is selected. */
  internal suspend fun settledParts(): SessionParts? =
    (mutableSlot.first { it !is SessionSlot.Switching } as? SessionSlot.Active)?.parts

  internal suspend fun settledRefusal(): RuntimeBuildProblem? =
    (mutableSlot.first { it !is SessionSlot.Switching } as? SessionSlot.Refused)?.problem

  override suspend fun selectDatabase(path: String?): Unit = lock.withLock {
    check(!closed.value) { "The work store session is closed." }
    val previous = (mutableSlot.value as? SessionSlot.Active)?.parts
    mutableSlot.value = SessionSlot.Switching
    var published = false
    var refusal: RuntimeBuildProblem? = null
    try {
      previous?.session?.closeAndJoin()
      gate.check()?.let { problem ->
        refusal = problem
        throw RuntimeBuildRefusedException(problem)
      }
      val parts = factory.create(path)
      try {
        parts.session.open()
      } catch (failure: Throwable) {
        withContext(NonCancellable) { parts.session.closeAndJoin() }
        throw failure
      }
      mutableSlot.value = SessionSlot.Active(parts)
      published = true
      if (closed.value) parts.session.close()
    } finally {
      if (!published) mutableSlot.value = refusal?.let { SessionSlot.Refused(it) } ?: SessionSlot.Idle
      // The previous database is gone whether or not the new one opened, so everything bound to it is out of date.
      generations.publishReplacement()
    }
    switchEvents.tryEmit(WorkSection.entries.toSet())
  }

  override suspend fun checkRuntimeBuild(): RuntimeBuildProblem? = lock.withLock { gate.check() }

  override fun close() {
    closed.value = true
    (mutableSlot.value as? SessionSlot.Active)?.parts?.session?.close()
  }

  override suspend fun closeAndJoin() {
    closed.value = true
    lock.withLock {
      (mutableSlot.value as? SessionSlot.Active)?.parts?.session?.closeAndJoin()
    }
  }
}
