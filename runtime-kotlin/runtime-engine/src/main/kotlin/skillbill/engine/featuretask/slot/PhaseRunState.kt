package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.goalreview.ReviewPassResolution
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

/**
 * The run state a strategy and its runner read and write for one step call: the settlement target a step
 * attempt pins, launch observation, token accounting, the settled envelope an attempt recorded, and the review
 * pass, checkpoint, and completion facts a code_review strategy persists.
 */
interface PhaseRunState {
  /**
   * Prepares [input] for launch once the runner has captured the before-state. Returns the input to launch, or
   * null when preparation rejected the launch and the step must not launch.
   */
  fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? = input

  /** The settlement target the step prompt pins for [attempt]. */
  fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget

  /** The activity and worktree-edit observation attached to a launch of [stepName]. */
  fun launchObservation(stepName: String): PhaseLaunchObservation

  /** Records the estimated token usage of one launch of [stepName]. */
  fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  )

  /** Reads the envelope settled for [stepName] at [target], if any. */
  fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ): PhaseSettledEnvelopeRead

  /** The iteration the current step's next attempt records. */
  fun nextStepIteration(): Int

  /** Reserves the goal review pass, or reports carry-forward, in-flight, or missing review state. */
  fun reserveReviewPass(): GoalSubtaskReviewPassReservation

  /** The durable resolved branch of this workflow, if recorded. */
  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch?

  /** Builds and persists the goal review input scoped to the owned and untracked paths. */
  fun prepareGoalReviewInput(
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation

  /** The durable raw review result a carried-forward pass settles with, if recorded. */
  fun carriedForwardReviewResult(): String?

  /** Persists a carried-forward review result as the completed step. Returns a failure reason, or null. */
  fun completeCarriedForwardReview(
    iteration: Int,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
  ): String?

  /** The review pass number the current review attempt runs as. */
  fun reviewPassNumber(): Int

  /** The review run id the durable record holds for [passNumber], if any. */
  fun recordedReviewRunId(passNumber: Int): String?

  /** Records the review step as running for [iteration] with [reviewRunId]. */
  fun startReview(
    iteration: Int,
    reviewRunId: String,
  )

  /** Records the review briefing for [input] and the resolved review tier ahead of the launch. */
  fun prepareReviewBriefing(
    prompt: PhaseStepPromptSource,
    input: GoalSubtaskReviewInput,
  )

  /** Records the review launch start for [iteration]. */
  fun reviewLaunched(iteration: Int)

  /** Records the content identities of the files the review may have edited. */
  fun recordReviewContentIdentities()

  /** The unaddressed findings earlier review passes left in the ledger. */
  fun unaddressedReviewFindings(): List<UnaddressedFinding>

  /** The finding verdicts recorded for the findings in [envelope]. */
  fun recordedFindingVerdicts(envelope: Map<String, Any?>): List<ReviewFindingVerdict>

  /** The normalized envelope recorded for completed [stepId], if any. */
  fun completedStepEnvelope(stepId: String): FeatureTaskRuntimeWorkflowArtifactMap?

  /** The raw payload recorded for completed [stepId], if any. */
  fun completedStepPayload(stepId: String): String?

  /** The branch the run resolved in this session, if any. */
  fun resolvedBranchName(): String?

  /** The goal review passes the durable review state completed, if any review state is recorded. */
  fun completedReviewPassCount(): Int?

  /** The in-flight verify_findings dispositions the durable checkpoint holds, if any. */
  fun findingVerificationCheckpoint(): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  /** Persists the in-flight verify_findings [dispositions]. Returns whether they were persisted. */
  fun persistFindingVerificationCheckpoint(
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean

  /** The boundary headings an earlier verify_findings attempt selected per finding, if recorded. */
  fun verificationBoundarySelection(): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?

  /** Persists the boundary heading [selections] per finding. Returns whether they were persisted. */
  fun persistVerificationBoundarySelection(
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean

  /** Appends the [rejected] verification findings of review pass [passNumber] to the unaddressed ledger. */
  fun appendRejectedVerificationFindings(
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  )

  /** Records the review tier [resolution] resolved for this pass on the goal review state. */
  fun persistResolvedReviewTier(resolution: ReviewPassResolution)

  /** The durable goal review state of a goal-continuation run, or null outside a goal continuation. */
  fun goalReviewState(): GoalSubtaskReviewState?

  /** Upserts the implement_fix repair [receipt] into the goal review state. Returns whether it was recorded. */
  fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean

  /** Amends the review's worktree edits into the remediation checkpoint. Returns whether it was established. */
  fun amendReviewRemediationCheckpoint(): Boolean

  /** Retains the assembled review output as producer-output evidence for [iteration]. */
  fun retainReviewOutput(
    iteration: Int,
    outputText: String,
  )

  /** Persists the completed review step. Returns the block reason when persistence blocked, or null. */
  fun completeReview(
    iteration: Int,
    outputText: String,
    output: AcceptedFeatureTaskRuntimePhaseOutput,
    fileManifest: PhaseStepFileManifest,
  ): String?

  /** Records the completed step for [iteration]. */
  fun stepCompleted(iteration: Int)

  /** Persists a review preparation block, attributed to attempt [attemptCount]. */
  fun blockReviewPreparation(
    attemptCount: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    carriedOutput: AcceptedFeatureTaskRuntimePhaseOutput? = null,
  )

  /** Persists a block of the launched review step at [iteration]. */
  fun blockReviewStep(
    iteration: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    fileManifest: PhaseStepFileManifest? = null,
  )

  /** Whether [stepId] is complete in this run. */
  fun isStepCompleted(stepId: String): Boolean

  /** Whether the run state marked the durable evidence of [stepId] stale. */
  fun isEvidenceInvalidated(stepId: String): Boolean

  /** The repository checkpoint fingerprint the delivered review projection judged, if recorded. */
  fun reviewedCheckpointFingerprint(): String?

  /** Durably invalidates the review generation. Returns the new generation, or null when it could not persist. */
  fun persistReviewGenerationInvalidation(): Int?

  /**
   * Advances the in-memory review generation to [generation], resets the loop state the invalidated review left,
   * and drops a pending re-entry of [reentryLoopId].
   */
  fun advanceReviewGeneration(
    generation: Int,
    reentryLoopId: String,
  )

  /** Completes the reserved goal review pass from the durable review [output] and its [envelope]. */
  fun completeReservedReviewPass(
    output: String,
    envelope: Map<String, Any?>,
  ): Boolean

  /**
   * Records a carried-forward review [output] as the completed step ahead of dispatch, keeping the prior record's
   * agent and the active re-entry. Throws when the completed step cannot persist.
   */
  fun settleCarriedForwardReview(output: AcceptedFeatureTaskRuntimePhaseOutput)

  /** The durable gate progress of the running step: open findings, repairs used, and the captured triage plan. */
  fun loadGateProgress(): FeatureTaskRuntimeValidationGateProgress?

  /** Persists the gate [progress] of the running step. */
  fun persistGateProgress(progress: FeatureTaskRuntimeValidationGateProgress)
}

data class PhaseLaunchObservation(
  val activityStampSink: AgentRunActivityStampSink,
  val worktreeEditObserver: AgentRunWorktreeEditObserver,
)

sealed interface PhaseSettledEnvelopeRead {
  data object None : PhaseSettledEnvelopeRead

  data class Found(val envelope: FeatureTaskRuntimeWorkflowArtifactMap) : PhaseSettledEnvelopeRead

  data class Failed(val error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) : PhaseSettledEnvelopeRead
}
