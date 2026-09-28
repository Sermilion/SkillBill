package skillbill.error.featuretask

import skillbill.error.core.ShellContentContractException

class InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
  val reason: String,
) : ShellContentContractException("Invalid feature-task runtime execution plan: $reason")
