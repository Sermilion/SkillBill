package skillbill.engine.featuretask.slot.state

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProjectionRejection
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeNoChangePausePort
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeHandoffProjectionContext
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnMarker
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

/**
 * The phase records, ledger, evidence, briefing, review-checkpoint, and gate-progress reads and writes one run makes.
 * Every operation is keyed by the run's workflow id, so an implementation decides where the records live.
 */
public interface PhaseRunRecords :
  PhaseStepRecords,
  PhaseReviewRecords,
  PhaseLaunchRecords,
  PhaseGateProgressRecords,
  PhasePlanRecords,
  PhaseImplementationProgressRecords,
  FeatureTaskRuntimeNoChangePausePort {
  public fun loadQaRepairState(workflowId: String): QaRepairState?

  /** The operator retry recorded for this workflow, if it has not been consumed. */
  public fun loadOperatorBlockRetry(workflowId: String): FeatureTaskRuntimeOperatorBlockRetry?

  /** Appends a ledger entry. */
  public fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean

  /** Appends a quarantine entry. */
  public fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean

  /** The quarantine entries of [workflowId]. */
  public fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>?

  /** The feature branch the run resolved. */
  public fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch?

  /** The resolved branch of the earliest surviving child of goal [parentWorkflowId]. */
  public fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch?

  /** Appends a checkpoint identity. */
  public fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean

  /** The checkpoint identities of [workflowId]. */
  public fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>?

  /** Records the paths the run owns. */
  public fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean
}

/** The criterion progress and turn evidence the implement step keeps while it works one criterion at a time. */
public interface PhaseImplementationProgressRecords {
  /** The progress of [workflowId], or null when none was ever recorded. Present corrupt progress fails loudly. */
  public fun loadImplementationProgress(workflowId: String): FeatureTaskRuntimeImplementationProgress?

  /** Records one turn and its progress in one owned transaction, or rejects it when the expected state moved. */
  public fun recordImplementationTurn(write: FeatureTaskRuntimeImplementationTurnWrite): RequiredPhaseWrite

  /** The recorded implementation attempts of [workflowId], which carry the runtime-authored turn evidence. */
  public fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>?
}

public data class FeatureTaskRuntimeImplementationTurnWrite(
  val workflowId: String,
  val phaseId: String,
  val attemptNumber: Int,
  val agentId: String,
  val expectedProgress: FeatureTaskRuntimeImplementationProgress?,
  val progress: FeatureTaskRuntimeImplementationProgress,
  val marker: FeatureTaskRuntimeImplementationTurnMarker,
  val value: String,
)

/** The plan bundle snapshot and decompose terminal records planning makes for one run. */
public interface PhasePlanRecords {
  /** The decompose terminal planning recorded for [workflowId], if the run decomposed. */
  public fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal?

  /** The bundle snapshot planning recorded for [workflowId], with the settled plan of each subtask. */
  public fun loadPlanBundle(workflowId: String): FeatureTaskRuntimePlanBundle?

  /** Records the plan [bundle] snapshot. Returns false when the write did not apply. */
  public fun recordPlanBundle(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
  ): Boolean

  /**
   * Records the plan [bundle] snapshot together with the settled phase record of each planned subtask in
   * [unitRecords], keyed by subtask id, in one write. Returns false when the write did not apply.
   */
  public fun recordPlanBundleWithUnits(
    workflowId: String,
    bundle: FeatureTaskRuntimePlanBundle,
    unitRecords: Map<Int, FeatureTaskRuntimePhaseRecord>,
  ): Boolean

  /** Records the decompose [terminal] reached at [planStepId]. Returns false when the write did not apply. */
  public fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean
}

/** The step state, completion, and producer-output records of one run. */
public interface PhaseStepRecords {
  /** Records a rejected producer output, returning the write that names the retained evidence. */
  public fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite

  /** Retains the raw output a producer step emitted. */
  public fun retainProducerOutput(evidence: ProducerOutputEvidence)

  /** Reads the output a producer step retained. */
  public fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead

  /** Records a step state. Returns false when the write did not apply. */
  public fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  /** Records a required step start, returning the rejection when the write did not apply. */
  public fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite

  /** Records a completed step atomically with its output, retaining the rejection when it did not apply. */
  public fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite

  /** Records an implementation attempt that ended without output. */
  public fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  /** The step records of [workflowId], keyed by step id. */
  public fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>?

  /** The ledger entries of [workflowId]. */
  public fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>?
}

/** The review pass, finding ledger, verification checkpoint, and review-generation records of one run. */
public interface PhaseReviewRecords {
  /** Completes a goal review step atomically with its pass result. */
  public fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean

  /** Invalidates the reviewed generation by tombstoning [reviewStepId], returning the new generation. */
  public fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int?

  /** Invalidates the record a quarantined producer step left for one loop iteration. */
  public fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean

  /** The finding verdicts recorded for the review [output]. */
  public fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>

  /** The unaddressed-finding ledger of [workflowId]. */
  public fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding>

  /** Appends the findings verification rejected in review pass [passNumber]. */
  public fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  )

  /** The finding-verification checkpoint of [workflowId]. */
  public fun loadFindingVerificationCheckpoint(
    workflowId: String,
  ): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  /** The finding-verification boundary selection of [workflowId]. */
  public fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?

  /** Persists the finding-verification boundary selection. */
  public fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean

  /** Persists the finding-verification checkpoint. */
  public fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean
}

/** The briefing, handoff-projection, and quality-gate progress records a step launch reads and writes. */
public interface PhaseLaunchRecords {
  /** Records the launch briefing of a step, returning the rejection when the write did not apply. */
  public fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
    attempt: Int = 1,
  ): RequiredPhaseWrite

  /** Records a handoff projection the consumer step rejected. */
  public fun recordProjectionRejection(
    context: InvalidFeatureTaskRuntimeHandoffProjectionContext,
    repositoryCheckpointFingerprint: String?,
  ): Boolean

  /** Records a handoff projection rejection. */
  public fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean

  /** Validates the handoff declarations a launch reads. */
  public fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>)

  /** The projections delivered to consumer steps of [workflowId]. */
  public fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>?
}

public interface PhaseGateProgressRecords {
  /** The validate gate progress of [workflowId]. */
  public fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?

  /** Persists the validate gate progress. */
  public fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )

  /** The build gate progress of [workflowId]. */
  public fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?

  /** Persists the build gate progress. */
  public fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )
}

internal interface AcceptanceCriterionRepairCycleRecords {
  fun requiresExplicitStart(phaseId: String): Boolean

  fun isRepairStep(phaseId: String): Boolean

  fun settleUnsuccessfulRepair(
    phase: FeatureTaskRuntimePhaseStateRequest,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ): Boolean?
}
