package skillbill.application.install

import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanDraft
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlatformPackSnapshot
import skillbill.install.model.InstallPolicyInput
import skillbill.install.model.InstallStagingIntent
import skillbill.install.model.buildInstallPlanWireMap
import skillbill.ports.install.InstallPlanWireValidator
import skillbill.ports.install.plan.model.InstallPlanningFacts

internal fun InstallPlanningFacts.toPolicyInput(
  request: InstallPlanRequest,
  platformPacks: List<InstallPlatformPackSnapshot>,
  resolvedReviewFallbackSlug: String? = null,
): InstallPolicyInput =
  InstallPolicyInput(
    request = request,
    baseSkills = baseSkills,
    platformPacks = platformPacks,
    resolvedReviewFallbackSlug = resolvedReviewFallbackSlug,
    detectedAgentTargets = detectedAgentTargets,
    defaultAgentTargets = defaultAgentTargets,
  )

internal fun validatedInstallPlan(
  draft: InstallPlanDraft,
  staging: InstallStagingIntent,
  validator: InstallPlanWireValidator,
): InstallPlan {
  val plan = draft.toInstallPlan(staging)
  validator.validate(buildInstallPlanWireMap(plan))
  return plan
}
