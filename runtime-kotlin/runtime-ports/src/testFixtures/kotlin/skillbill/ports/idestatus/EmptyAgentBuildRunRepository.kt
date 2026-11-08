package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRun
import skillbill.ports.idestatus.model.AgentBuildRunStart
import java.time.Instant

object EmptyAgentBuildRunRepository : AgentBuildRunRepository {
  override fun start(start: AgentBuildRunStart) {
  }

  override fun settle(
    buildId: String,
    endedAt: Instant,
    outcome: AgentBuildOutcome,
    exitCode: Int?,
  ): Boolean = false

  override fun abandonOpen(runId: String): Int = 0

  override fun latestForRun(runIds: Set<String>): AgentBuildRun? = null

  override fun history(
    repositoryIdentity: String,
    command: String,
    startedBefore: Instant,
  ): AgentBuildHistory = AgentBuildHistory(usualMs = null, previous = null)
}
