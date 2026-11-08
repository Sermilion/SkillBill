package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.workflow.taskruntime.model.qa.QaRunCoverage
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.model.qa.QaRunFinding
import skillbill.workflow.taskruntime.model.qa.QaRunRetainedResource
import java.nio.file.Path

internal object QaRunDetailsMapping {
  private val LEADING_REASON_TOKEN = Regex("^[a-z_]+")

  fun goal(
    tested: QaSettledRun,
    previous: QaRunDetails?,
  ): QaRunDetails? {
    val report = tested.report
    val reportPath = tested.reportPath
    val evidencePrefix = tested.evidencePrefix
    val invocationId = tested.invocationId
    val producedReason = tested.producedReason
    val skipReason = skipReasonOf(producedReason) ?: report?.reason
    val retained = previous?.retainedResources.orEmpty()
    if (report == null) {
      val hasHistory = previous?.let { it.preview != null || it.occurrences.isNotEmpty() } == true
      return if (skipReason == null && retained.isEmpty() && !hasHistory) {
        null
      } else {
        QaRunDetails(
          skipReason = skipReason,
          retainedResources = retained,
          preview = previous?.preview,
          historyAvailability = previous?.historyAvailability ?: "not_recorded",
          occurrences = previous?.occurrences.orEmpty(),
        )
      }
    }
    return reported(report).copy(
      reportPath = reportPath,
      invocationDirectory = invocationDirectoryOf(evidencePrefix, reportPath),
      skipReason = skipReason,
      retainedResources = retained,
      preview = previous?.preview,
      historyAvailability = previous?.historyAvailability ?: "not_recorded",
      occurrences =
        previous?.occurrences.orEmpty().map { occurrence ->
          if (occurrence.workspaceInvocationId == (invocationId ?: previous?.preview?.invocationId)) {
            occurrence.copy(screenshots = occurrence.screenshots.ifEmpty { report.screenshots })
          } else {
            occurrence
          }
        },
    )
  }

  fun standalone(
    report: QaReport,
    directory: String,
    reportPath: String,
    resources: List<QaRetainedResource>,
  ): QaRunDetails =
    reported(report).copy(
      reportPath = reportPath,
      invocationDirectory = directory,
      skipReason = report.reason,
      retainedResources = retained(resources),
      findings = findings(report),
    )

  fun verify(report: QaReport): QaRunDetails =
    reported(report).copy(skipReason = report.reason, findings = findings(report))

  fun retained(resources: List<QaRetainedResource>): List<QaRunRetainedResource> =
    resources.map { resource -> QaRunRetainedResource(resource.kind, resource.identity) }

  private fun reported(report: QaReport): QaRunDetails =
    QaRunDetails(
      assertions = report.assertions,
      coverage =
        report.coverage.map { line ->
          QaRunCoverage(line.kind.wireValue, line.verdict.wireValue, line.reason, line.backend?.wireValue)
        },
      temporaryEdits = report.temporaryEdits,
    )

  private fun findings(report: QaReport): List<QaRunFinding> =
    report.findings.map { finding ->
      QaRunFinding(
        finding.id,
        finding.title,
        finding.observed,
        finding.expected,
        finding.steps,
        finding.evidencePaths,
      )
    }

  private fun invocationDirectoryOf(
    evidencePrefix: String?,
    reportPath: String?,
  ): String? =
    evidencePrefix?.takeIf(String::isNotBlank)?.removeSuffix("/")
      ?: reportPath?.let { path -> Path.of(path).parent?.toString() }

  private fun skipReasonOf(producedReason: String?): String? {
    val token = producedReason?.let { LEADING_REASON_TOKEN.find(it)?.value }
    return QaSkipReason.fromWire(token)?.wireValue
  }
}
