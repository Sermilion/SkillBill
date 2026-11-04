package skillbill.engine.featuretask.slot.codereview

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeCarriedFindings
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeCompactFindingRef
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeOmittedFindingsRetryReason
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeProseMentions
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRemediationRoundNumberOrNull
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairEntriesFromProse
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptFromProse
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptOmittedFindings
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepeatedUnresolvedBlockReason
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeUnresolvedFindings
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.codereview.verify.VerifyFindingsEvidence
import skillbill.engine.featuretask.slot.state.PhaseImplementFixStepBinding
import skillbill.error.core.catchingNonCancellation
import skillbill.goalrunner.model.UNADDRESSED_FINDING_REJECTED_DISPOSITION
import skillbill.goalrunner.subtaskreview.GoalSubtaskReviewSummaryReducer
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.withoutRefutedFindings
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object ImplementFixReceipt {
  private const val MAX_REPLY_MENTIONS_LENGTH = 300
  private const val NON_GOAL_REVIEW_PASS = 1
  private const val NON_GOAL_FIXED_BODY = "Fixed in implement_fix."

  private const val WRITE_FAILURE_REASON =
    "the review persistence.state could not be updated with the repair receipt."

  fun settle(
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    comments: DiffCommentService? = null,
  ): PhaseStepOutputCheck {
    val prose = auditProseValue(outputMap) ?: return PhaseStepOutputCheck.Accept
    val reviewState =
      state.goalReviewState() ?: run {
        if (comments != null) replyOutsideGoal(context, state, comments, prose)
        return PhaseStepOutputCheck.Accept
      }
    val anchor = anchor(context, reviewState) ?: return PhaseStepOutputCheck.Accept
    val refuted = refutedCarriedFindingIds(context.diagnostics, context.request, state, reviewState)
    val receipt =
      featureTaskRuntimeRepairReceiptFromProse(
        prose,
        featureTaskRuntimeCarriedFindings(reviewState, refuted),
        anchor.baseSha,
        anchor.roundNumber,
      )
    val omitted = featureTaskRuntimeRepairReceiptOmittedFindings(receipt, reviewState, refuted)
    return if (omitted.isNotEmpty()) {
      PhaseStepOutputCheck.OweFindings(
        featureTaskRuntimeOmittedFindingsRetryReason(omitted),
        omitted.mapTo(linkedSetOf(), ::featureTaskRuntimeCompactFindingRef),
      )
    } else {
      val failure = persist(context, state, receipt)
      if (failure != null) {
        PhaseStepOutputCheck.Block(failure)
      } else {
        val passNumber = reviewState.passResults.lastOrNull()?.passNumber
        if (comments != null && passNumber != null) {
          val replies = receiptReplies(receipt.entries, prose, "Fixed in repair round ${receipt.roundNumber}.")
          postReceiptReplies(context, comments, passNumber, replies)
        }
        repeatedUnresolvedBlock(receipt, reviewState) ?: PhaseStepOutputCheck.Accept
      }
    }
  }

  fun carriedFindingsSection(
    diagnostics: RuntimeDiagnostics,
    request: FeatureTaskRuntimeRunFacts,
    state: PhaseImplementFixStepBinding,
  ): String {
    val reviewState = state.goalReviewState() ?: return ""
    val refuted = refutedCarriedFindingIds(diagnostics, request, state, reviewState)
    val carried = featureTaskRuntimeCarriedFindings(reviewState, refuted).ifEmpty { return "" }
    return buildString {
      appendLine()
      appendLine("## Carried findings (implement_fix)")
      appendLine(
        "The runtime carries exactly these review findings into this round. This list, not the wording of the " +
          "verify_findings report, decides what you owe. Name each finding by its id in your report and say " +
          "whether you fixed it, found that it needed no edit (with the reason), or left it open (with the reason).",
      )
      carried.forEach { finding ->
        appendLine("- ${featureTaskRuntimeCompactFindingRef(finding)} [${finding.severity}] ${finding.text}")
      }
      if (refuted.isNotEmpty()) {
        appendLine("Verification refuted ${refuted.sorted().joinToString(", ")}; do not fix or report on them.")
      }
    }
  }

  private fun replyOutsideGoal(
    context: PhaseStepOutputContext,
    state: PhaseImplementFixStepBinding,
    comments: DiffCommentService,
    prose: String,
  ) {
    val replies =
      catchingNonCancellation {
        val review =
          state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
            ?: return@catchingNonCancellation emptyList()
        val refuted =
          state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS)
            ?.let { VerifyFindingsEvidence.rejectedReviewFindings(context.request, state, it) }
            ?.findings
            .orEmpty()
            .mapNotNullTo(linkedSetOf()) { it.findingId?.takeIf(String::isNotBlank) }
        val carried =
          withoutRefutedFindings(
            GoalSubtaskReviewSummaryReducer.fromOutput(review, state.recordedFindingVerdicts(review)),
            refuted,
          )
        receiptReplies(featureTaskRuntimeRepairEntriesFromProse(prose, carried), prose, NON_GOAL_FIXED_BODY)
      }.getOrElse { error ->
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "Feature-task-runtime could not read the implement_fix outcomes for issue ${context.request.issueKey}, " +
            "workflow ${context.request.workflowId}; operation reply review outcomes.",
          error,
        )
        return
      }
    postReceiptReplies(context, comments, NON_GOAL_REVIEW_PASS, replies)
  }

  private fun receiptReplies(
    entries: List<FeatureTaskRuntimeRepairReceiptEntry>,
    prose: String,
    fixedFallback: String,
  ): List<ReceiptReply> =
    entries.map { entry ->
      when (entry.outcome) {
        FeatureTaskRuntimeRepairOutcome.ADDRESSED -> {
          val mentions =
            featureTaskRuntimeProseMentions(prose, entry.findingId).joinToString(" ").take(MAX_REPLY_MENTIONS_LENGTH)
          val body = if (mentions.isEmpty()) fixedFallback else "Fixed: $mentions"
          ReceiptReply(entry.findingId, DiffCommentOutcome.FIXED, body)
        }
        FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED -> {
          val reason = entry.noEditReason?.takeIf(String::isNotBlank) ?: "no edit required."
          ReceiptReply(entry.findingId, DiffCommentOutcome.SKIPPED, "Skipped: $reason")
        }
        FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED -> {
          val reason = entry.unresolvedReason?.takeIf(String::isNotBlank) ?: "no reason given."
          ReceiptReply(entry.findingId, DiffCommentOutcome.FAILED, "Failed to fix: $reason")
        }
      }
    }

  private fun postReceiptReplies(
    context: PhaseStepOutputContext,
    comments: DiffCommentService,
    passNumber: Int,
    replies: List<ReceiptReply>,
  ) {
    replies.forEach { (findingId, outcome, body) ->
      val result =
        catchingNonCancellation {
          comments.replyReviewOutcome(
            context.request.repoRoot,
            "review:${context.request.workflowId}:pass-$passNumber:$findingId",
            body,
            outcome,
          )
        }
      val error = result.exceptionOrNull()
      if (error != null || result.getOrNull() !is DiffCommentThreadResult.Saved) {
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "Feature-task-runtime could not reply ${outcome.name.lowercase()} to finding $findingId " +
            "for issue ${context.request.issueKey}, workflow ${context.request.workflowId}; " +
            "operation reply ${outcome.name.lowercase()}.",
          error,
        )
      }
    }
  }

  private data class ReceiptReply(
    val findingId: String,
    val outcome: DiffCommentOutcome,
    val body: String,
  )

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
    catchingNonCancellation { state.recordRepairReceipt(receipt) }.fold(
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
    diagnostics: RuntimeDiagnostics,
    request: FeatureTaskRuntimeRunFacts,
    state: PhaseImplementFixStepBinding,
    reviewState: GoalSubtaskReviewState,
  ): Set<String> {
    val passNumber = reviewState.passResults.lastOrNull()?.passNumber ?: return emptySet()
    return catchingNonCancellation {
      state
        .unaddressedReviewFindings()
        .asSequence()
        .filter { finding -> finding.reviewPassNumber == passNumber }
        .filter { finding -> finding.verificationDisposition == UNADDRESSED_FINDING_REJECTED_DISPOSITION }
        .mapNotNull { finding -> finding.findingId?.takeIf(String::isNotBlank) }
        .toSet()
    }.getOrElse { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Feature-task-runtime could not read the unaddressed-findings ledger for issue " +
          "${request.issueKey}, workflow ${request.workflowId}; repair-receipt coverage waives no " +
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
