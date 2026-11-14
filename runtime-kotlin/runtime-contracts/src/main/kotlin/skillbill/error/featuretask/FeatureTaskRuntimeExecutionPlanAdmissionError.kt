package skillbill.error.featuretask

import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.error.shellcontent.WorkflowFailureCode

private val QUALITY_ROUTING_REFUSAL_CODES =
  setOf(
    AgentAddonFailureCode.INVALID_SELECTION,
    AgentAddonFailureCode.SELECTION_DRIFT,
  )

/**
 * A refused durable routing decision that must propagate out of failure containment instead of settling the
 * affected phase with a runtime-authored outcome: an incompatible retained execution plan, an add-on selection
 * refusal or drift, or an already-classified invalid quality execution.
 */
val SkillBillRuntimeException.isQualityRoutingRefusal: Boolean
  get() =
    this is IncompatibleFeatureTaskRuntimeExecutionPlanError ||
      code == WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION ||
      code in QUALITY_ROUTING_REFUSAL_CODES

sealed class FeatureTaskRuntimeExecutionPlanAdmissionError(
  val reasonCode: String,
  detail: String? = null,
) : ShellContentContractException(
    "Durable execution plan refused: $reasonCode. " +
      (detail?.let { "$it " } ?: "") +
      "Retain the original workflow and its evidence. " +
      "Inspect status and use a compatible runtime or a separately reviewed semantic mapping.",
  )

class MissingFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError("missing_descriptor")

class CorruptFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError("corrupt_descriptor")

class UnsupportedFeatureTaskRuntimeExecutionPlanError : FeatureTaskRuntimeExecutionPlanAdmissionError(
  "unsupported_descriptor",
)

class IncompatibleFeatureTaskRuntimeExecutionPlanError(detail: String? = null) :
  FeatureTaskRuntimeExecutionPlanAdmissionError("incompatible_descriptor", detail)
