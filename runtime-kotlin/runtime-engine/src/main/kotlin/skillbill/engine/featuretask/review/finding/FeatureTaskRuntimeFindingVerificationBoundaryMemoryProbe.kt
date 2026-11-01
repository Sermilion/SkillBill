package skillbill.engine.featuretask.review.finding

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingBoundaryMemorySection
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition

public fun FeatureTaskRuntimeFindingVerificationBoundaryMemory.validateDeliveredWithPersisted(
  sections: List<FeatureTaskRuntimeFindingBoundaryMemorySection>,
  dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  pending: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String? {
  val matchFailure = validatePersistedBoundarySelectionsMatch(sections, dispositions, persisted)
  if (matchFailure != null) return matchFailure
  return pendingDeliveredMismatch(pending, persisted)
}

internal fun pendingDeliveredMismatch(
  pending: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  persisted: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
): String? =
  pending.firstNotNullOfOrNull { (findingId, pendingSelections) ->
    val persistedForFinding =
      persisted[findingId]
        ?: return@firstNotNullOfOrNull missingResolvedBodiesMessage(findingId)
    if (!boundaryHeadingsMatch(persistedForFinding, pendingSelections)) {
      "finding verification disposition for $findingId must reuse the persisted " +
        "selected_boundary_headings verbatim; do not change heading selections after the initial settlement pass."
    } else {
      null
    }
  }

private fun missingResolvedBodiesMessage(findingId: String) =
  "finding verification disposition for $findingId selected boundary headings but resolved entry " +
    "bodies were not yet delivered; re-read the briefing after the runtime records selections."
