package skillbill.engine.featuretask.qa.report

enum class QaVerdict(val wireValue: String) {
  PASSED("passed"),
  FINDINGS_OPEN("findings_open"),
  SKIPPED("skipped"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun fromWire(value: String?): QaVerdict? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaRetainedResource(
  val kind: String,
  val identity: String,
)

data class QaReportFinding(
  val id: String,
  val title: String,
  val section: String,
  val evidencePaths: List<String>,
)

data class QaReport(
  val text: String?,
  val verdict: QaVerdict,
  val reason: String?,
  val findings: List<QaReportFinding>,
  val reference: String?,
  val warnings: List<String> = emptyList(),
  val cleanup: String? = null,
) {
  val openFindingIds: List<String>
    get() = findings.map(QaReportFinding::id).takeIf { verdict == QaVerdict.FINDINGS_OPEN }.orEmpty()
}
