package skillbill.goalrunner.model

import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

fun Any?.asGoalRunnerIntOrNull(): Int? =
  if (this == null) {
    null
  } else {
    asExactIntOrNull()
      ?: throw invalidWorkflowStateSchemaError("Goal-runner durable integer must be exact.")
  }
