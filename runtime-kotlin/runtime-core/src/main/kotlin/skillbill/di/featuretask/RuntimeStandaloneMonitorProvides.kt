package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.runtime.RuntimeSingleton
import skillbill.infrastructure.host.JdkMonitorProcessInspector
import skillbill.infrastructure.workflow.monitor.GhMonitorPullRequestSource
import skillbill.infrastructure.workflow.monitor.GitMonitorResources
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.StandaloneMonitorResources

internal interface RuntimeStandaloneMonitorProvides {
  @Provides
  fun monitorProcessInspector(adapter: JdkMonitorProcessInspector): MonitorProcessInspector = adapter

  @Provides
  fun monitorPullRequestSource(adapter: GhMonitorPullRequestSource): MonitorPullRequestSource = adapter

  @Provides @RuntimeSingleton
  fun standaloneMonitorResources(adapter: GitMonitorResources): StandaloneMonitorResources = adapter
}
