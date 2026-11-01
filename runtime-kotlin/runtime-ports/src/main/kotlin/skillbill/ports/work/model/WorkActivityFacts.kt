package skillbill.ports.work.model

import java.time.Instant

data class WorkActivityAttempt(
  val workflowId: String,
  val subtaskId: Int,
  val name: String?,
  val workflowStatus: String?,
  val eventStatus: String?,
  val startedAt: Instant,
  val finishedAt: Instant?,
  val blockedReason: String?,
)

data class WorkActivityStop(
  val runId: String,
  val startedAt: Instant,
  val finishedAt: Instant?,
  val reason: String?,
  val blockedReason: String? = null,
)
