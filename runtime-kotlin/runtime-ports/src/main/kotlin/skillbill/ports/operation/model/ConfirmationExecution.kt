package skillbill.ports.operation.model

import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity

enum class ConfirmationExecutionState { ADMITTED, CLAIMED, RETIRED }

data class ConfirmationExecution(
  val executionId: Long,
  val token: String,
  val launchId: String,
  val state: ConfirmationExecutionState,
  val admissionOwner: FeatureTaskRuntimeProcessIdentity,
  val invocationId: String? = null,
  val childOwner: FeatureTaskRuntimeProcessIdentity? = null,
)
