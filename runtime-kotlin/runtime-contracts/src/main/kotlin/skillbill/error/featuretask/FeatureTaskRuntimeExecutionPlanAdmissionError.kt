package skillbill.error.featuretask

import skillbill.error.core.ShellContentContractException

sealed class FeatureTaskRuntimeExecutionPlanAdmissionError(
  val reasonCode: String,
) : ShellContentContractException(
    "Durable execution plan refused: $reasonCode. Retain the original workflow and its evidence. " +
      "Inspect status and use a compatible runtime or a separately reviewed semantic mapping.",
  )

class MissingFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError("missing_descriptor")

class CorruptFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError("corrupt_descriptor")

class UnsupportedFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError(
  "unsupported_descriptor",
)

class IncompatibleFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError(
  "incompatible_descriptor",
)
