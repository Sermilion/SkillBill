package skillbill.goalrunner.model

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull

fun Any?.asGoalRunnerIntOrNull(): Int? =
  if (this == null) {
    null
  } else {
    asExactIntOrNull()
      ?: throw InvalidWorkflowStateSchemaError("Goal-runner durable integer must be exact.")
  }
