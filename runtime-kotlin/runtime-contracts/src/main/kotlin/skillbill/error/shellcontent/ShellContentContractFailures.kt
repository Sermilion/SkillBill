package skillbill.error.shellcontent

import skillbill.error.core.FailureWireCode
import skillbill.error.core.FailureWireDecodeCode
import skillbill.error.core.GoalTelemetryRowFailureCode
import skillbill.error.core.JsonFailureCode
import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException

fun Throwable.isShellContentContractFailure(): Boolean {
  if (this is ShellContentContractException) return true
  val failureCode = (this as? SkillBillRuntimeException)?.code
  return failureCode is FailureWireCode ||
    failureCode is JsonFailureCode ||
    failureCode is FailureWireDecodeCode ||
    failureCode is ManifestFailureCode ||
    failureCode is SkillStagingFailureCode ||
    failureCode is ReviewContextFailureCode ||
    failureCode is AgentAddonFailureCode ||
    failureCode is GovernedReviewFailureCode ||
    failureCode is GoalTelemetryRowFailureCode ||
    failureCode is InstallFailureCode ||
    failureCode is FeatureTaskRuntimeFailureCode ||
    failureCode is WorkflowFailureCode
}

fun Throwable.isInvalidWorkflowStateFailure(): Boolean =
  (this as? SkillBillRuntimeException)?.code == WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA ||
    (this as? SkillBillRuntimeException)?.code == FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION
