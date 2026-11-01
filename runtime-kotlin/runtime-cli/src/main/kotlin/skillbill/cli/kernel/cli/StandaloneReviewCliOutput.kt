package skillbill.cli.kernel.cli

import skillbill.application.review.model.ParallelCodeReviewResult

internal fun ParallelCodeReviewResult.standaloneReportText(): String {
  val register = output.trim()
  return register.ifBlank { "NO_FINDINGS" }
}
