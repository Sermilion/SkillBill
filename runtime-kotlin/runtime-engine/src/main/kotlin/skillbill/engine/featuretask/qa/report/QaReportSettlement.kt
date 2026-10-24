package skillbill.engine.featuretask.qa.report

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.qa.QaWorkspacePort
import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.nio.file.Path

@Inject
class QaReportSettlement(
  private val workspaces: QaWorkspacePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun recordDiagnostics(report: QaReport) {
    report.warnings.forEach { warning ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa warning: seam=qa_report value_expected=consistent_report value_used=warned " +
          "reason=${warning.take(MAX_RECORD_WARNING)}",
      )
    }
    if (report.verdict == QaVerdict.SKIPPED) {
      val reason = report.reason ?: "agent_reported"
      diagnostics.info("qa skipped: seam=qa_report value_expected=executed value_used=$reason reason=$reason")
    }
  }

  internal fun observe(
    previous: QaRepairState,
    report: QaReport,
    qa: QaExecutionContext,
  ): QaRepairState {
    val reference = qa.workspace.reportPath.toString()
    val current =
      report.findings
        .map { finding -> QaOpenFinding(finding.id, emptyList(), listOf(reference)) }
        .takeIf { report.verdict == QaVerdict.FINDINGS_OPEN }
        .orEmpty()
    return previous.observe(current, report.retestsFindings, qa.expected.treeIdentity)
  }

  internal fun retainedFinding(request: QaRetainedFindingRequest): QaReportFinding? {
    val repoRoot = request.repoRoot
    val reference = request.reference
    val findingId = request.findingId
    val storedReportText = request.storedReportText
    val storedEvidencePaths = request.storedEvidencePaths
    val currentReport = request.currentReport

    fun currentReportFinding(): QaReportFinding? =
      currentReport?.findings?.firstOrNull { finding -> finding.id == findingId }?.let { finding ->
        finding.copy(evidencePaths = (finding.evidencePaths + storedEvidencePaths).distinct())
      }
    if (storedReportText != null) {
      val stored =
        QaReportParser.parse(storedReportText, "")
          .findings
          .firstOrNull { candidate -> candidate.id == findingId }
      if (stored != null) {
        return stored.copy(evidencePaths = (stored.evidencePaths + storedEvidencePaths).distinct())
      }
      recordReconstructionMiss(findingId, "persisted report section unavailable")
    }
    if (reference.isNullOrBlank()) {
      recordReconstructionMiss(findingId, "missing report reference")
      return currentReportFinding()
    }
    if (reference.endsWith("receipt.json")) {
      recordReconstructionMiss(findingId, "legacy receipt reference")
      return currentReportFinding()
    }
    return try {
      val source = workspaces.readRetainedReport(repoRoot, reference)
      if (source == null) {
        recordReconstructionMiss(findingId, "retained report unavailable")
        currentReportFinding()
      } else {
        val finding = retainedFinding(source.content, source.evidencePrefix, reference, findingId)
        if (finding == null) {
          recordReconstructionMiss(
            findingId,
            "finding heading unavailable",
          )
          currentReportFinding()
        } else {
          finding.copy(evidencePaths = (finding.evidencePaths + storedEvidencePaths).distinct())
        }
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      recordReconstructionMiss(findingId, "retained report unreadable")
      currentReportFinding()
    }
  }

  fun settle(qa: QaExecutionContext): QaReport =
    try {
      val source = workspaces.readReport(qa.workspace.reportPath)
      if (source == null) {
        QaReports.missing("QA agent wrote no report.md")
      } else {
        QaReportParser.parse(
          source.content,
          qa.expected.evidencePrefix,
          qa.workspace.reportPath.toString(),
          declaredKinds(qa),
        )
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      QaReports.incomplete("QA report was unreadable: ${failure.message.orEmpty()}")
    }

  private fun declaredKinds(qa: QaExecutionContext): Set<QaTargetKind> =
    (qa.target as? QaExecutionTarget.Supported)?.targets.orEmpty().flatMapTo(linkedSetOf()) { it.kinds }

  private companion object {
    const val MAX_RECORD_WARNING = 300

    fun reconstructionWarning(
      findingId: String,
      reason: String,
    ): String = "qa repair prompt reconstruction degraded: seam=qa_repair_prompt finding=$findingId reason=$reason"
  }

  private fun recordReconstructionMiss(
    findingId: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      reconstructionWarning(findingId, reason).take(MAX_RECORD_WARNING),
    )
  }

  private fun retainedFinding(
    content: String,
    evidencePrefix: String,
    reference: String,
    findingId: String,
  ): QaReportFinding? =
    QaReportParser.parse(content, evidencePrefix, reference)
      .findings
      .firstOrNull { candidate -> candidate.id == findingId }
}

internal data class QaRetainedFindingRequest(
  val repoRoot: Path,
  val reference: String?,
  val findingId: String,
  val storedReportText: String?,
  val storedEvidencePaths: List<String>,
  val currentReport: QaReport? = null,
)
