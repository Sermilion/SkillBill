package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.approval.ApprovalObservations
import dev.skillbill.desktop.core.data.approval.SessionApprovalGenerations
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import dev.skillbill.desktop.core.data.repository.ActivityObservations
import dev.skillbill.desktop.core.data.repository.BoardObservations
import dev.skillbill.desktop.core.data.repository.ChangeObservations
import dev.skillbill.desktop.core.data.repository.ChangeScopeObservations
import dev.skillbill.desktop.core.data.repository.DetailObservations
import dev.skillbill.desktop.core.data.repository.DiffCommentObservations
import dev.skillbill.desktop.core.data.repository.SpecObservations
import dev.skillbill.desktop.core.data.repository.WorkingTreeReads
import dev.skillbill.desktop.core.data.runtime.DetailRead
import dev.skillbill.desktop.core.data.runtime.RuntimeBoardReads
import dev.skillbill.desktop.core.data.runtime.RuntimeDetailReads
import dev.skillbill.desktop.core.data.runtime.RuntimeReads
import dev.skillbill.desktop.core.data.signal.CommitSignalWatcher
import dev.skillbill.desktop.core.data.signal.NativeSignalWatch
import dev.skillbill.desktop.core.data.signal.SpecDirectoryChanges
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.ports.db.model.DatabasePathProtectionResult
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.filesystem.model.DatabaseFileObservation
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/** Everything bound to one database session. [DatabaseSessionManager] creates one per selection and releases it on switch. */
@ContributesSubcomponent(DatabaseSessionScope::class)
@SingleIn(DatabaseSessionScope::class)
interface DatabaseSessionComponent {
  val selection: SessionSelectionHolder
  val purgeIdentity: SessionPurgeIdentity
  val runtime: RuntimeComponent
  val reads: RuntimeReads
  val signals: CommitSignalWatcher
  val board: BoardObservations
  val details: DetailObservations
  val favoriteResolver: FavoriteWorkSessionResolver
  val specs: SpecObservations
  val changes: ChangeObservations
  val changeScopes: ChangeScopeObservations
  val activity: ActivityObservations
  val diffComments: DiffCommentObservations
  val approvals: ApprovalObservations
  val approvalGenerations: SessionApprovalGenerations

  @ContributesSubcomponent.Factory(AppScope::class)
  interface Factory {
    fun createDatabaseSessionComponent(): DatabaseSessionComponent
  }
}

/** Builds the session's runtime from the host facts the app started with and the session's selection. */
@ContributesTo(DatabaseSessionScope::class)
interface RuntimeSessionProviders {
  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun runtimeComponent(holder: SessionSelectionHolder, host: HostEnvironment): RuntimeComponent {
    val selection = holder.selection
    return RuntimeComponent::class.create(
      RuntimeContext(
        EnvironmentContext(
          selection.databasePath,
          environment = host.variables,
          userHome = Path.of(host.userHome),
          repositoryRoot = Path.of(host.workingDirectory),
        ),
        TransportContext(),
        WorkflowOpsContext(),
        OptionalCallbacks(hostAgentHomesPort = SelectedAgentHomes(selection.claudeConfigDir, host.userHome)),
      ),
    )
  }

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun signalWatcher(
    holder: SessionSelectionHolder,
    runtime: RuntimeComponent,
    diagnostics: Diagnostics,
    switch: DiagnosticsSwitch,
    clock: Clock,
  ): CommitSignalWatcher = CommitSignalWatcher(
    holder.selection.scope,
    runtime.databaseSession.resolveDbPath(),
    diagnostics,
    switch,
    clock,
    ::NativeSignalWatch,
  )

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun boardObservations(
    holder: SessionSelectionHolder,
    reads: RuntimeReads,
    runtime: RuntimeComponent,
    signals: CommitSignalWatcher,
    diagnostics: Diagnostics,
    clock: Clock,
  ): BoardObservations =
    BoardObservations(holder.selection.scope, signals.snapshots, RuntimeBoardReads(reads, runtime, diagnostics, holder.selection.id), clock)

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun detailObservations(
    holder: SessionSelectionHolder,
    reads: RuntimeReads,
    runtime: RuntimeComponent,
    signals: CommitSignalWatcher,
    diagnostics: Diagnostics,
  ): DetailObservations = DetailObservations(holder.selection.scope, signals.snapshots, RuntimeDetailReads(reads, runtime, diagnostics, holder.selection.id))

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun specObservations(
    holder: SessionSelectionHolder,
    reads: RuntimeReads,
    runtime: RuntimeComponent,
    signals: CommitSignalWatcher,
    diagnostics: Diagnostics,
    host: HostEnvironment,
  ): SpecObservations = SpecObservations(
    holder.selection.scope, holder.selection.id, signals.snapshots, reads, runtime, diagnostics, SpecDirectoryChanges(diagnostics), host,
  )

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun activityObservations(reads: RuntimeReads, runtime: RuntimeComponent, diagnostics: Diagnostics): ActivityObservations =
    ActivityObservations(runtime, diagnostics) { id -> reads.read("work_activity") { it.workActivityService.timeline(id) } }

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun diffCommentObservations(
    reads: RuntimeReads,
    runtime: RuntimeComponent,
    signals: CommitSignalWatcher,
    diagnostics: Diagnostics,
  ): DiffCommentObservations = DiffCommentObservations(signals.snapshots, reads, runtime, diagnostics)

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun workingTreeReads(): WorkingTreeReads = WorkingTreeReads()

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun changeObservations(
    runtime: RuntimeComponent,
    diagnostics: Diagnostics,
    host: HostEnvironment,
    workingTree: WorkingTreeReads,
  ): ChangeObservations = ChangeObservations(runtime, diagnostics, host.variables, workingTree = workingTree)

  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun changeScopeObservations(
    reads: RuntimeReads,
    runtime: RuntimeComponent,
    diagnostics: Diagnostics,
    workingTree: WorkingTreeReads,
  ): ChangeScopeObservations = ChangeScopeObservations(
    runtime,
    diagnostics,
    readSubtasks = { id -> reads.read("work_change_scopes") { it.workChangeScopesService.goalSubtaskScopes(id) } },
    workingTree = workingTree,
  )
}

/** A session graph over a [DatabaseSessionComponent]. */
class RuntimeSessionGraph internal constructor(val component: DatabaseSessionComponent) : SessionGraph {
  override fun databaseFile(): DatabaseFileIdentity? = component.purgeIdentity.observation?.let {
    DatabaseFileIdentity(it.path.toAbsolutePath().normalize().toString(), it.fileKey)
  }

  override fun displayPath(): String = try {
    component.runtime.databaseSession.resolveDbPath().toString()
  } catch (_: InvalidPathException) {
    component.selection.selection.databasePath.orEmpty()
  }

  // Ordinary reads never migrate, so an older existing store is upgraded once here. A missing store is not created.
  override fun open(): Job {
    val runtime = component.runtime
    runtime.databaseSchema.migrateExistingDatabase()
    val protection = when (val result = runtime.databaseSession.acquireDatabasePathProtection()) {
      is DatabasePathProtectionResult.Acquired -> result.protection
      is DatabasePathProtectionResult.Unavailable -> {
        component.purgeIdentity.initialize(null)
        return startSignalsAfterExistingStore(runtime)
      }
    }
    try {
      val observation = runtime.databaseSession.readIfPresent {
        it.workList.observation()
        it.databaseFileObservation
      }
      if (observation == null) {
        component.purgeIdentity.initialize(null)
        throw DatabaseFileMissingException(displayPath())
      }
      when (runtime.databaseSession.transactionFenced(observation) { Unit }) {
        FencedTransactionResult.ChangedTarget -> component.purgeIdentity.initialize(null)
        is FencedTransactionResult.Committed -> component.purgeIdentity.initialize(observation)
      }
    } finally {
      protection.close()
    }
    return component.signals.start()
  }

  private fun startSignalsAfterExistingStore(runtime: RuntimeComponent): Job {
    runtime.databaseSession.readIfPresent { it.workList.observation() } ?: throw DatabaseFileMissingException(displayPath())
    return component.signals.start()
  }

  /** Ends every approval owner generation and operator registration of this session while its database is still usable. */
  internal fun releaseApprovals() {
    component.approvalGenerations.closeAll()
    component.approvals.releaseAll()
  }

  override fun release() {
    try {
      releaseApprovals()
    } finally {
      component.purgeIdentity.clear()
      component.runtime.databaseReadConnections.release()
    }
  }
}

@Inject
@SingleIn(DatabaseSessionScope::class)
class FavoriteWorkSessionResolver(
  reads: RuntimeReads,
  runtime: RuntimeComponent,
  diagnostics: Diagnostics,
  holder: SessionSelectionHolder,
  private val identity: SessionPurgeIdentity,
) {
  private val detailReads = RuntimeDetailReads(reads, runtime, diagnostics, holder.selection.id)
  private val database = runtime.databaseSession

  internal suspend fun resolve(id: String): DetailRead {
    val observed = identity.observation ?: return DetailRead.Failed(dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime("Database identity is unavailable."))
    return try {
      if (database.readIfPresent { it.databaseFileObservation } != observed) return changedIdentity()
      val result = detailReads.first(id)
      if (database.readIfPresent { it.databaseFileObservation } != observed) changedIdentity() else result
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: Exception) {
      DetailRead.Failed(dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime(failure.message ?: "Favorite lookup failed."))
    }
  }

  private fun changedIdentity() = DetailRead.Failed(
    dev.skillbill.desktop.core.domain.model.ReadProblem.Runtime("Database identity changed during favorite lookup."),
  )
}

@Inject
@SingleIn(DatabaseSessionScope::class)
class SessionPurgeIdentity {
  @Volatile
  var observation: DatabaseFileObservation? = null
    private set
  private var initialized = false

  fun initialize(value: DatabaseFileObservation?) {
    check(!initialized)
    initialized = true
    observation = value
  }

  fun clear() {
    observation = null
  }
}

@Inject
class ComponentSessionGraphFactory(private val components: DatabaseSessionComponent.Factory) : SessionGraphFactory<RuntimeSessionGraph> {
  override fun create(selection: SessionSelection): RuntimeSessionGraph {
    val component = components.createDatabaseSessionComponent()
    component.selection.choose(selection)
    return RuntimeSessionGraph(component)
  }
}
