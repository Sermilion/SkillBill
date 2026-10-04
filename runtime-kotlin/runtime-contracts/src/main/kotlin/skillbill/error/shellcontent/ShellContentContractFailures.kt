package skillbill.error.shellcontent

import skillbill.error.core.DurableInstallStateFailureCode
import skillbill.error.core.ExternalAddonFailureCode
import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.FailureWireCode
import skillbill.error.core.FailureWireDecodeCode
import skillbill.error.core.GoalTelemetryRowFailureCode
import skillbill.error.core.JsonFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionCode
import skillbill.error.featuretask.PhaseSlotFailureCode

fun Throwable.isShellContentContractFailure(): Boolean {
  val failureCode = (this as? SkillBillRuntimeException)?.code
  return when (failureCode) {
    is FailureWireCode,
    is JsonFailureCode,
    is FailureWireDecodeCode,
    is ManifestFailureCode,
    is SkillStagingFailureCode,
    is ReviewContextFailureCode,
    is AgentAddonFailureCode,
    is GovernedReviewFailureCode,
    is GoalTelemetryRowFailureCode,
    is ExternalPlatformPackFailureCode,
    is ExternalAddonFailureCode,
    is InstallFailureCode,
    is DurableInstallStateFailureCode,
    is FeatureTaskRuntimeFailureCode,
    is FeatureTaskRuntimeExecutionPlanAdmissionCode,
    is WorkflowFailureCode,
    -> true
    PhaseSlotFailureCode.VALIDATION_SCOPE,
    PhaseSlotFailureCode.UNKNOWN_SKELETON_DEFINITION,
    PhaseSlotFailureCode.UNKNOWN_PHASE_REVIEW_TARGET,
    PhaseSlotFailureCode.INTAKE_REQUIRED,
    PhaseSlotFailureCode.PULL_REQUEST_BRANCH_REFUSED,
    PhaseSlotFailureCode.UNKNOWN_PHASE_STRATEGY,
    -> true
    else -> false
  }
}

fun Throwable.isInvalidWorkflowStateFailure(): Boolean =
  (this as? SkillBillRuntimeException)?.code == WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA ||
    (this as? SkillBillRuntimeException)?.code == FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION
