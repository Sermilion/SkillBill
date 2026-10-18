package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult

@Inject
class PlanWorkflowLookup(
  private val lookupService: FeatureTaskContinuationLookupService,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
) {
  fun lookup(
    issueKey: String,
    repositoryIdentity: String,
    workflowId: String?,
  ): FeatureTaskContinuationLookupResult = lookupService.lookupPlan(issueKey, repositoryIdentity, workflowId)

  fun lookupAfterCrashRecovery(
    issueKey: String,
    repositoryIdentity: String,
  ): FeatureTaskContinuationLookupResult {
    val found = lookup(issueKey, repositoryIdentity, null)
    if (found !is FeatureTaskContinuationLookupResult.AlreadyRunning) return found
    crashReconciler.reconcile(found.candidate.workflowId)
    return lookup(issueKey, repositoryIdentity, null)
  }
}
