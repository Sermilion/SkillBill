package skillbill.ports.review.model

import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding

data class ReviewIntegrationPassOutcome(
  val commitSequenceDigest: String,
  val terminalOutcome: ReviewIntegrationTerminalOutcome,
  val summarizedLaneCount: Int,
  val findings: List<ParallelReviewRawFinding> = emptyList(),
  val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
  val skipReason: String? = null,
  val launchBytes: Long = 0,
  val resultBytes: Long = 0,
  val modelTurns: Int = 0,
  val failureReason: String? = null,
) {
  init {
    require(commitSequenceDigest.isNotBlank()) { "Integration outcome must name the sequence it covered." }
    require(summarizedLaneCount >= 0)
    require(launchBytes >= 0 && resultBytes >= 0 && modelTurns >= 0)
    if (terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE) {
      require(!skipReason.isNullOrBlank()) {
        "A skipped integration pass must say why it was not applicable."
      }
      require(findings.isEmpty()) { "A skipped integration pass reports no findings." }
    }
  }

  val completed: Boolean get() = terminalOutcome == ReviewIntegrationTerminalOutcome.COMPLETED

  val durable: Boolean get() = terminalOutcome.isDurablyComplete

  companion object {
    fun skipped(
      commitSequenceDigest: String,
      reason: String,
    ): ReviewIntegrationPassOutcome =
      ReviewIntegrationPassOutcome(
        commitSequenceDigest = commitSequenceDigest,
        terminalOutcome = ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE,
        summarizedLaneCount = 0,
        skipReason = reason,
      )
  }
}
