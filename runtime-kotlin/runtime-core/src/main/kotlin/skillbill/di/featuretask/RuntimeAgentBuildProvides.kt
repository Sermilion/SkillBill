package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.engine.featuretask.runloop.durable.AgentBuildRecorderFactory
import skillbill.engine.featuretask.runloop.observability.AgentBuildObserverFactory
import skillbill.infrastructure.host.system.JdkBuildRepositoryFiles
import skillbill.infrastructure.sqlite.idestatus.SessionAgentBuildRunRepository
import skillbill.ports.idestatus.AgentBuildRunRepository

internal interface RuntimeAgentBuildProvides {
  @Provides
  fun buildRepositoryFiles(adapter: JdkBuildRepositoryFiles): BuildRepositoryFiles = adapter

  @Provides
  fun agentBuildRunRepository(adapter: SessionAgentBuildRunRepository): AgentBuildRunRepository = adapter

  @Provides
  fun agentBuildObserverFactory(impl: AgentBuildRecorderFactory): AgentBuildObserverFactory = impl
}
