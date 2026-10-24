package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.scaffold.model.QaTargetKind

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

enum class QaBackendAttribution(val wireValue: String) {
  REAL("real"),
  MOCKED("mocked"),
  NONE("none"),
  ;

  companion object {
    fun fromWire(value: String?): QaBackendAttribution? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaTargetCoverage(
  val kind: QaTargetKind,
  val verdict: QaVerdict,
  val reason: String?,
  val backend: QaBackendAttribution?,
)

data class QaReport(
  val text: String?,
  val verdict: QaVerdict,
  val reason: String?,
  val findings: List<QaReportFinding>,
  val reference: String?,
  val warnings: List<String> = emptyList(),
  val cleanup: String? = null,
  val coverage: List<QaTargetCoverage> = emptyList(),
  val assertionsReported: Boolean = true,
  val coverageWarnings: List<String> = emptyList(),
) {
  init {
    require(reason == null || QaSkipReason.fromWire(reason) != null) {
      "QaReport.reason must be a QaSkipReason wire value."
    }
  }

  val openFindingIds: List<String>
    get() = findings.map(QaReportFinding::id).takeIf { verdict == QaVerdict.FINDINGS_OPEN }.orEmpty()

  val retestsFindings: Boolean
    get() = verdict == QaVerdict.FINDINGS_OPEN || (verdict == QaVerdict.PASSED && assertionsReported)
}
