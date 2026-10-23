package skillbill.engine.featuretask.runloop.state

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.featuretask.lifecycle.checkpoint.normalizeForAliasComparison
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress

object FeatureTaskRuntimeRunEvidenceOwnership {
  private val checkoutEvidenceRoot = normalizeForAliasComparison(LegacyProjectStatePaths.LEGACY_RUN_EVIDENCE_PREFIX)

  fun isRunEvidencePath(path: String): Boolean {
    val normalized = normalizeForAliasComparison(path)
    return normalized == checkoutEvidenceRoot || normalized.startsWith("$checkoutEvidenceRoot/")
  }

  fun isOwnedByRun(
    path: String,
    workflowId: String?,
  ): Boolean {
    if (workflowId.isNullOrBlank()) return false
    val owned =
      normalizeForAliasComparison(
        "$checkoutEvidenceRoot/${FeatureTaskRuntimeRunEvidenceAddress.pathSegment(workflowId)}",
      )
    val normalized = normalizeForAliasComparison(path)
    return normalized == owned || normalized.startsWith("$owned/")
  }
}
