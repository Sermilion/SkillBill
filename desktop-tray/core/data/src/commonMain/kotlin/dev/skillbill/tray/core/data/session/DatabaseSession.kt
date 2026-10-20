package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.port.CommitSignalSource
import dev.skillbill.tray.core.data.port.FailureClassifier
import dev.skillbill.tray.core.data.port.InvalidDatabasePathException
import dev.skillbill.tray.core.data.port.ObservationEndedException
import dev.skillbill.tray.core.data.port.ObservationUnavailableException
import dev.skillbill.tray.core.data.port.RuntimeFailure
import dev.skillbill.tray.core.data.port.RuntimeWorkGateway
import dev.skillbill.tray.core.data.port.StoreObservation
import dev.skillbill.tray.core.data.port.WorkStoreAccess
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.concurrent.Volatile

/** A registration whose close failure is reported under [failureEvent] instead of being thrown. */
internal class OwnedRegistration(val closeable: AutoCloseable, val failureEvent: String)

/** A repository that keeps per-connection state which the session must release or reset with the connection. */
internal interface SessionParticipant {
  /** Registrations to close while the connection is torn down. */
  fun registrations(): List<OwnedRegistration>

  /** Views of [sections] are stale; drop anything cached for them. */
  fun invalidated(sections: Set<WorkSection>)

  /** The connection is gone; forget everything tied to it. [closed] are the registrations that were closed. */
  fun reset(closed: List<AutoCloseable>)
}

internal const val MAX_FAILURE_CAUSES = 16

internal fun AutoCloseable?.closeOwned(diagnostics: Diagnostics, event: String) {
  try {
    this?.close()
  } catch (_: Exception) {
    diagnostics.record(event)
  }
}

internal fun <T> staleCursor(): Outcome<T> =
  Outcome.Failure(Problem(ProblemCode.STALE_TARGET, "The database or query changed. Refresh this view.", StorageFailure.STALE_QUERY))

private fun <T> closedFailure(): Outcome<T> =
  Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "The data connection is closed.", StorageFailure.ACCESS))

/** What [DatabaseSession.acquireGeneration] decided about the connection while it held the state lock. */
private sealed interface Acquisition {
  class Reuse(val generation: SessionGeneration) : Acquisition

  /** The session is closed, or its observer failed and the user has to refresh before it reconnects. */
  data object Unavailable : Acquisition

  /** [fileReplaced] is true when the file behind a live connection is no longer the one it opened, not merely when it reconnects. */
  class Replace(val path: String, val retired: SessionGeneration?, val fileReplaced: Boolean) : Acquisition
}

/**
 * Everything bound to one selected database: its runtime gateway and one [SessionGeneration] at a time (the read-only
 * connection, its commit-signal observation and the scope that owner callbacks, timers and reads run in). Closing or
 * replacing a generation joins everything it launched before readiness is published again.
 *
 * Two locks keep that safe. [mutex] guards session and repository state and is what cleanup callbacks take; nothing
 * ever waits for a generation's work while holding it. [lifecycle] serialises replacement and teardown and is never
 * taken by a callback, so a teardown may join callbacks that are waiting for [mutex].
 */
@Inject
@SingleIn(DatabaseSessionScope::class)
class DatabaseSession(
  internal val gateway: RuntimeWorkGateway,
  private val store: WorkStoreAccess,
  private val commitSignals: CommitSignalSource,
  private val classifier: FailureClassifier,
  private val generations: DatabaseGenerations,
  private val dispatchers: DispatcherProvider,
  private val diagnostics: Diagnostics,
) {
  internal val mutex = Mutex()
  private val lifecycle = Mutex()
  private val sessionScope = CoroutineScope(SupervisorJob() + dispatchers.io)
  private val closerScope = CoroutineScope(SupervisorJob() + dispatchers.io)
  internal val sectionSignals = MutableStateFlow(WorkSection.entries.associateWith { 0L })
  internal val detailSignals = MutableStateFlow(mapOf(WorkSection.GOALS to 0L, WorkSection.PHASES to 0L, WorkSection.OPERATIONS to 0L))
  private val detailOwnerSignals = MutableStateFlow(emptyMap<Long, Long>())
  private val mutableRefreshEvents = MutableSharedFlow<Set<WorkSection>>(extraBufferCapacity = 1)
  internal val refreshEvents: Flow<Set<WorkSection>> = mutableRefreshEvents.asSharedFlow()
  internal var path: String? = null
    private set
  internal var generation = 0L
    private set
  internal var observationSession = 0L
    private set

  @Volatile
  private var current: SessionGeneration? = null
  private var subscribers = 0
  private val closedFlag = MutableStateFlow(false)
  private val closeCompletion = CompletableDeferred<Unit>()
  private val participants = mutableListOf<SessionParticipant>()

  @Volatile
  internal var observationProblem: Problem? = null
    private set
  internal var configurationProblem: Problem? = null
    private set

  internal val closed: Boolean get() = closedFlag.value

  internal fun register(participant: SessionParticipant) {
    participants += participant
  }

  /** Binds the session to the database its selection names; the manager calls it once before publishing the session. */
  internal suspend fun open() = mutex.withLock {
    check(!closed) { "The database session is closed." }
    configurationProblem = null
    try {
      path = gateway.databasePath()
    } catch (_: InvalidDatabasePathException) {
      configurationProblem = Problem(ProblemCode.DATABASE_ACCESS, "The selected database path is invalid.", StorageFailure.ACCESS)
      diagnostics.record("database_path_invalid")
    }
    if (path != null) configurationProblem = prepareSchema()
    generation = generations.next()
    observationProblem = null
  }

  /** Reconnects after an observer failure, and retries a refused upgrade; callers hold no lock. */
  internal suspend fun refresh() = lifecycle.withLock {
    val stale = mutex.withLock { observationProblem != null || current?.observer?.isActive != true }
    if (stale) retire(announce = true)
    mutex.withLock {
      if (configurationProblem != null && path != null) configurationProblem = prepareSchema()
      observationProblem = null
      invalidate()
    }
  }

  /** Upgrades an older selected store before anything reads it; a refusal becomes the session's configuration problem. */
  private suspend fun prepareSchema(): Problem? {
    configurationProblem = null
    val outcome = safeRead {
      withContext(dispatchers.io) { gateway.upgradeExistingStore() }
      Outcome.Value(Unit)
    }
    return (outcome as? Outcome.Failure)?.problem
  }

  internal fun workObservation(): StoreObservation = gateway.observation()

  internal fun currentFileMatches(): Boolean = current?.connection?.isCurrent() == true

  /** The generation the session currently exposes, for owners that are registered outside a read. */
  internal fun currentGeneration(): SessionGeneration? = current

  /** Identifies the generation and observation session a deadline or callback is created in. Callers hold [mutex]. */
  internal fun token(): SessionToken = SessionToken(generation, observationSession)

  /** True while [token] still names the live generation of an open session. Callers hold [mutex]. */
  internal fun isCurrent(token: SessionToken): Boolean =
    !closed && token.generation == generation && token.observationSession == observationSession

  /**
   * Runs [block] under the state lock inside the scope of the usable generation, opening or replacing the connection
   * first. The read is a job of the generation, so retiring the generation joins it before the connection closes.
   */
  internal suspend fun <T> read(block: suspend (SessionGeneration) -> Outcome<T>): Outcome<T> = safeRead {
    val acquired = acquireGeneration() ?: return@safeRead observationProblem?.let { Outcome.Failure(it) } ?: closedFailure()
    val reading = acquired.scope.async {
      mutex.withLock { if (current === acquired) block(acquired) else staleCursor() }
    }
    try {
      reading.await()
    } catch (_: CancellationException) {
      // Either this caller was cancelled, which ensureActive rethrows, or the generation was retired under the read.
      reading.cancel()
      currentCoroutineContext().ensureActive()
      staleCursor()
    }
  }

  /** Runs [block] under the state lock in [owner]'s scope; it is dropped when that generation was retired. */
  internal fun launchIn(owner: SessionGeneration, block: suspend () -> Unit) {
    owner.scope.launch { mutex.withLock { if (current === owner && !closed) block() } }
  }

  /**
   * Checking the file and opening a connection block, and readers call from any dispatcher (screens read from the main thread),
   * so the acquisition runs on the I/O dispatcher. A replacement is stored in [current] before it returns, so a caller cancelled
   * at the hand-back never leaks the connection.
   */
  private suspend fun acquireGeneration(): SessionGeneration? = withContext(dispatchers.io) {
    lifecycle.withLock {
      when (val acquisition = mutex.withLock { planAcquisition() }) {
        is Acquisition.Reuse -> acquisition.generation
        Acquisition.Unavailable -> null
        is Acquisition.Replace -> {
          dispose(acquisition.retired, announce = true)
          if (acquisition.fileReplaced) generations.publishReplacement()
          open(acquisition.path)
        }
      }
    }
  }

  private fun planAcquisition(): Acquisition {
    val existing = current
    return when {
      closed -> Acquisition.Unavailable
      existing != null && existing.connection.isCurrent() && (observationProblem != null || existing.observer?.isActive == true) ->
        Acquisition.Reuse(existing)
      existing == null && observationProblem != null -> Acquisition.Unavailable
      else -> {
        val fileReplaced = existing != null && !existing.connection.isCurrent()
        val retired = detach(announce = true)
        observationProblem = null
        Acquisition.Replace(requireNotNull(path), retired, fileReplaced)
      }
    }
  }

  /** Opens the connection and its change observer; any failure disposes what was opened before it is rethrown. */
  private suspend fun open(selected: String): SessionGeneration {
    gateway.existingSnapshot { }
    val connection = store.open(selected)
    val session = mutex.withLock { ++observationSession }
    val opened = SessionGeneration(connection, session, CoroutineScope(SupervisorJob(sessionScope.coroutineContext.job) + dispatchers.io))
    val ready = CompletableDeferred<Unit>()
    opened.observer = opened.scope.launch { observe(selected, ready, opened) }
    try {
      ready.await()
    } catch (error: Throwable) {
      dispose(opened, announce = false)
      throw when {
        error is CancellationException || error is ObservationUnavailableException -> error
        error is Exception && classifier.isObserverFailure(error) -> ObservationUnavailableException(error)
        else -> error
      }
    }
    mutex.withLock { current = opened }
    return opened
  }

  private suspend fun observe(selected: String, ready: CompletableDeferred<Unit>, owner: SessionGeneration) {
    val topics = commitSignals.topics
    var deliveredRevisions = emptyMap<String, Long>()
    try {
      commitSignals.changes(selected, ready).collect { change ->
        val changedTopics = change.revisions.filter { (topic, revision) -> deliveredRevisions[topic] != revision }.keys
        deliveredRevisions = change.revisions
        if (change.initial) return@collect
        if (changedTopics.isEmpty() && !change.resynchronize) return@collect
        if (changedTopics == setOf(topics.activity) && !change.resynchronize) {
          invalidateDetails()
          return@collect
        }
        val sections = buildSet {
          add(WorkSection.NEEDS_YOU)
          add(WorkSection.HISTORY)
          add(WorkSection.RECENT)
          val affected = if (change.resynchronize) topics.all else changedTopics
          if (topics.goals in affected) add(WorkSection.GOALS)
          if (topics.phases in affected) {
            add(WorkSection.PHASES)
            add(WorkSection.OPERATIONS)
          }
        }
        if (owner.observationSession == observationSession) observationProblem = null
        invalidate(sections)
      }
      if (owner.connection.isCurrent()) throw ObservationEndedException()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      if (!classifier.isObserverFailure(error)) {
        ready.completeExceptionally(error)
        throw error
      }
      val providerCode = generateSequence(error as Throwable?) { it.cause }.take(MAX_FAILURE_CAUSES)
        .firstNotNullOfOrNull(classifier::providerFailure)?.code
      diagnostics.recordRequest(
        "observation_unavailable_${classifier.diagnosticName(error)}",
        providerCode?.let { mapOf("sqlite_error_code" to it) }.orEmpty(),
      )
      if (owner.observationSession != observationSession) return
      observationProblem = Problem(
        ProblemCode.OBSERVATION_UNAVAILABLE,
        "Database change observation is unavailable. Refresh to reconnect.",
        StorageFailure.OBSERVATION_UNAVAILABLE,
      )
      invalidate()
      ready.completeExceptionally(ObservationUnavailableException(error))
    }
  }

  internal fun invalidate(sections: Set<WorkSection> = WorkSection.entries.toSet()) {
    val detailSections = if (sections == setOf(WorkSection.NEEDS_YOU)) setOf(WorkSection.GOALS, WorkSection.PHASES, WorkSection.OPERATIONS) else sections
    detailSignals.update { current ->
      current.mapValues { (section, revision) -> if (section in detailSections) revision + 1 else revision }
    }
    mutableRefreshEvents.tryEmit(sections)
    participants.forEach { it.invalidated(sections) }
    sectionSignals.update { current ->
      current.mapValues { (section, revision) -> if (section in sections) revision + 1 else revision }
    }
  }

  internal fun invalidateDetails() {
    detailSignals.update { current -> current.mapValues { (_, revision) -> revision + 1 } }
  }

  /**
   * Re-reads [section] because a deadline owned by [token] passed. Only that section is touched: every section that
   * depends on a deadline arms its own, so no other section, detail or paging source is re-read. Returns false, doing
   * nothing, when [token] no longer names the live generation. Callers hold [mutex].
   */
  internal fun reprojectSection(section: WorkSection, token: SessionToken): Boolean {
    if (!isCurrent(token)) return false
    val sections = setOf(section)
    mutableRefreshEvents.tryEmit(sections)
    participants.forEach { it.invalidated(sections) }
    sectionSignals.update { current ->
      current.mapValues { (name, revision) -> if (name in sections) revision + 1 else revision }
    }
    return true
  }

  /** Emits a value whenever [owner]'s own deadline asks for its detail to be re-read. */
  internal fun detailReprojections(owner: Long): Flow<Long> = detailOwnerSignals.map { it[owner] ?: 0L }.distinctUntilChanged()

  /** Re-reads only the detail subscription [owner]; false, doing nothing, when [token] is no longer live. Callers hold [mutex]. */
  internal fun reprojectDetail(owner: Long, token: SessionToken): Boolean {
    if (!isCurrent(token)) return false
    detailOwnerSignals.update { it + (owner to ((it[owner] ?: 0L) + 1)) }
    return true
  }

  /** Closes one owner registration, reporting a failure under [event] instead of throwing it. */
  internal fun closeRegistration(registration: AutoCloseable?, event: String) = registration.closeOwned(diagnostics, event)

  internal fun releaseDetailOwner(owner: Long) {
    detailOwnerSignals.update { it - owner }
  }

  /** Marks one board or detail collector as active; the connection and observer stay open while any is. */
  internal suspend fun acquireSubscriber() {
    mutex.withLock { subscribers++ }
  }

  /** Ends one collector; the last one out releases the connection, observer and registrations without announcing a change. */
  internal suspend fun releaseSubscriber() {
    withContext(NonCancellable) {
      lifecycle.withLock {
        var idle = false
        val retired = mutex.withLock {
          subscribers = (subscribers - 1).coerceAtLeast(0)
          idle = subscribers == 0 && !closed
          if (idle) detach(announce = false).also { observationProblem = null } else null
        }
        if (idle) dispose(retired, announce = false)
      }
    }
  }

  /**
   * Takes the current generation out of service so nothing new can start on it. [announce] also advances the generation
   * number; an idle release skips it because the next open bumps [observationSession], which already fences older
   * cursors. Callers hold [mutex]; the caller must pass the result to [dispose] after releasing it.
   */
  private fun detach(announce: Boolean): SessionGeneration? {
    val retired = current
    current = null
    if (announce) generation = generations.next()
    return retired
  }

  private suspend fun retire(announce: Boolean) = dispose(mutex.withLock { detach(announce) }, announce)

  /**
   * Stops a detached generation without holding [mutex]: its scope (observer, owner callbacks, timers and in-flight
   * reads) is cancelled and joined first, then its registrations and connection close, and only then is state reset and
   * [announce] told to subscribers. Callers hold [lifecycle].
   */
  private suspend fun dispose(retired: SessionGeneration?, announce: Boolean) {
    withContext(dispatchers.io + NonCancellable) {
      try {
        retired?.scope?.coroutineContext?.job?.cancelAndJoin()
      } catch (_: Exception) {
        diagnostics.record("room_observer_teardown_failed")
      }
      val registrations = mutex.withLock { participants.flatMap { it.registrations() } }
      registrations.forEach { it.closeable.closeOwned(diagnostics, it.failureEvent) }
      try {
        retired?.connection?.close()
      } catch (_: Exception) {
        diagnostics.record("room_session_close_failed")
      }
      mutex.withLock {
        val closedRegistrations = registrations.map { it.closeable }
        participants.forEach { it.reset(closedRegistrations) }
        if (announce && retired != null) invalidate()
      }
    }
  }

  private fun beginClose(): Boolean {
    if (!closedFlag.compareAndSet(false, true)) return false
    participants.forEach { it.invalidated(WorkSection.entries.toSet()) }
    return true
  }

  /** Stops observing, releases every registration and the connection without waiting for it. Idempotent. */
  fun close() {
    if (beginClose()) closerScope.launch { shutdown() }
  }

  /** Closes the session and returns only after its generation, registrations, connection and scope have stopped. */
  suspend fun closeAndJoin() {
    if (beginClose()) shutdown()
    closeCompletion.await()
  }

  private suspend fun shutdown() {
    withContext(NonCancellable) {
      try {
        lifecycle.withLock { retire(announce = true) }
      } finally {
        sessionScope.coroutineContext.job.cancelAndJoin()
        closeCompletion.complete(Unit)
        closerScope.cancel()
      }
    }
  }

  /** Runs [block] against a usable session, turning provider and runtime failures into typed outcomes. */
  internal suspend fun <T> safeRead(block: suspend () -> Outcome<T>): Outcome<T> = try {
    if (closed) {
      closedFailure()
    } else {
      configurationProblem?.let { Outcome.Failure(it) } ?: block()
    }
  } catch (error: Exception) {
    val causes = generateSequence(error as Throwable?) { it.cause }.take(MAX_FAILURE_CAUSES).toList()
    causes.firstOrNull { it is CancellationException }?.let { throw it }
    causes.firstNotNullOfOrNull(classifier::providerFailure)?.let { providerFailure ->
      diagnostics.recordRequest(
        "room_read_provider_failure",
        providerFailure.code?.let { mapOf("sqlite_error_code" to it) }.orEmpty(),
      )
    }
    val code = causes.firstNotNullOfOrNull { cause -> classify(cause) } ?: throw error
    failure(code)
  }

  private fun classify(cause: Throwable): String? {
    if (cause is ObservationUnavailableException) return "observation_unavailable"
    classifier.runtimeFailure(cause)?.let { kind -> return kind.failureCode(otherCode = "incompatible_record") }
    if (classifier.providerFailure(cause) != null) return classifyProviderFailure()
    if (classifier.isIoFailure(cause)) return if (fileIsMissing()) "missing_database" else "database_access"
    if (classifier.isAccessDenied(cause)) return "database_access"
    return null
  }

  private fun fileIsMissing(): Boolean = path?.let(store::isMissing) == true

  private fun classifyProviderFailure(): String {
    if (fileIsMissing()) return "missing_database"
    return try {
      gateway.existingSnapshot { }
      "database_access"
    } catch (failure: Exception) {
      (classifier.runtimeFailure(failure) ?: throw failure).failureCode(otherCode = "database_access")
    }
  }

  private fun RuntimeFailure.failureCode(otherCode: String): String = when (this) {
    RuntimeFailure.MISSING -> "missing_database"
    RuntimeFailure.BUSY -> "busy"
    RuntimeFailure.CORRUPT -> "corrupt_database"
    RuntimeFailure.INCOMPATIBLE_SCHEMA -> "incompatible_database"
    RuntimeFailure.ACCESS -> "database_access"
    RuntimeFailure.OTHER -> otherCode
  }

  internal fun <T> failure(code: String): Outcome<T> {
    diagnostics.record("work_data_$code")
    val summary = when (code) {
      "missing_database" -> "The selected database does not exist. Choose an existing runtime store."
      "busy" -> "The database is busy. Retry after the writer finishes."
      "corrupt_database" -> "The selected database is corrupt. Restore it through the runtime's supported recovery route."
      "incompatible_record" -> "A saved workflow record could not be read. Check the runtime diagnostics for the affected workflow."
      "incompatible_database" -> "The database is incompatible with this app. Update the runtime and apply its supported migrations before reconnecting."
      "observation_unavailable" -> "Database change observation is unavailable. Reconnect and retry."
      else -> "The selected database could not be read. Check its path and access permissions."
    }
    val problemCode = ProblemCode.fromWire(code)
    return Outcome.Failure(Problem(problemCode, summary, StorageFailure.entries.single { it.code == problemCode.wireValue }))
  }
}
