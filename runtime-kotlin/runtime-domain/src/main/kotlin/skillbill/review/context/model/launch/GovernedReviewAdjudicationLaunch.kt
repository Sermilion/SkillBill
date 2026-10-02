package skillbill.review.context.model.launch

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingVerdict

data class GovernedReviewAdjudicationLaunch(
  val packet: ReviewContextPacket,
  val finding: ParallelReviewMergedFinding,
  val stage1Verdict: ReviewFindingVerdict,
  val specIntentProjection: SpecIntentProjection,
  val citedRegion: ReviewCitedRegion,
  val evidenceSurfaceRules: String,
  val dependencyAllowlist: ReviewDependencyAllowlist,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(finding.fNumber.isNotBlank()) { "Adjudication launch requires a finding_ref." }
    require(stage1Verdict.findingRef == finding.fNumber) {
      "Adjudication launch stage-1 verdict must belong to the same finding."
    }
    require(evidenceSurfaceRules.isNotBlank()) { "Adjudication launch requires evidence-surface rules." }
    require(brokerId.isNotBlank()) { "Adjudication launch requires a broker id." }
    require(isolation == ReviewConversationIsolation.FRESH)
    require(dependencyAllowlist.normalized.all { it in packet.dependencyAllowlist.normalized }) {
      "Adjudication launch dependency allowlist escapes the packet allowlist."
    }
  }
}
