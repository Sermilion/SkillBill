package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorService
import skillbill.infrastructure.host.process.JdkMonitorProcessInspector
import skillbill.infrastructure.launcher.process.launch.JvmAgentRunChildDrain
import skillbill.infrastructure.workflow.monitor.GhMonitorCheckSource
import skillbill.infrastructure.workflow.monitor.GhMonitorPullRequestSource
import skillbill.infrastructure.workflow.monitor.GitMonitorRepairPublisher
import skillbill.infrastructure.workflow.monitor.GitMonitorResources
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.MonitorRepairPublisher
import skillbill.ports.monitor.StandaloneMonitorResources
import java.time.Clock

internal interface RuntimeStandaloneMonitorProvides {
  @Provides
  fun agentRunChildDrain(adapter: JvmAgentRunChildDrain): AgentRunChildDrain = adapter

  @Provides
  fun monitorProcessInspector(adapter: JdkMonitorProcessInspector): MonitorProcessInspector = adapter

  @Provides
  fun monitorPullRequestSource(adapter: GhMonitorPullRequestSource): MonitorPullRequestSource = adapter

  @Provides
  fun monitorCheckSource(adapter: GhMonitorCheckSource): MonitorCheckSource = adapter

  @Provides
  fun monitorRepairPublisher(adapter: GitMonitorRepairPublisher): MonitorRepairPublisher = adapter

  @Provides @RuntimeSingleton
  fun standaloneMonitorResources(adapter: GitMonitorResources): StandaloneMonitorResources = adapter

  @Provides
  fun standaloneMonitorService(
    source: MonitorPullRequestSource,
    checks: MonitorCheckSource,
    publisher: MonitorRepairPublisher,
    clock: Clock,
  ): StandaloneMonitorService = StandaloneMonitorService(source, checks, publisher, clock)
}
