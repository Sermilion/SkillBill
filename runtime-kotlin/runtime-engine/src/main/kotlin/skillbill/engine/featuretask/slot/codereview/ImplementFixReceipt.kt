package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeCarriedFindings
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeOmittedFindingsRetryReason
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRemediationRoundNumberOrNull
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptFromProse
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptOmittedFindings
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepeatedUnresolvedBlockReason
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeUnresolvedFindings
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object ImplementFixReceipt {
  private const val WRITE_FAILURE_REASON =
    "the review persistence.state could not be updated with the repair receipt."

  fun settle(
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    val prose = auditProseValue(outputMap) ?: return PhaseStepOutputCheck.Accept
    val reviewState = state.goalReviewState() ?: return PhaseStepOutputCheck.Accept
    val anchor = anchor(context, reviewState) ?: return PhaseStepOutputCheck.Accept
    val refuted = refutedCarriedFindingIds(context, state, reviewState)
    val receipt =
      featureTaskRuntimeRepairReceiptFromProse(
        prose,
        featureTaskRuntimeCarriedFindings(reviewState, refuted),
        anchor.baseSha,
        anchor.roundNumber,
      )
    return settleReceipt(context, state, reviewState, receipt, refuted)
  }

  private fun settleReceipt(
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
    reviewState: GoalSubtaskReviewState,
    receipt: FeatureTaskRuntimeRepairReceipt,
    refuted: Set<String>,
  ): PhaseStepOutputCheck {
    val omitted = featureTaskRuntimeRepairReceiptOmittedFindings(receipt, reviewState, refuted)
    if (omitted.isNotEmpty()) return PhaseStepOutputCheck.Reject(featureTaskRuntimeOmittedFindingsRetryReason(omitted))
    return persist(context, state, receipt)?.let { reason -> PhaseStepOutputCheck.Block(reason) }
      ?: repeatedUnresolvedBlock(receipt, reviewState)
      ?: PhaseStepOutputCheck.Accept
  }

  private fun repeatedUnresolvedBlock(
    receipt: FeatureTaskRuntimeRepairReceipt,
    reviewState: GoalSubtaskReviewState,
  ): PhaseStepOutputCheck? {
    val unresolved = featureTaskRuntimeUnresolvedFindings(receipt) ?: return null
    val prior =
      reviewState.repairReceipts
        .filter { it.roundNumber < receipt.roundNumber }
        .maxByOrNull(FeatureTaskRuntimeRepairReceipt::roundNumber)
        ?.let(::featureTaskRuntimeUnresolvedFindings)
        ?.refs
        .orEmpty()
    return featureTaskRuntimeRepeatedUnresolvedBlockReason(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      unresolved.refs,
      prior,
      unresolved.detail,
    )?.let { reason -> PhaseStepOutputCheck.Block(reason, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION) }
  }

  private fun persist(
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
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
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
    reviewState: GoalSubtaskReviewState,
  ): Set<String> {
    val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
    return runCatching {
      state
        .unaddressedReviewFindings()
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
    context: PhaseStepOutputContext,
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

  private data class ReceiptAnchor(
    val baseSha: String,
    val roundNumber: Int,
  )
}
