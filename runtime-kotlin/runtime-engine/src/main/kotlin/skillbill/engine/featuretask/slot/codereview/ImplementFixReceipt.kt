package skillbill.engine.featuretask.slot.codereview

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeRepairReceiptMissing
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeRepairReceiptRejected
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeRepairReceiptValid
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeParseRepairReceipt
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRemediationRoundNumberOrNull
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptSettleRejection
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptShapeRejection
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseStepState
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

internal object ImplementFixReceipt {
  private const val REPAIR_RECEIPT_RULE = "repair-receipt"
  private const val WRITE_FAILURE_REASON =
    "the review persistence.state could not be updated with the repair receipt."

  fun settle(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    val produced = completedProducedOutputs(outputMap) ?: return PhaseStepOutputCheck.Accept
    val reviewState = state.goalReviewState() ?: return shapeCheck(produced)
    val anchor = anchor(context, reviewState) ?: return shapeCheck(produced)
    return when (
      val parsed =
        featureTaskRuntimeParseRepairReceipt(
          produced,
          anchor.baseSha,
          anchor.roundNumber,
          recordTruncation = { record -> RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, record) },
        )
    ) {
      FeatureTaskRuntimeRepairReceiptMissing -> PhaseStepOutputCheck.Accept
      is FeatureTaskRuntimeRepairReceiptRejected -> rejected(parsed.rejectionDetail)
      is FeatureTaskRuntimeRepairReceiptValid -> settleValid(context, state, parsed.receipt, reviewState)
    }
  }

  private fun completedProducedOutputs(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): Map<String, Any?>? =
    outputMap
      .takeIf {
        (it[SharedPayloadKeys.STATUS] as? String)?.let(WorkflowStepStatus::fromWire) == WorkflowStepStatus.COMPLETED
      }
      ?.let { JsonCodec.anyToStringAnyMap(it[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty() }

  private fun settleValid(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): PhaseStepOutputCheck =
    featureTaskRuntimeRepairReceiptSettleRejection(
      receipt,
      reviewState,
      refutedCarriedFindingIds(context, state, reviewState),
    )
      ?.let { detail -> rejected(detail) }
      ?: persist(context, state, receipt)?.let { reason -> PhaseStepOutputCheck.Block(reason) }
      ?: PhaseStepOutputCheck.Accept

  private fun persist(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    receipt: FeatureTaskRuntimeRepairReceipt,
  ): String? =
    runCatching { state.recordRepairReceipt(receipt) }.fold(
      onSuccess = { recorded -> if (recorded) null else WRITE_FAILURE_REASON },
      onFailure = { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "Feature-task-runtime could not persist the implement_fix repair receipt for issue " +
            "${context.request.issueKey}, workflow ${context.request.workflowId}.",
          error,
        )
        WRITE_FAILURE_REASON
      },
    )

  private fun refutedCarriedFindingIds(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    reviewState: GoalSubtaskReviewState,
  ): Set<String> {
    val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
    return runCatching {
      state.unaddressedReviewFindings()
        .asSequence()
        .filter { finding -> finding.reviewPassNumber == passNumber }
        .filter { finding -> finding.verificationDisposition == UNADDRESSED_FINDING_REJECTED_DISPOSITION }
        .mapNotNull { finding -> finding.findingId?.takeIf(String::isNotBlank) }
        .toSet()
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "Feature-task-runtime could not read the unaddressed-findings ledger for issue " +
          "${context.request.issueKey}, workflow ${context.request.workflowId}; repair-receipt coverage waives no " +
          "refuted finding for this round.",
        error,
      )
      emptySet()
    }
  }

  private fun anchor(
    context: PhaseAttemptEnvironment,
    reviewState: GoalSubtaskReviewState,
  ): ReceiptAnchor? {
    val baseSha = reviewState.remediationBaseSha
    val roundNumber = featureTaskRuntimeRemediationRoundNumberOrNull(reviewState)
    if (baseSha != null && roundNumber != null) return ReceiptAnchor(baseSha, roundNumber)
    val reason =
      if (baseSha == null) {
        "no durable remediation base sha was recorded for this round"
      } else {
        "the durable remediation round number is not yet established"
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      context.diagnostics,
      "Feature-task-runtime did not record the implement_fix repair receipt for issue " +
        "${context.request.issueKey}, workflow ${context.request.workflowId}: $reason. The remediation repair " +
        "ledger loses this round.",
    )
    return null
  }

  private fun shapeCheck(produced: Map<String, Any?>): PhaseStepOutputCheck =
    featureTaskRuntimeRepairReceiptShapeRejection(produced)?.let { detail -> rejected(detail) }
      ?: PhaseStepOutputCheck.Accept

  private fun rejected(detail: String): PhaseStepOutputCheck = PhaseStepOutputCheck.Reject(detail, REPAIR_RECEIPT_RULE)

  private data class ReceiptAnchor(val baseSha: String, val roundNumber: Int)
}
