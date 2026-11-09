package skillbill.infrastructure.sqlite.idestatus

import me.tatarka.inject.annotations.Inject
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRun
import skillbill.ports.idestatus.model.AgentBuildRunStart
import java.time.Instant

@Inject
class SessionAgentBuildRunRepository(private val database: DatabaseSessionFactory) : AgentBuildRunRepository {
  override fun start(start: AgentBuildRunStart) {
    database.transaction { it.agentBuildRuns.start(start) }
  }

  override fun settle(
    buildId: String,
    endedAt: Instant,
    outcome: AgentBuildOutcome,
    exitCode: Int?,
  ): Boolean = database.transaction { it.agentBuildRuns.settle(buildId, endedAt, outcome, exitCode) }

  override fun abandonOpen(runId: String): Int = database.transaction { it.agentBuildRuns.abandonOpen(runId) }

  override fun latestForRun(runIds: Set<String>): AgentBuildRun? =
    database.read { it.agentBuildRuns.latestForRun(runIds) }

  override fun buildsForRun(runIds: Set<String>): List<AgentBuildRun> =
    database.read { it.agentBuildRuns.buildsForRun(runIds) }

  override fun history(
    repositoryIdentity: String,
    command: String,
    startedBefore: Instant,
  ): AgentBuildHistory = database.read { it.agentBuildRuns.history(repositoryIdentity, command, startedBefore) }
}
