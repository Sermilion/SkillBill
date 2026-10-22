package skillbill.ports.blockincident.model

import skillbill.ports.workflow.model.WorkflowFamily
import java.time.Instant

data class BlockedWorkflowWithoutIncident(
  val workflowId: String,
  val family: WorkflowFamily,
  val blockEnteredAt: Instant,
  val updatedAt: Instant,
)
