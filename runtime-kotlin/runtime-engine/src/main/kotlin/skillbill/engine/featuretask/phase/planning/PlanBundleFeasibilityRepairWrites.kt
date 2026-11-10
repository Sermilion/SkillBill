package skillbill.engine.featuretask.phase.planning

import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityRepairAppliedWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode

internal fun feasibilityRepairWriteResult(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityRepairAppliedWrite,
  stored: FeatureTaskRuntimePlanBundle?,
): PlanBundleFeasibilityStateWriteResult {
  val launch =
    current.activeLaunch?.takeIf { it.transitionId == request.transitionId }
      ?: return replayFeasibilityRepair(current, request, stored)
  if (stored == null || !stored.matchesFeasibilityIdentity(launch.baselineIdentity)) {
    return PlanBundleFeasibilityStateWriteResult.Refused("stored plan bundle differs from the retained repair baseline")
  }
  val matchingIntent =
    current.revision == request.expectedRevision &&
      launch.kind == PlanBundleFeasibilityLaunchKind.REPAIR && launch.intendedAfterIdentity == request.intendedIdentity
  val matchingResult =
    request.state.identity == request.intendedIdentity && request.state.activeLaunch == null &&
      request.state.mode == PlanBundleFeasibilityMode.REVIEW &&
      request.bundle.matchesFeasibilityIdentity(request.intendedIdentity)
  return if (!matchingIntent || !matchingResult) {
    PlanBundleFeasibilityStateWriteResult.Refused("repair intent or expected revision changed")
  } else {
    planBundleFeasibilityWriteResult(
      current,
      PlanBundleFeasibilityStateWrite(
        request.workflowId,
        request.phaseId,
        request.attempt,
        request.expectedRevision,
        request.transitionId,
        request.state,
      ),
    )
  }
}

private fun replayFeasibilityRepair(
  current: FeatureTaskRuntimePlanBundleFeasibilityState,
  request: PlanBundleFeasibilityRepairAppliedWrite,
  stored: FeatureTaskRuntimePlanBundle?,
): PlanBundleFeasibilityStateWriteResult {
  val settled = current.settledLaunches.singleOrNull { it.transitionId == request.transitionId }
  val storedMatches =
    stored != null && stored.parentSpecSha256 == request.bundle.parentSpecSha256 &&
      stored.manifestSha256 == request.bundle.manifestSha256 &&
      stored.subtasks.map { it.id to it.specPath } == request.bundle.subtasks.map { it.id to it.specPath } &&
      stored.matchesFeasibilityIdentity(request.intendedIdentity)
  val settledMatches =
    settled?.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED &&
      settled.intendedAfterIdentity == request.intendedIdentity && current.identity == request.intendedIdentity
  return if (settledMatches && storedMatches && request.bundle.matchesFeasibilityIdentity(request.intendedIdentity)) {
    PlanBundleFeasibilityStateWriteResult.Replayed(current, checkNotNull(settled).outcome.wireValue)
  } else {
    PlanBundleFeasibilityStateWriteResult.Refused("repair intent is not active")
  }
}

internal fun FeatureTaskRuntimePlanBundle.matchesFeasibilityIdentity(
  identity: PlanBundleFeasibilityIdentity,
): Boolean {
  val parent = identity.rawParentText ?: return false
  val manifest = identity.rawManifestText ?: return false
  if (parentSpecSha256 != sha256HexUtf8(parent) || manifestSha256 != sha256HexUtf8(manifest)) return false
  val matchingChildren = subtasks.map { it.id to it.specPath } == identity.subtasks.map { it.id to it.specPath }
  return matchingChildren &&
    subtasks.all { bundleSubtask ->
      val text = identity.subtasks.firstOrNull { it.id == bundleSubtask.id }?.rawSpecText ?: return@all false
      val scopeHash = sha256HexUtf8(PlanBundleSpecText.scopeOf(text))
      val detailsHash = PlanBundleSpecText.detailsOf(text)?.let(::sha256HexUtf8)
      bundleSubtask.scopeSha256 == scopeHash && bundleSubtask.detailsSha256 == detailsHash
    }
}
