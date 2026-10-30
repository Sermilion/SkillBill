package dev.skillbill.tray.core.data

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.data.port.CandidateRequest
import dev.skillbill.tray.core.data.port.CandidateResult
import dev.skillbill.tray.core.data.port.CommitSignalSource
import dev.skillbill.tray.core.data.port.ControlAction
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.port.OwnerRegistration
import dev.skillbill.tray.core.data.port.RuntimeWorkGateway
import dev.skillbill.tray.core.data.port.SignalTopics
import dev.skillbill.tray.core.data.port.SnapshotReads
import dev.skillbill.tray.core.data.port.StoreChange
import dev.skillbill.tray.core.data.port.WorkStoreAccess
import dev.skillbill.tray.core.data.port.WorkStoreConnection
import dev.skillbill.tray.core.data.repository.RoomWorkBoardRepository
import dev.skillbill.tray.core.data.repository.RoomWorkDetailRepository
import dev.skillbill.tray.core.data.repository.RuntimeAgentHomeRepository
import dev.skillbill.tray.core.data.repository.RuntimeSpecBrowserRepository
import dev.skillbill.tray.core.data.repository.RuntimeWorkControlRepository
import dev.skillbill.tray.core.data.repository.SessionWorkBoardRepository
import dev.skillbill.tray.core.data.repository.SessionWorkControlRepository
import dev.skillbill.tray.core.data.repository.SessionWorkDetailRepository
import dev.skillbill.tray.core.data.runtime.JvmFailureClassifier
import dev.skillbill.tray.core.data.runtime.RuntimeSessionProviders
import dev.skillbill.tray.core.data.runtime.RuntimeAgentHomeAdapter
import dev.skillbill.tray.core.data.runtime.RuntimeSpecAdapter
import dev.skillbill.tray.core.data.runtime.RuntimeWorkAdapter
import dev.skillbill.tray.core.data.session.DatabaseGenerations
import dev.skillbill.tray.core.data.session.DatabaseSelection
import dev.skillbill.tray.core.data.session.DatabaseSession
import dev.skillbill.tray.core.data.session.MonotonicClock
import dev.skillbill.tray.core.data.session.RuntimeBuildGate
import dev.skillbill.tray.core.data.session.SessionFactory
import dev.skillbill.tray.core.data.session.SessionParts
import dev.skillbill.tray.core.data.session.TimeSourceMonotonicClock
import dev.skillbill.tray.core.data.session.WorkStoreSessionManager
import dev.skillbill.tray.core.data.signal.DesktopCommitSignalSource
import dev.skillbill.tray.core.data.signal.NioSpecDirectoryChanges
import dev.skillbill.tray.core.data.store.JvmWorkStoreAccess
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkItem
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.domain.repository.WorkDetailRepository
import dev.skillbill.tray.core.domain.repository.WorkStoreSession
import dev.skillbill.tray.core.testing.FakeSettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Instant

/** Records every diagnostic so tests can assert on what the data layer reported. */
class RecordingDiagnostics : Diagnostics {
  val categories = CopyOnWriteArrayList<String>()
  val requests = CopyOnWriteArrayList<Pair<String, Map<String, Number>>>()

  override fun record(category: String) {
    categories += category
  }

  override fun recordRequest(category: String, fields: Map<String, Number>) {
    categories += category
    requests += category to fields
  }
}

/**
 * The data layer built from its constructors, the way the generated graph wires it: one app-scope manager and facades
 * over per-database sessions. Tests choose the clocks and the commit-signal source; everything else is the real
 * implementation against real SQLite files.
 */
class WorkStack(
  private val boards: WorkBoardRepository,
  private val details: WorkDetailRepository,
  private val controls: WorkControlRepository,
  private val store: WorkStoreSession,
  val diagnostics: RecordingDiagnostics,
  /** Counts every read that reaches the store or the runtime: candidate queries and snapshot reads. */
  val readWork: AtomicInteger,
  /** Counts live connections to the store file, so a test can prove that observers and handles are released. */
  val openConnections: AtomicInteger,
  /** Counts open owner-exit registrations; zero means no board or detail subscription still watches an owner. */
  val ownerRegistrations: AtomicInteger,
  /** Counts collected commit-signal observations; zero means the file watch was released. */
  val signalRegistrations: AtomicInteger,
  /** Counts sessions the session factory built; zero means no runtime was created for any selection. */
  val sessionsCreated: AtomicInteger,
) : WorkBoardRepository by boards,
  WorkDetailRepository by details,
  WorkControlRepository by controls,
  WorkStoreSession by store

fun workStack(
  home: Path,
  clock: Clock = Clock.System,
  monotonicClock: MonotonicClock = TimeSourceMonotonicClock,
  commitSignals: (Diagnostics) -> CommitSignalSource = { DesktopCommitSignalSource(it) },
  /** Runs at the start of every candidate query, so a test can hold a board read open or count page reads. */
  onCandidateQuery: () -> Unit = {},
  /** Answers a control request in place of the runtime when it returns a result, so a test can script an outcome. */
  scriptedControl: (ControlAction) -> ControlResult? = { null },
  gate: RuntimeBuildGate = RuntimeBuildGate { null },
  afterSnapshot: () -> Unit = {},
): WorkStack {
  val diagnostics = RecordingDiagnostics()
  val readWork = AtomicInteger()
  val openConnections = AtomicInteger()
  val ownerRegistrations = AtomicInteger()
  val signalRegistrations = AtomicInteger()
  val sessionsCreated = AtomicInteger()
  val dispatchers = DispatcherProvider(io = Dispatchers.IO, ui = Dispatchers.IO)
  val generations = DatabaseGenerations()
  // A non-empty map: an empty one makes the runtime fall back to the host environment.
  val host = HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString())
  val store = CountingStoreAccess(JvmWorkStoreAccess(diagnostics), readWork, openConnections, onCandidateQuery)
  val signals = CountingSignals(commitSignals(diagnostics), signalRegistrations)
  val classifier = JvmFailureClassifier()
  val sessions = SessionFactory { path ->
    sessionsCreated.incrementAndGet()
    val selection = DatabaseSelection().also { it.choose(path) }
    val runtime = object : RuntimeSessionProviders {}.runtimeComponent(selection, host, FakeSettingsRepository(SettingsState.Ready(AppSettings())))
    val gateway = CountingGateway(RuntimeWorkAdapter(runtime), readWork, ownerRegistrations, scriptedControl, afterSnapshot)
    val session = DatabaseSession(gateway, store, signals, classifier, generations, dispatchers, diagnostics, dev.skillbill.tray.core.data.session.SessionDatabaseIdentity())
    SessionParts(
      session,
      RoomWorkBoardRepository(session, dispatchers, clock, monotonicClock, diagnostics),
      RoomWorkDetailRepository(session, dispatchers, clock),
      RuntimeWorkControlRepository(session, diagnostics),
      RuntimeSpecBrowserRepository(
        RuntimeSpecAdapter(runtime, session.selectedDatabaseIdentity), NioSpecDirectoryChanges(diagnostics), signals, dispatchers, diagnostics,
      ),
      RuntimeAgentHomeRepository(RuntimeAgentHomeAdapter(runtime, host), dispatchers),
    )
  }
  val manager = WorkStoreSessionManager(sessions, generations, gate)
  return WorkStack(
    SessionWorkBoardRepository(manager),
    SessionWorkDetailRepository(manager),
    SessionWorkControlRepository(manager),
    manager,
    diagnostics,
    readWork,
    openConnections,
    ownerRegistrations,
    signalRegistrations,
    sessionsCreated,
  )
}

/** A commit-signal source whose observation each test scripts, delegating to the real source where it chooses to. */
class ScriptedSignals(
  private val script: (databasePath: String, ready: CompletableDeferred<Unit>) -> Flow<StoreChange>,
) : CommitSignalSource {
  override val topics: SignalTopics = DesktopCommitSignalSource(RecordingDiagnostics()).topics

  override fun changes(databasePath: String, ready: CompletableDeferred<Unit>): Flow<StoreChange> = script(databasePath, ready)
}

private class CountingSignals(
  private val delegate: CommitSignalSource,
  private val active: AtomicInteger,
) : CommitSignalSource by delegate {
  override fun changes(databasePath: String, ready: CompletableDeferred<Unit>): Flow<StoreChange> =
    delegate.changes(databasePath, ready)
      .onStart { active.incrementAndGet() }
      .onCompletion { active.decrementAndGet() }
}

private class CountingGateway(
  private val delegate: RuntimeWorkGateway,
  private val readWork: AtomicInteger,
  private val ownerRegistrations: AtomicInteger,
  private val scriptedControl: (ControlAction) -> ControlResult?,
  private val afterSnapshot: () -> Unit,
) : RuntimeWorkGateway by delegate {
  override fun request(item: WorkItem, action: ControlAction, agentId: String?, stillCurrent: () -> Boolean): ControlResult =
    scriptedControl(action) ?: delegate.request(item, action, agentId, stillCurrent)

  override fun <T : Any> existingSnapshot(read: SnapshotReads.() -> T): T {
    readWork.incrementAndGet()
    return delegate.existingSnapshot(read).also { afterSnapshot() }
  }

  override fun observeOwners(ids: List<String>, observedAt: Instant, onExit: () -> Unit): OwnerRegistration {
    val registration = delegate.observeOwners(ids, observedAt, onExit)
    ownerRegistrations.incrementAndGet()
    val released = AtomicBoolean()
    return object : OwnerRegistration {
      override val nextLeaseExpiry: Instant? get() = registration.nextLeaseExpiry

      override fun close() {
        try {
          registration.close()
        } finally {
          if (released.compareAndSet(false, true)) ownerRegistrations.decrementAndGet()
        }
      }
    }
  }
}

private class CountingStoreAccess(
  private val delegate: WorkStoreAccess,
  private val readWork: AtomicInteger,
  private val openConnections: AtomicInteger,
  private val onCandidateQuery: () -> Unit,
) : WorkStoreAccess by delegate {
  override suspend fun open(databasePath: String): WorkStoreConnection {
    val connection = delegate.open(databasePath)
    openConnections.incrementAndGet()
    return CountingConnection(connection, readWork, openConnections, onCandidateQuery)
  }
}

private class CountingConnection(
  private val delegate: WorkStoreConnection,
  private val readWork: AtomicInteger,
  private val openConnections: AtomicInteger,
  private val onCandidateQuery: () -> Unit,
) : WorkStoreConnection by delegate {
  override suspend fun candidates(request: CandidateRequest): CandidateResult {
    readWork.incrementAndGet()
    onCandidateQuery()
    return delegate.candidates(request)
  }

  override suspend fun close() {
    openConnections.decrementAndGet()
    delegate.close()
  }
}
