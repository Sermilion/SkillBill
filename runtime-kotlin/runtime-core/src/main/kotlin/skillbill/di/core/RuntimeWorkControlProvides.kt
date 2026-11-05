package skillbill.di.core

import me.tatarka.inject.annotations.Provides
import skillbill.engine.goal.GoalNewWorkAdmissionPort
import skillbill.engine.goal.GoalPreflightPort
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goalrunner.intake.GoalIntakeAdmissionService
import skillbill.engine.goalrunner.preflight.GoalPreflightService
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.work.control.WorkControlService
import skillbill.infrastructure.launcher.work.DetachedWorkRunLauncher
import skillbill.ports.system.HostAgentHomesPort
import skillbill.ports.work.LaunchAgentHomesPort
import skillbill.ports.work.WorkIncidentRecoveryCommands
import skillbill.ports.work.WorkRunLauncher

internal interface RuntimeWorkControlProvides {
  @Provides
  fun goalRunnerStatusPort(service: GoalRunnerStatusService): GoalRunnerStatusPort = service

  @Provides
  fun goalPreflightPort(service: GoalPreflightService): GoalPreflightPort = service

  @Provides
  fun goalNewWorkAdmissionPort(service: GoalIntakeAdmissionService): GoalNewWorkAdmissionPort = service

  @Provides
  fun workIncidentRecoveryCommands(controls: WorkControlService): WorkIncidentRecoveryCommands = controls

  @Provides
  fun workRunLauncher(
    callbacks: OptionalCallbacks,
    adapter: DetachedWorkRunLauncher,
  ): WorkRunLauncher = callbacks.workRunLauncher ?: adapter

  @Provides
  fun launchAgentHomesPort(adapter: DetachedWorkRunLauncher): LaunchAgentHomesPort = adapter

  @Provides
  fun hostAgentHomesPort(callbacks: OptionalCallbacks): HostAgentHomesPort =
    callbacks.hostAgentHomesPort ?: HostAgentHomesPort { emptyMap() }
}
