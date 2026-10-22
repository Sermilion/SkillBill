package skillbill.application.workflow.model

import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import java.nio.file.Path

data class WorkflowUpdateRequest(
  val workflowId: String,
  val workflowStatus: String,
  val currentStepId: String = "",
  val stepUpdates: WorkflowStepUpdates? = null,
  val artifactsPatch: WorkflowArtifactPatch? = null,
  val planningResult: DecompositionPlanningResult? = null,
  val sessionId: String = "",
  val checkpointRoot: Path? = null,
)

enum class WorkflowFamilyKind {
  VERIFY,
  TASK_RUNTIME,
}
