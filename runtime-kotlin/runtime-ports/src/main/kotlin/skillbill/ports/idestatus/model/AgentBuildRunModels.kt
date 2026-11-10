package skillbill.ports.idestatus.model

import java.time.Instant

enum class AgentBuildOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  UNKNOWN("unknown"),
  ;

  companion object {
    fun fromWire(value: String?): AgentBuildOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class AgentBuildRunStart(
  val buildId: String,
  val runId: String,
  val parentRunId: String?,
  val repositoryIdentity: String,
  val command: String,
  val shortCommand: String,
  val tool: String,
  val projectKind: String,
  val pid: Long,
  val startedAt: Instant,
)

data class AgentBuildRun(
  val buildId: String,
  val runId: String,
  val parentRunId: String?,
  val repositoryIdentity: String,
  val command: String,
  val shortCommand: String,
  val tool: String,
  val projectKind: String,
  val pid: Long,
  val startedAt: Instant,
  val endedAt: Instant?,
  val outcome: AgentBuildOutcome?,
  val exitCode: Int?,
)

data class AgentBuildPrevious(
  val outcome: AgentBuildOutcome,
  val durationMs: Long,
  val endedAt: Instant,
)

data class AgentBuildHistory(
  val usualMs: Long?,
  val previous: AgentBuildPrevious?,
  val sampleCount: Int = 0,
)
