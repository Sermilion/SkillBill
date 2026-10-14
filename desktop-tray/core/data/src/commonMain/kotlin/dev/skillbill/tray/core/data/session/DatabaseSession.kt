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
import dev.skillbill.tray.core.data.port.WorkStoreConnection
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
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

/**
 * Everything bound to one selected database: its runtime gateway, the read-only connection, the commit-signal
 * observation, and the generation that fences views taken from an older connection. Closing it releases all of them.
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
  internal val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
  internal val sectionSignals = MutableStateFlow(WorkSection.entries.associateWith { 0L })
  internal val detailSignals = MutableStateFlow(mapOf(WorkSection.GOALS to 0L, WorkSection.PHASES to 0L))
  private val mutableRefreshEvents = MutableSharedFlow<Set<WorkSection>>(extraBufferCapacity = 1)
  internal val refreshEvents: Flow<Set<WorkSection>> = mutableRefreshEvents.asSharedFlow()
  internal var path: String? = null
    private set
  internal var generation = 0L
    private set
  internal var observationSession = 0L
    private set
  private var connection: WorkStoreConnection? = null
  private var observer: Job? = null
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
    generation = generations.next()
    observationProblem = null
  }

  /** Reconnects after an observer failure; callers hold no lock. */
  internal suspend fun refresh() = mutex.withLock {
    if (observationProblem != null || observer?.isActive != true) disposeSession()
    observationProblem = null
    invalidate()
  }

  internal fun workObservation(): StoreObservation = gateway.observation()

  internal fun requireConnection(): WorkStoreConnection = checkNotNull(connection)

  internal fun currentFileMatches(): Boolean = connection?.isCurrent() == true

  /** Opens the connection and its change observer if they are absent or stale. Callers hold [mutex]. */
  internal suspend fun ensureSession() {
    val selected = requireNotNull(path)
    if (connection != null && currentFileMatches() && (observationProblem != null || observer?.isActive == true)) return
    if (connection != null && !currentFileMatches()) generation = generations.next()
    disposeSession()
    observationProblem = null
    gateway.existingSnapshot { }
    connection = store.open(selected)
    observationSession++
    val ready = CompletableDeferred<Unit>()
    val ownerSession = observationSession
    observer = scope.launch { observe(selected, ready, ownerSession) }
    try {
      ready.await()
    } catch (error: ObservationUnavailableException) {
      throw error
    } catch (error: Exception) {
      if (classifier.isObserverFailure(error)) throw ObservationUnavailableException(error)
      throw error
    }
  }

  private suspend fun observe(selected: String, ready: CompletableDeferred<Unit>, ownerSession: Long) {
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
          if (topics.phases in affected) add(WorkSection.PHASES)
        }
        if (ownerSession == observationSession) observationProblem = null
        invalidate(sections)
      }
      if (currentFileMatches()) throw ObservationEndedException()
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
      if (ownerSession != observationSession) return
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
    val detailSections = if (sections == setOf(WorkSection.NEEDS_YOU)) setOf(WorkSection.GOALS, WorkSection.PHASES) else sections
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

  /** Marks one board or detail collector as active; the connection and observer stay open while any is. */
  internal suspend fun acquireSubscriber() {
    mutex.withLock { subscribers++ }
  }

  /** Ends one collector; the last one out releases the connection, observer and registrations without announcing a change. */
  internal suspend fun releaseSubscriber() {
    withContext(NonCancellable) {
      mutex.withLock {
        subscribers = (subscribers - 1).coerceAtLeast(0)
        if (subscribers == 0 && !closed) tearDown(announce = false)
      }
    }
  }

  private suspend fun disposeSession() = tearDown(announce = true)

  /**
   * Releases the connection, observer and registrations. [announce] also advances the generation and invalidates views;
   * an idle release skips it because the next open bumps [observationSession], which already fences older cursors.
   */
  private suspend fun tearDown(announce: Boolean) {
    val tornDownObserver = observer
    val tornDownConnection = connection
    val registrations = participants.flatMap { it.registrations() }
    if (announce) {
      generation = generations.next()
      if (tornDownConnection != null || tornDownObserver != null) invalidate()
    }
    withContext(dispatchers.io + NonCancellable) {
      try {
        tornDownObserver?.cancelAndJoin()
      } catch (_: Exception) {
        diagnostics.record("room_observer_teardown_failed")
      }
      registrations.forEach { it.closeable.closeOwned(diagnostics, it.failureEvent) }
      try {
        tornDownConnection?.close()
      } catch (_: Exception) {
        diagnostics.record("room_session_close_failed")
      }
      if (observer === tornDownObserver) observer = null
      if (connection === tornDownConnection) connection = null
      val closedRegistrations = registrations.map { it.closeable }
      participants.forEach { it.reset(closedRegistrations) }
    }
  }

  /** Stops observing, releases every registration and the connection. Idempotent. */
  fun close() {
    if (!closedFlag.compareAndSet(false, true)) return
    participants.forEach { it.invalidated(WorkSection.entries.toSet()) }
    scope.launch {
      try {
        mutex.withLock { disposeSession() }
      } finally {
        scope.cancel()
        closeCompletion.complete(Unit)
      }
    }
  }

  suspend fun closeAndJoin() {
    close()
    closeCompletion.await()
  }

  /** Runs [block] against a usable session, turning provider and runtime failures into typed outcomes. */
  internal suspend fun <T> safeRead(block: suspend () -> Outcome<T>): Outcome<T> = try {
    if (closed) {
      Outcome.Failure(Problem(ProblemCode.DATABASE_ACCESS, "The data connection is closed.", StorageFailure.ACCESS))
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
