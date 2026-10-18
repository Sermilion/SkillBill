package skillbill.ports.taskruntime.model

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import java.nio.file.Path

data class AcceptanceCriterionRepairProjectionRequest(
  val repositoryRoot: Path,
  val workflowId: String,
  val state: AcceptanceCriterionRepairState,
)
