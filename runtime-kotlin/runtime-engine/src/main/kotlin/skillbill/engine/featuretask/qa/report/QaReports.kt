package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaSkipReason

public object QaReports {
  public fun skipped(
    reason: QaSkipReason,
    detail: String,
  ): QaReport = QaReport(detail, QaVerdict.SKIPPED, reason.wireValue, emptyList(), null)

  public fun incomplete(sentence: String): QaReport =
    QaReport(sentence, QaVerdict.INCOMPLETE, null, emptyList(), null, listOf(sentence))

  public fun missing(sentence: String): QaReport =
    QaReport(null, QaVerdict.INCOMPLETE, null, emptyList(), null, listOf(sentence))

  internal fun withRetainedResources(
    report: QaReport,
    resources: List<QaRetainedResource>,
    guidance: String,
  ): QaReport {
    if (resources.isEmpty()) return report
    val retained = resources.joinToString(", ") { "${it.kind} ${it.identity}" }
    val note = "Cleanup: retained $retained. $guidance"
    return report.copy(cleanup = listOfNotNull(report.cleanup, note).joinToString("\n"))
  }
}
