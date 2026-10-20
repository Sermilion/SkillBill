package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phaserun.qa.qaReport
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
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
      previous?.report,
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
    val receipt = JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.RECEIPT]) ?: return null
    val verdict =
      QaVerdict.fromWire(receipt[QaReceiptPayloadKeys.VERDICT] as? String)
        ?: throw invalidQaReceipt("the settled QA receipt has no known verdict.")
    val reason = qaOutcomeReason(verdict, receipt, produced)
    return FeatureTaskQaOutcome(
      request.workflowId,
      outcome(verdict),
      reason,
      attempts,
      produced[QaPhaseOutputKeys.TREE_IDENTITY] as? String,
      "$recordedAt",
      mergedFindings(
        previous?.findings.orEmpty(),
        verdict,
        receipt,
        produced[QaPhaseOutputKeys.RECEIPT_REF] as? String,
      ),
      criteriaReport(receipt) ?: previous?.report,
    )
  }

  fun mergedFindings(
    previous: List<FeatureTaskQaFinding>,
    verdict: QaVerdict,
    receipt: Map<String, Any?>,
    receiptPath: String?,
  ): List<FeatureTaskQaFinding> {
    val reported = reportedFindings(receipt, receiptPath).associateBy(FeatureTaskQaFinding::id)
    val retested = verdict == QaVerdict.PASSED || verdict == QaVerdict.FINDINGS_OPEN
    val carried =
      previous.map { finding ->
        reported[finding.id]
          ?: finding.copy(status = if (retested) FeatureTaskQaFindingStatus.FIXED else finding.status)
      }
    val previousIds = previous.map(FeatureTaskQaFinding::id).toSet()
    return carried + reported.values.filterNot { it.id in previousIds }
  }

  private fun reportedFindings(
    receipt: Map<String, Any?>,
    receiptPath: String?,
  ): List<FeatureTaskQaFinding> =
    JsonCodec.anyToStringAnyMapList(receipt[QaReceiptPayloadKeys.FINDINGS]).orEmpty().mapNotNull { finding ->
      val id = (finding[QaReceiptPayloadKeys.ID] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
      FeatureTaskQaFinding(
        id = id,
        title = findingTitle(finding[QaReceiptPayloadKeys.OBSERVED] as? String),
        affectedCriteria = JsonCodec.anyToStringList(finding[QaReceiptPayloadKeys.AFFECTED_CRITERIA]).orEmpty(),
        evidencePaths =
          JsonCodec.anyToStringAnyMapList(finding[QaReceiptPayloadKeys.EVIDENCE_REFS]).orEmpty()
            .mapNotNull { it[QaReceiptPayloadKeys.PATH] as? String },
        receiptPath = receiptPath,
        status = FeatureTaskQaFindingStatus.NOT_FIXED,
      )
    }

  private fun findingTitle(observed: String?): String {
    val line = observed.orEmpty().lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    return if (line.length <= MAX_TITLE_LENGTH) {
      line
    } else {
      line.take(
        MAX_TITLE_LENGTH - ELLIPSIS.length,
      ).trimEnd() + ELLIPSIS
    }
  }

  private const val MAX_TITLE_LENGTH = 160
  private const val ELLIPSIS = "..."

  fun outcome(verdict: QaVerdict): StandaloneQaOutcome =
    when (verdict) {
      QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
      QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
      QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
      QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
    }

  fun criteriaReport(receipt: Map<String, Any?>): StandaloneQaReport? {
    if (receipt.isEmpty()) return null
    return decodeQaReceipt(receipt).qaReport()
  }
}
