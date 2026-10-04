package skillbill.engine.featuretask.lifecycle.branch

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.workflow.gitops.ProtectedBranches

internal fun requirePublishableBranch(
  branch: String?,
  baseBranch: String,
): String {
  val reason =
    when {
      branch == null -> "the checkout is on no branch."
      ProtectedBranches.protectedName(branch) != null -> "'$branch' is a protected branch."
      branch == baseBranch -> "'$branch' is the base branch."
      else -> null
    }
  if (reason != null) {
    throw SkillBillRuntimeException(
      PhaseSlotFailureCode.PULL_REQUEST_BRANCH_REFUSED,
      "Refusing to open a pull request from branch '${branch ?: "(detached)"}': $reason",
    )
  }
  return requireNotNull(branch)
}
