package skillbill.error.shellcontent

import skillbill.error.core.FailureWireCode
import skillbill.error.core.GoalTelemetryRowFailureCode
import skillbill.error.core.ShellContentContractException
import skillbill.error.core.SkillBillRuntimeException

fun Throwable.isShellContentContractFailure(): Boolean {
  if (this is ShellContentContractException) return true
  val failureCode = (this as? SkillBillRuntimeException)?.code
  return failureCode is FailureWireCode ||
    failureCode is AgentAddonFailureCode ||
    failureCode is GovernedReviewFailureCode ||
    failureCode is GoalTelemetryRowFailureCode
}
