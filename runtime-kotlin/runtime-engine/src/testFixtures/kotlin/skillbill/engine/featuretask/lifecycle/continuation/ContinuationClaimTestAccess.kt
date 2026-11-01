package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership

fun FeatureTaskContinuationLookupService.claimForTest(
  candidate: FeatureTaskContinuationCandidate,
  effectiveInputs: EffectiveGatePolicyInputs,
  expectedOwnership: FeatureTaskRuntimeWorkerOwnership? = null,
) = claim(candidate, effectiveInputs, expectedOwnership)
