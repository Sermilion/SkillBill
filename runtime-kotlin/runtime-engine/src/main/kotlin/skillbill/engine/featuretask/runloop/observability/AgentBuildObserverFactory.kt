package skillbill.engine.featuretask.runloop.observability

import skillbill.ports.agentrun.model.AgentRunBuildObserver
import java.nio.file.Path

/** Creates the build observer owned by one agent launch. */
public fun interface AgentBuildObserverFactory {
  /** Creates an observer using lazily resolved run identities. */
  public fun observer(
    repoRoot: Path,
    resolveRunId: () -> String?,
    resolveParentRunId: () -> String?,
  ): AgentRunBuildObserver

  public companion object {
    public val NONE: AgentBuildObserverFactory = AgentBuildObserverFactory { _, _, _ -> AgentRunBuildObserver.NONE }
  }
}
