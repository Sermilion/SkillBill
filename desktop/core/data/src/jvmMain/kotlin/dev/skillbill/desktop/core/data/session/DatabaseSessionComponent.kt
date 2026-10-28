package dev.skillbill.desktop.core.data.session

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.di.DatabaseSessionScope
import dev.skillbill.desktop.core.data.runtime.RuntimeReads
import dev.skillbill.desktop.core.data.signal.CommitSignalWatcher
import dev.skillbill.desktop.core.data.signal.NativeSignalWatch
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import software.amazon.lastmile.kotlin.inject.anvil.ContributesSubcomponent
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlinx.coroutines.Job
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.time.Clock

/** Everything bound to one database session. [DatabaseSessionManager] creates one per selection and releases it on switch. */
@ContributesSubcomponent(DatabaseSessionScope::class)
@SingleIn(DatabaseSessionScope::class)
interface DatabaseSessionComponent {
  val selection: SessionSelectionHolder
  val runtime: RuntimeComponent
  val reads: RuntimeReads
  val signals: CommitSignalWatcher

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
}

/** A session graph over a [DatabaseSessionComponent]. */
class RuntimeSessionGraph internal constructor(val component: DatabaseSessionComponent) : SessionGraph {
  override fun displayPath(): String = try {
    component.runtime.databaseSession.resolveDbPath().toString()
  } catch (_: InvalidPathException) {
    component.selection.selection.databasePath.orEmpty()
  }

  // Ordinary reads never migrate, so an older existing store is upgraded once here. A missing store is not created.
  override fun open(): Job {
    val runtime = component.runtime
    runtime.databaseSchema.migrateExistingDatabase()
    runtime.databaseSession.readIfPresent { it.workList.observation() } ?: throw DatabaseFileMissingException(displayPath())
    return component.signals.start()
  }

  override fun release() = component.runtime.databaseReadConnections.release()
}

@Inject
class ComponentSessionGraphFactory(private val components: DatabaseSessionComponent.Factory) : SessionGraphFactory<RuntimeSessionGraph> {
  override fun create(selection: SessionSelection): RuntimeSessionGraph {
    val component = components.createDatabaseSessionComponent()
    component.selection.choose(selection)
    return RuntimeSessionGraph(component)
  }
}
