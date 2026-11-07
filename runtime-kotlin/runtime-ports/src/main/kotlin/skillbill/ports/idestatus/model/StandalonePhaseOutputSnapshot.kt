package skillbill.ports.idestatus.model

import skillbill.ports.agentrun.activity.model.AgentRunActivity
import java.time.Instant

data class StandalonePhaseOutputSnapshot(
  val tail: List<String> = emptyList(),
  val latestLineAt: Instant? = null,
  val latestAttributedLines: Map<Int, String> = emptyMap(),
  val latestAttributedLineAt: Map<Int, Instant> = emptyMap(),
  val pendingLines: List<StandalonePhaseOutputLine> = emptyList(),
  val pendingActivity: List<StandalonePlanActivity> = emptyList(),
  val activities: List<StandalonePlanActivity> = emptyList(),
)

data class StandalonePhaseOutputLine(
  val id: String,
  val text: String,
  val at: Instant,
  val subtaskId: Int? = null,
  val sequence: Long = 0,
  val isRaw: Boolean = false,
)

data class StandalonePlanActivity(
  val phaseId: String,
  val at: Instant,
  val updatedAt: Instant,
  val event: AgentRunActivity,
)
