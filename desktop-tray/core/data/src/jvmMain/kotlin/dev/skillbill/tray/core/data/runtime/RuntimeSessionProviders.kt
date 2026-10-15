package dev.skillbill.tray.core.data.runtime

import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.common.di.DatabaseSessionScope
import dev.skillbill.tray.core.data.session.DatabaseSelection
import me.tatarka.inject.annotations.Provides
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path

/** Builds the runtime for the database a session was created for, from the host facts the app was started with. */
@ContributesTo(DatabaseSessionScope::class)
interface RuntimeSessionProviders {
  @Provides
  @SingleIn(DatabaseSessionScope::class)
  fun runtimeComponent(selection: DatabaseSelection, host: HostEnvironment): RuntimeComponent = RuntimeComponent::class.create(
    RuntimeContext(
      EnvironmentContext(
        selection.path,
        environment = host.variables,
        userHome = Path.of(host.userHome),
        repositoryRoot = Path.of(host.workingDirectory),
      ),
      TransportContext(),
      WorkflowOpsContext(),
      OptionalCallbacks(),
    ),
  )
}
