package skillbill.engine.featuretask.slot.state

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewInputPreparation
import skillbill.engine.featuretask.model.review.GoalSubtaskReviewPassReservation
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.review.ReviewPassResolution
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import java.time.Clock

/** The review pass reservation, launch, and tier facts a code_review step records before its review settles. */
internal interface PhaseReviewPassState {
  /** Reserves the goal review pass, or reports carry-forward, in-flight, or missing review state. */
  fun reserveReviewPass(): GoalSubtaskReviewPassReservation

  /** Builds and persists the goal review input scoped to the owned and untracked paths. */
  fun prepareGoalReviewInput(
    scopedUntrackedExclusions: List<String>?,
    ownedPathspec: List<String>,
  ): GoalSubtaskReviewInputPreparation

  /** The review pass number the current review attempt runs as. */
  val reviewPassNumber: Int

  /** The review run id the durable record holds for [passNumber], if any. */
  fun recordedReviewRunId(passNumber: Int): String?

  /** Records the review step as running for [iteration] with [reviewRunId]; a rejected required write is returned. */
  fun startReview(
    iteration: Int,
    reviewRunId: String,
  ): RequiredPhaseWrite

  /**
   * Records the review briefing for [input] and the resolved review tier ahead of the launch, returning the
   * rejection if the required briefing write did not apply.
   */
  fun prepareReviewBriefing(
    iteration: Int,
    prompt: PhaseStepPromptSource,
    input: GoalSubtaskReviewInput,
  ): RequiredPhaseWrite

  /** Records the review launch start for [iteration]. */
  fun reviewLaunched(iteration: Int)

  /** Records the content identities of the files the review may have edited. */
  fun recordReviewContentIdentities()

  /** Records the review tier [resolution] resolved for this pass on the goal review state. */
  fun persistResolvedReviewTier(resolution: ReviewPassResolution)

  /** The goal review passes the durable review state completed, if any review state is recorded. */
  val completedReviewPassCount: Int?

  /** Records the review run and its lane telemetry when the strategy did not already record it. */
  fun recordReviewRun(
    reviewRunId: String,
    result: ParallelCodeReviewResult,
    laneTelemetryRecorded: Boolean,
  )

  /**
   * The target every review pass reviews: [resolve] runs on the first pass and pinned state returns that result on
   * later passes.
   */
  fun pinnedReviewTarget(resolve: () -> ReviewTarget): ReviewTarget
}

/** The completion, carry-forward, and block writes that settle a code_review step. */
internal interface PhaseReviewSettlementState {
  /** The durable raw review result a carried-forward pass settles with, if recorded. */
  fun carriedForwardReviewResult(): String?

  /** Persists a carried-forward review result as the completed step. Returns a failure reason, or null. */
  fun completeCarriedForwardReview(
    iteration: Int,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): String?

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
    output: NormalizedFeatureTaskRuntimePhaseOutput,
    fileManifest: PhaseStepFileManifest,
  ): String?

  /** Persists a review preparation block, attributed to attempt [attemptCount]. */
  fun blockReviewPreparation(
    attemptCount: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    carriedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  )

  /** Persists a block of the launched review step at [iteration]. */
  fun blockReviewStep(
    iteration: Int,
    reason: String,
    disposition: FeatureTaskRuntimeFailureDisposition,
    fileManifest: PhaseStepFileManifest? = null,
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
  fun settleCarriedForwardReview(output: NormalizedFeatureTaskRuntimePhaseOutput)
}

/** Finding and goal-review observations shared by the review and remediation steps. */
internal interface PhaseReviewFindingObservations {
  /** The unaddressed findings earlier review passes left in the ledger. */
  fun unaddressedReviewFindings(): List<UnaddressedFinding>

  /** The finding verdicts recorded for the findings in [envelope]. */
  fun recordedFindingVerdicts(envelope: Map<String, Any?>): List<ReviewFindingVerdict>

  /** The durable goal review state of a goal-continuation run, or null outside a goal continuation. */
  fun goalReviewState(): GoalSubtaskReviewState?
}

/** The checkpoint and boundary selection written by the accepted verify_findings step. */
internal interface PhaseFindingVerificationState : PhaseReviewFindingObservations {
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
}

/** The repair receipt written by the accepted implement_fix step. */
internal interface PhaseRepairReceiptState : PhaseReviewFindingObservations {
  /** Upserts the implement_fix repair [receipt] into the goal review state. Returns whether it was recorded. */
  fun recordRepairReceipt(receipt: FeatureTaskRuntimeRepairReceipt): Boolean
}

/** The review generation a fix invalidates, and the checkpoint the delivered review judged. */
internal interface PhaseReviewGenerationState {
  /** The repository checkpoint fingerprint the delivered projection of [reviewStepId] judged, if recorded. */
  fun reviewedCheckpointFingerprint(reviewStepId: String): String?

  /** Persists the review tombstone before updating generation, invalidated state, and matching [reentryLoopId]. */
  fun invalidateReviewGeneration(
    reviewStepId: String,
    reentryLoopId: String,
  ): Int?
}

internal data class PhaseReviewExecutionContext(
  val gitOperations: PhaseRepositoryObservations,
  val clock: Clock,
)
