package skillbill.workflow.taskruntime.model.review

import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

data class FeatureTaskRuntimeReviewFinding(
  val severity: FeatureTaskRuntimeReviewSeverity,
  val message: String,
) {
  init {
    require(message.isNotBlank()) { "FeatureTaskRuntimeReviewFinding.message must be non-blank." }
  }
}

data class FeatureTaskRuntimeReviewVerdict(
  val findings: List<FeatureTaskRuntimeReviewFinding>,
) {
  val verdict: FeatureTaskRuntimeVerdict
    get() =
      if (findings.any { it.severity.requiresRemediation }) {
        FeatureTaskRuntimeVerdict.CHANGES_REQUESTED
      } else {
        FeatureTaskRuntimeVerdict.APPROVED
      }

  val remediationFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.requiresRemediation }

  val unresolvedFindings: List<FeatureTaskRuntimeReviewFinding>
    get() = findings.filter { it.severity.blocksAdvance }
}
