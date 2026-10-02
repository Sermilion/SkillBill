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
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.goalrunner.model.UnaddressedFinding
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.review.model.ReviewFindingVerdict
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeSharedEvidenceMeasurement
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttempt
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDeliveredProjectionRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

/**
 * The phase records, ledger, evidence, briefing, review-checkpoint, and gate-progress reads and writes one run makes.
 * Every operation is keyed by the run's workflow id, so an implementation decides where the records live.
 */
internal interface PhaseRunRecords : PhaseStepRecords, PhaseReviewRecords, PhaseLaunchRecords {
  /** Appends a ledger entry. */
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean

  /** Appends a quarantine entry. */
  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean

  /** The quarantine entries of [workflowId]. */
  fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>?

  /** The feature branch the run resolved. */
  fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch?

  /** The resolved branch of the earliest surviving child of goal [parentWorkflowId]. */
  fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch?

  /** Appends a checkpoint identity. */
  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean

  /** The checkpoint identities of [workflowId]. */
  fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>?

  /** Records the paths the run owns. */
  fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean

  /** The decompose terminal planning recorded for [workflowId], if the run decomposed. */
  fun loadDecomposeTerminal(workflowId: String): FeatureTaskRuntimeDecomposeTerminal?

  /** Records the decompose [terminal] reached at [planStepId]. Returns false when the write did not apply. */
  fun recordDecomposeTerminal(
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean
}

/** The step state, completion, and producer-output records of one run. */
internal interface PhaseStepRecords {
  /** Records a rejected producer output, returning the write that names the retained evidence. */
  fun recordRejectedOutput(
    request: RejectedOutputDiagnosticRequest,
    producerGeneration: Int = 0,
  ): FeatureTaskRuntimeRejectedOutputWrite

  /** Retains the raw output a producer step emitted. */
  fun retainProducerOutput(evidence: ProducerOutputEvidence)

  /** Reads the output a producer step retained. */
  fun producerOutput(args: ProducerOutputQueryArgs): FeatureTaskRuntimeProducerOutputRead

  /** Records a step state. Returns false when the write did not apply. */
  fun recordPhaseState(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  /** Records a required step start, returning the rejection when the write did not apply. */
  fun recordRequiredPhaseStart(request: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite

  /** Records a completed step atomically with its output. Returns false when the write did not apply. */
  fun recordCompletedPhase(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  /** Records an implementation attempt that ended without output. */
  fun recordIncompleteImplementationAttempt(request: FeatureTaskRuntimePhaseStateRequest): Boolean

  /** The recorded implementation attempts of [workflowId]. */
  fun loadImplementationAttempts(workflowId: String): List<FeatureTaskRuntimeImplementationAttempt>?

  /** The step records of [workflowId], keyed by step id. */
  fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord>?

  /** The ledger entries of [workflowId]. */
  fun loadPhaseLedger(workflowId: String): List<FeatureTaskRuntimePhaseLedgerEntry>?
}

/** The review pass, finding ledger, verification checkpoint, and review-generation records of one run. */
internal interface PhaseReviewRecords {
  /** Completes a goal review step atomically with its pass result. */
  fun completeGoalReviewPhase(completion: GoalReviewPhaseCompletionRequest): Boolean

  /** Invalidates the reviewed generation by tombstoning [reviewStepId], returning the new generation. */
  fun persistReviewGenerationInvalidation(
    workflowId: String,
    reviewStepId: String,
  ): Int?

  /** Invalidates the record a quarantined producer step left for one loop iteration. */
  fun invalidateQuarantinedProducerRecord(
    workflowId: String,
    producerPhaseId: String,
    loopId: String,
    edgeIteration: Int,
  ): Boolean

  /** The finding verdicts recorded for the review [output]. */
  fun recordedFindingVerdicts(output: Map<String, Any?>): List<ReviewFindingVerdict>

  /** The unaddressed-finding ledger of [workflowId]. */
  fun fetchUnaddressedLedger(workflowId: String): List<UnaddressedFinding>

  /** Appends the findings verification rejected in review pass [passNumber]. */
  fun appendRejectedVerificationFindings(
    workflowId: String,
    passNumber: Int,
    rejected: List<UnaddressedFinding>,
  )

  /** The finding-verification checkpoint of [workflowId]. */
  fun loadFindingVerificationCheckpoint(workflowId: String): List<FeatureTaskRuntimeFindingVerificationDisposition>?

  /** The finding-verification boundary selection of [workflowId]. */
  fun loadFindingVerificationBoundarySelection(
    workflowId: String,
  ): Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>?

  /** Persists the finding-verification boundary selection. */
  fun persistFindingVerificationBoundarySelection(
    workflowId: String,
    selections: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>,
  ): Boolean

  /** Persists the finding-verification checkpoint. */
  fun persistFindingVerificationCheckpoint(
    workflowId: String,
    dispositions: List<FeatureTaskRuntimeFindingVerificationDisposition>,
  ): Boolean
}

/** The briefing, handoff-projection, and quality-gate progress records a step launch reads and writes. */
internal interface PhaseLaunchRecords {
  /** Records the launch briefing of a step, returning the rejection when the write did not apply. */
  fun recordPhaseBriefing(
    workflowId: String,
    briefing: FeatureTaskRuntimePhaseLaunchBriefing,
    sharedEvidenceMeasurement: FeatureTaskRuntimeSharedEvidenceMeasurement? = null,
    attempt: Int = 1,
  ): RequiredPhaseWrite

  /** Records a handoff projection the consumer step rejected. */
  fun recordProjectionRejection(
    workflowId: String,
    consumerPhaseId: String,
    error: InvalidFeatureTaskRuntimeHandoffProjectionError,
    repositoryCheckpointFingerprint: String?,
  ): Boolean

  /** Records a handoff projection rejection. */
  fun recordProjectionRejection(rejection: FeatureTaskRuntimeProjectionRejection): Boolean

  /** Validates the handoff declarations a launch reads. */
  fun validateHandoffDeclarations(declarations: List<PhaseHandoffProjectionDeclaration>)

  /** The projections delivered to consumer steps of [workflowId]. */
  fun loadDeliveredProjections(workflowId: String): Map<String, FeatureTaskRuntimeDeliveredProjectionRecord>?

  /** The validate gate progress of [workflowId]. */
  fun loadValidationGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?

  /** Persists the validate gate progress. */
  fun persistValidationGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )

  /** The build gate progress of [workflowId]. */
  fun loadBuildGateProgress(workflowId: String): FeatureTaskRuntimeValidationGateProgress?

  /** Persists the build gate progress. */
  fun persistBuildGateProgress(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  )
}
