package skillbill.ports.agentrun.model

import java.time.Instant

data class AgentRunProcessSample(
  val pid: Long,
  val parentPid: Long?,
  val command: String?,
  val arguments: List<String>,
  val startedAt: Instant?,
)

enum class AgentRunCommandEventKind { STARTED, COMPLETED, FAILED }

data class AgentRunCommandEvent(
  val callId: String,
  val command: String,
  val kind: AgentRunCommandEventKind,
  val exitCode: Int?,
  val observedAt: Instant,
  val completionProcessSamples: List<AgentRunProcessSample>? = null,
)
