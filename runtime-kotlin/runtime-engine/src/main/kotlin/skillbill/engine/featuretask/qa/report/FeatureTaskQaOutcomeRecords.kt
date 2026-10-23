package skillbill.engine.featuretask.qa.report

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import java.time.Instant

internal object FeatureTaskQaOutcomeRecords {
  fun attempted(
    workflowId: String,
    previous: FeatureTaskQaOutcome?,
    recordedAt: Instant,
  ): FeatureTaskQaOutcome =
    FeatureTaskQaOutcome(
      workflowId,
      StandaloneQaOutcome.ATTEMPTED,
      null,
      previous?.qaFixAttempts ?: 0,
      null,
      "$recordedAt",
      previous?.findings.orEmpty(),
      previous?.reportText,
    )

  fun settled(
    request: FeatureTaskRuntimePhaseStateRequest,
    recordedAt: Instant,
    previous: FeatureTaskQaOutcome?,
  ): FeatureTaskQaOutcome? {
    val completed = request.finished && request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
    val output = request.normalizedOutput.takeIf { completed } ?: return null
    val attempts = output.qaRepairState?.consumedAttempts ?: return null
    val produced = JsonCodec.anyToStringAnyMap(output.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty()
    if (!hasQaOutput(produced)) return null
    val verdict = qaVerdictOf(produced) ?: QaVerdict.INCOMPLETE
    val report = reportFrom(produced)
    val reference = qaReportRef(produced)
    val reported =
      report?.let { reportedFindings(verdict, it, reference) }
        ?: legacyFindingsFrom(produced, verdict)
    return FeatureTaskQaOutcome(
      request.workflowId,
      outcome(verdict),
      qaOutcomeReason(verdict, produced),
      attempts,
      produced[QaPhaseOutputKeys.TREE_IDENTITY] as? String,
      "$recordedAt",
      mergedFindings(previous?.findings.orEmpty(), verdict, reported),
      report?.text,
    )
  }

  fun reportFrom(produced: Map<String, Any?>): QaReport? {
    val text = produced[QaPhaseOutputKeys.REPORT_TEXT] as? String
    return text?.let { report ->
      QaReportParser.parse(report, produced[QaPhaseOutputKeys.EVIDENCE_PREFIX] as? String ?: "")
    }
  }

  fun legacyFindingsFrom(
    produced: Map<String, Any?>,
    verdict: QaVerdict,
  ): List<FeatureTaskQaFinding> {
    val receipt = JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.LEGACY_RECEIPT]) ?: return emptyList()
    val reference = qaReportRef(produced)
    return QaLegacyReportAdapter.fromReceipt(receipt, verdict)?.findings.orEmpty().map { finding ->
      FeatureTaskQaFinding(
        finding.id,
        finding.title,
        finding.evidencePaths,
        reference,
        FeatureTaskQaFindingStatus.NOT_FIXED,
      )
    }
  }

  fun legacyDiagnosticsFrom(
    produced: Map<String, Any?>,
    verdict: QaVerdict,
  ): List<String> {
    val receipt = JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.LEGACY_RECEIPT]) ?: return emptyList()
    return QaLegacyReportAdapter.fromReceipt(receipt, verdict)?.diagnostics.orEmpty()
  }

  fun mergedFindings(
    previous: List<FeatureTaskQaFinding>,
    verdict: QaVerdict,
    reported: List<FeatureTaskQaFinding>,
  ): List<FeatureTaskQaFinding> {
    val byId = reported.associateBy(FeatureTaskQaFinding::id)
    val retested = verdict == QaVerdict.PASSED || verdict == QaVerdict.FINDINGS_OPEN
    val carried =
      previous.map { finding ->
        byId[finding.id]
          ?: finding.copy(status = if (retested) FeatureTaskQaFindingStatus.FIXED else finding.status)
      }
    val previousIds = previous.map(FeatureTaskQaFinding::id).toSet()
    return carried + byId.values.filterNot { it.id in previousIds }
  }

  fun reportedFindings(
    verdict: QaVerdict,
    report: QaReport,
    reportPath: String?,
  ): List<FeatureTaskQaFinding> =
    report.findings.takeIf { verdict != QaVerdict.PASSED }.orEmpty().map { finding ->
      FeatureTaskQaFinding(
        finding.id,
        finding.title,
        finding.evidencePaths,
        reportPath,
        FeatureTaskQaFindingStatus.NOT_FIXED,
      )
    }

  fun outcome(verdict: QaVerdict): StandaloneQaOutcome =
    when (verdict) {
      QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
      QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
      QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
      QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
    }
}
