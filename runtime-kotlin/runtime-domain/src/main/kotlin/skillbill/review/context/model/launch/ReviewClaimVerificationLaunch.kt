package skillbill.review.context.model.launch

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.requireRepositoryRelativePath

data class ReviewCitedRegion(
  val path: String,
  val startLine: Int,
  val endLine: Int,
) {
  init {
    requireRepositoryRelativePath(path)
    require(startLine >= 1) { "Cited region start_line must be a positive integer." }
    require(endLine >= startLine) { "Cited region end_line must be at or after start_line." }
  }
}

data class GovernedReviewVerificationLaunch(
  val packet: ReviewContextPacket,
  val finding: ParallelReviewMergedFinding,
  val citedRegion: ReviewCitedRegion,
  val evidenceSurfaceRules: String,
  val dependencyAllowlist: ReviewDependencyAllowlist,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(finding.fNumber.isNotBlank()) { "Verification launch requires a finding_ref." }
    require(evidenceSurfaceRules.isNotBlank()) { "Verification launch requires evidence-surface rules." }
    require(brokerId.isNotBlank()) { "Verification launch requires a broker id." }
    require(isolation == ReviewConversationIsolation.FRESH)
    require(dependencyAllowlist.normalized.all { it in packet.dependencyAllowlist.normalized }) {
      "Verification launch dependency allowlist escapes the packet allowlist."
    }
  }
}
