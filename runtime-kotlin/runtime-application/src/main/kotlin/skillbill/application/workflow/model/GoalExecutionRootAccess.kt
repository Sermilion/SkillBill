package skillbill.application.workflow.model

import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import java.nio.file.Path

sealed interface GoalExecutionRootAccess {
  data class Ready(val path: Path, val recreated: Boolean) : GoalExecutionRootAccess

  data class Failed(val reason: String) : GoalExecutionRootAccess
}

data class GoalExecutionRootOptions(
  val recreateCompleted: Boolean = false,
  val revisionSubtaskId: Int? = null,
  val requireExistingBranch: Boolean = false,
  val ownership: GoalPlanningOwnership? = null,
  val parentWorkflowId: String? = null,
  val allowLegacyRecordedPath: Boolean = false,
)
