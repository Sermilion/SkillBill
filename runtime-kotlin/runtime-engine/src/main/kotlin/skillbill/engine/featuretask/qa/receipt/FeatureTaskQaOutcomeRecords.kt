package skillbill.engine.featuretask.qa.receipt

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.error.featuretask.qa.invalidQaReceipt
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
    )

  fun settled(
    request: FeatureTaskRuntimePhaseStateRequest,
    recordedAt: Instant,
  ): FeatureTaskQaOutcome? {
    val completed = request.finished && request.status.workflowStepStatus() == WorkflowStepStatus.COMPLETED
    val output = request.normalizedOutput.takeIf { completed } ?: return null
    val attempts = output.qaRepairState?.consumedAttempts ?: return null
    val produced = JsonCodec.anyToStringAnyMap(output.envelopeWireMap()[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty()
    val receipt = JsonCodec.anyToStringAnyMap(produced[QaPhaseOutputKeys.RECEIPT]) ?: return null
    val verdict =
      QaVerdict.fromWire(receipt[QaReceiptPayloadKeys.VERDICT] as? String)
        ?: throw invalidQaReceipt("the settled QA receipt has no known verdict.")
    val outcome =
      when (verdict) {
        QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
        QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
        QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
        QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
      }
    val reason =
      (receipt[QaReceiptPayloadKeys.SKIP_REASON] ?: receipt[QaReceiptPayloadKeys.INCOMPLETE_REASON]) as? String
    return FeatureTaskQaOutcome(
      request.workflowId,
      outcome,
      reason?.takeIf(String::isNotBlank),
      attempts,
      produced[QaPhaseOutputKeys.TREE_IDENTITY] as? String,
      "$recordedAt",
    )
  }
}
