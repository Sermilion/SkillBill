package skillbill.engine.featuretask.slot.codereview

import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeScopedReviewBaseline
import skillbill.engine.featuretask.slot.PhaseEntrySettlement
import skillbill.engine.featuretask.slot.PhaseLoopRules
import skillbill.engine.featuretask.slot.PhaseStepState
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object InlineReviewLoopRules : PhaseLoopRules {
  private const val REVIEW = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
  private const val REVIEW_FIX_LOOP = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID

  override fun reopenStaleSettledSteps(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ) {
    if (!cappedReviewIsStale(context, state)) return
    checkNotNull(state.persistReviewGenerationInvalidation(REVIEW)) {
      "Could not durably reopen the stale capped review for workflow '${context.request.workflowId}'."
    }
  }

  override fun invalidateStaleEvidence(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ) {
    if (!state.isEvidenceInvalidated(REVIEW)) return
    val generation =
      checkNotNull(state.persistReviewGenerationInvalidation(REVIEW)) {
        "Could not durably invalidate legacy review evidence for workflow '${context.request.workflowId}'."
      }
    state.advanceReviewGeneration(generation, REVIEW_FIX_LOOP, REVIEW)
  }

  override fun discardsResumedReentry(
    loopId: String,
    state: PhaseStepState,
  ): Boolean = loopId == REVIEW_FIX_LOOP && !state.isStepCompleted(REVIEW)

  override fun resumesInFlightReentry(loopId: String): Boolean = loopId == REVIEW_FIX_LOOP

  override fun reentryCheckpoint(
    loopId: String,
    state: PhaseStepState,
  ): String? = if (loopId == REVIEW_FIX_LOOP) state.reviewedCheckpointFingerprint(REVIEW) else null

  override fun entryBlockReason(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): String? =
    if (stepId == REVIEW && isGoalContinuationRun(context.request) && state.isStepCompleted(stepId)) {
      reconcileReservedReviewPass(stepId, context, state)
    } else {
      null
    }

  override fun settleWithoutLaunch(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): PhaseEntrySettlement? {
    if (stepId != REVIEW || !isGoalContinuationRun(context.request)) return null
    return runCatching { state.goalReviewState() }.fold(
      onSuccess = { reviewState ->
        reviewState
          ?.takeIf { it.reviewCapReached || it.reviewSkippedByUser }
          ?.let { settleCarriedForward(stepId, context, state, it) }
      },
      onFailure = { error -> carriedForwardBlock(error.message.orEmpty()) },
    )
  }

  override fun routedVerdict(
    stepId: String,
    verdict: FeatureTaskRuntimeVerdict,
    state: PhaseStepState,
  ): FeatureTaskRuntimeVerdict =
    if (stepId == REVIEW && state.goalReviewState()?.reviewCapReached == true) {
      FeatureTaskRuntimeVerdict.REVIEW_CAP_REACHED
    } else {
      verdict
    }

  private fun reconcileReservedReviewPass(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): String? =
    runCatching { state.goalReviewState() }.fold(
      onSuccess = { reviewState ->
        when {
          reviewState == null ->
            "Goal-subtask review persistence.state is missing while reconciling a completed review pass."
          reviewState.reservedPassNumber != null -> reconcileReservedReviewOutput(stepId, context, state)
          else -> null
        }
      },
      onFailure = { error ->
        "Goal-subtask review persistence.state is malformed while reconciling a completed review pass: " +
          error.message.orEmpty()
      },
    )

  private fun reconcileReservedReviewOutput(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): String? =
    state.completedStepPayload(stepId)?.let { output ->
      runCatching {
        context.outputValidator.validatePhaseOutput(output, sourceLabel = stepId).requireAcceptedOutput(stepId)
      }.fold(
        onSuccess = { accepted ->
          if (state.completeReservedReviewPass(output, accepted.normalizedOutput.envelopeWireMap())) {
            null
          } else {
            "Completed goal-subtask review could not persist its reserved pass."
          }
        },
        onFailure = { error ->
          "Completed goal-subtask review output cannot reconcile its reserved pass: " + error.message.orEmpty()
        },
      )
    } ?: "Completed goal-subtask review has no durable output to reconcile its reserved pass."

  private fun settleCarriedForward(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    reviewState: GoalSubtaskReviewState,
  ): PhaseEntrySettlement =
    runCatching { state.carriedForwardReviewResult() }.fold(
      onSuccess = { rawResult ->
        rawResult?.let { recordCarriedForward(stepId, context, state, it, reviewState) }
          ?: carriedForwardBlock(null)
      },
      onFailure = { error -> carriedForwardBlock(error.message.orEmpty()) },
    )

  private fun recordCarriedForward(
    stepId: String,
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
    rawResult: String,
    reviewState: GoalSubtaskReviewState,
  ): PhaseEntrySettlement =
    runCatching {
      val accepted =
        context.outputValidator.validatePhaseOutput(rawResult, stepId).requireAcceptedOutput(stepId)
      state.settleCarriedForwardReview(accepted)
    }.fold(
      onSuccess = { PhaseEntrySettlement.Completed(requireNotNull(reviewState.passResults.lastOrNull()).verdict) },
      onFailure = { error -> carriedForwardBlock(error.message.orEmpty()) },
    )

  private fun carriedForwardBlock(detail: String?): PhaseEntrySettlement =
    PhaseEntrySettlement.Blocked(
      if (detail == null) {
        "Goal-subtask review pass budget is exhausted but its durable raw review result is missing."
      } else {
        "Goal-subtask review pass budget is exhausted but its durable raw review result is malformed: $detail"
      },
    )

  private fun cappedReviewIsStale(
    context: PhaseAttemptEnvironment,
    state: PhaseStepState,
  ): Boolean {
    val request = context.request
    val goalBranch = request.goalContinuation?.goalBranch ?: return false
    val reviewState =
      state.goalReviewState()
        ?.takeIf { it.reviewCapReached || it.pausedForOperatorDecision }
        ?: return false
    val judgedDigest = reviewState.reviewedDeltaDigest ?: return true
    val gitOperations = context.phaseGates.gitOperations
    val resolved = state.resolvedBranch()
    val digests =
      listOfNotNull(reviewState.remediationBaseSha, reviewState.reviewBaseSha).distinct().mapNotNull { base ->
        val baseline =
          resolved?.let { FeatureTaskRuntimeScopedReviewBaseline.of(gitOperations, request.repoRoot, it, base) }
            ?: GoalSubtaskReviewBaseline(base, reviewState.baselineUntrackedPaths)
        gitOperations.buildGoalSubtaskReviewInput(request.repoRoot, baseline, goalBranch).input?.deltaDigest
      }
    return digests.isNotEmpty() && judgedDigest !in digests
  }
}
