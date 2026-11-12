package skillbill.di.featuretask

import me.tatarka.inject.annotations.Provides
import skillbill.application.workflow.model.BlockedPhaseRetryArtifacts
import skillbill.engine.featuretask.slot.plan.PlanBundleFeasibilityBlockedRetry

internal interface RuntimeFeatureTaskRecoveryProvides {
  @Provides
  fun blockedPhaseRetryArtifacts(adapter: PlanBundleFeasibilityBlockedRetry): BlockedPhaseRetryArtifacts = adapter
}
