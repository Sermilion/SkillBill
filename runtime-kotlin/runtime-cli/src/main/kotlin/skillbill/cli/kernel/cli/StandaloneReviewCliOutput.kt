package skillbill.cli.kernel.cli

import skillbill.application.review.model.ParallelCodeReviewResult

internal fun ParallelCodeReviewResult.standaloneReportText(): String {
  val register = output.trim()
  val finalLine = register.lineSequence().lastOrNull()?.trim()
  if (finalLine == "verdict: approved" || finalLine == "verdict: changes_requested") return register
  val verdict =
    rawOutput.lineSequence().map(String::trim).lastOrNull {
      it == "verdict: approved" || it == "verdict: changes_requested"
    }
  return buildString {
    append(if (register.isBlank()) "NO_FINDINGS" else register)
    verdict?.let {
      appendLine()
      append(it)
    }
  }
}
