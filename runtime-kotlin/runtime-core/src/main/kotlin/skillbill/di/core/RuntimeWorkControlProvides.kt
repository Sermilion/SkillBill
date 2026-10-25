package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.engine.work.control.WorkControlService
import skillbill.infrastructure.launcher.work.DetachedWorkRunLauncher
import skillbill.ports.system.HostAgentHomesPort
import skillbill.ports.work.WorkIncidentRecoveryCommands
import skillbill.ports.work.WorkRunLauncher

internal interface RuntimeWorkControlProvides {
  @Provides
  fun workIncidentRecoveryCommands(controls: WorkControlService): WorkIncidentRecoveryCommands = controls

  @Provides
  fun workRunLauncher(
    callbacks: OptionalCallbacks,
    adapter: DetachedWorkRunLauncher,
  ): WorkRunLauncher = callbacks.workRunLauncher ?: adapter

  @Provides
  fun hostAgentHomesPort(callbacks: OptionalCallbacks): HostAgentHomesPort =
    callbacks.hostAgentHomesPort ?: HostAgentHomesPort { emptyMap() }
}
