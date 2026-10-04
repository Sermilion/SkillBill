package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSource
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptContinuations
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProjectionFailureClassification
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewFinding

internal data class PhaseAttemptContext(
  val run: PhaseRun,
  val loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
  val transitionDeclaration: FeatureTaskRuntimeTransitionDeclaration,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
  val outputGateFailuresBefore: Int? = null,
)

internal data class PhaseAttemptAccumulatorContext(
  val attempt: PhaseAttemptContext,
)

internal data class TerminalOutputAttemptArgs(
  val run: PhaseRun,
  val iteration: Int,
  val reason: String,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val session: FeatureTaskRuntimeRunSessionObservations,
)

internal data class UnattributableRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val rejection: RecordRejection,
  val producer: String?,
)

internal data class WriteUnattributableRejectedEvidenceArgs(
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val run: PhaseRun,
  val rejection: RecordRejection,
  val detail: String,
  val evidence: ProducerOutputEvidence,
  val generationScoped: Boolean,
)

internal data class ProducerEvidenceRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val producer: String,
  val consumer: String,
  val producerGenerationScoped: Boolean,
)

internal data class QuarantineRecordRejectionArgs(
  val context: PhaseAttemptContext,
  val rejection: RecordRejection,
  val regeneration: PhaseAttemptContinuations.RecordRejectionRegenerationEdge,
  val producerEvidence: ProducerOutputEvidence,
  val producerGenerationScoped: Boolean,
)

internal data class LaunchPreparationRejectedArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val classification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
  val measurement: LaunchRejectionMeasurementContext,
  val message: String,
)

internal data class LaunchSeamRejectionArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val classification: FeatureTaskRuntimeProjectionFailureClassification,
  val sourceLabel: String,
  val fallbackProducerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
)

internal data class CompletionProjectionRejectionArgs(
  val run: PhaseRun,
  val iteration: Int,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val repositoryFingerprint: String?,
  val checksImmediateConsumerProjection: Boolean,
)

internal data class RepositoryCheckpointResolutionArgs(
  val recorder: PhaseRunRecords,
  val goalContinuationRecorder: PhaseRunGoal,
  val gitOperations: WorkflowGitOperations,
  val qualityGateCycles: RuntimeQualityGateCycles,
  val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val run: PhaseRun,
)

internal data class PersistAcceptedOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val repositoryFingerprint: String?,
)

internal data class PersistStandardAcceptedOutputArgs(
  val accepted: PersistAcceptedOutputArgs,
  val outputText: String,
)

internal data class BlockAndPersistArgs(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val loopId: String?,
  val edgeIteration: Int?,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  val payload: BlockAndPersistPayload,
)

internal data class BlockAndPersistPayload(
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val outputArtifact: String? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val rejectedOutput: String? = null,
  val childNeverLaunched: Boolean = false,
)

internal data class BlockAndPersistInPhaseArgs(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val failureDisposition: FeatureTaskRuntimeFailureDisposition,
  val payload: BlockAndPersistPayload,
)

internal data class PauseAtArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val phaseId: String,
  val reason: String,
  val resumableStep: String,
)

internal data class CapExhaustionReasonArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val recorder: PhaseRunRecords,
  val loopId: String,
  val edgeIteration: Int,
  val verdict: FeatureTaskRuntimeVerdict,
  val unresolvedFindings: List<FeatureTaskRuntimeReviewFinding>,
)

internal data class UnownedWorktreeCommitShaArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val diagnostics: RuntimeDiagnostics,
  val gitOperations: WorkflowGitOperations,
  val run: PhaseRun,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
)

internal data class MissingProducerAgentResolutionArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val run: PhaseRun,
  val iteration: Int,
  val consumer: String,
  val producer: String,
  val observability: FeatureTaskRuntimeRunObservability,
)

internal data class RunPhaseArgs(
  val phaseId: String,
  val request: FeatureTaskRuntimeRunFacts,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val observability: FeatureTaskRuntimeRunObservability,
  val specSource: SpecSource,
  val reentry: PendingReentry?,
)

internal data class CommitCheckpointArgs(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val intent: String,
  val ownedPaths: List<String>,
  val blockedReason: (String, String) -> String,
)

internal data class RecordCheckpointIdentityArgs(
  val precedingPhaseId: String,
  val branch: String,
  val loopId: String?,
  val ownedPaths: List<String>,
  val parentSha: String?,
  val commitSha: String,
  val blockedReason: (String, String) -> String,
)

internal data class SettleValidatedOutputPauseArgs(
  val capture: ValidatedOutputCapture,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val repositoryFingerprint: String?,
  val blockedDisposition: FeatureTaskRuntimeFailureDisposition,
)

internal data class ReconstructFixLoopBudgetBasesArgs(
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  val edgeIterationByLoop: Map<String, Int>,
  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
  val initialLedger: List<FeatureTaskRuntimePhaseLedgerEntry>,
  val completed: Set<String>,
  val gateInvalidatedPhases: Set<String>,
  val nextIteration: (String) -> Int,
  val resumeRules: (String) -> PhaseResumeRules,
)

internal data class RejectedOutputTargetingArgs(
  val run: PhaseRun,
  val phaseId: String,
  val agentId: String,
  val model: String,
  val path: String,
  val repairTurn: Int,
  val generationScoped: Boolean,
)

internal data class SettleValidatedOutputAfterFingerprintArgs(
  val capture: ValidatedOutputCapture,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val repositoryFingerprint: String?,
  val boundStep: PhaseStepBinding,
  val stepHooks: PhaseStepHooks,
  val reject: (String, String) -> AttemptResult,
)

internal data class RejectedOutputTargetingOverrides(
  val phaseId: String? = null,
  val agentId: String? = null,
  val model: String? = null,
  val path: String? = null,
  val repairTurn: Int? = null,
  val generationScoped: Boolean? = null,
)

internal fun defaultRejectedOutputTargetingArgs(
  run: PhaseRun,
  overrides: RejectedOutputTargetingOverrides = RejectedOutputTargetingOverrides(),
): RejectedOutputTargetingArgs {
  val phaseId = overrides.phaseId ?: run.phaseId
  return RejectedOutputTargetingArgs(
    run = run,
    phaseId = phaseId,
    agentId = overrides.agentId ?: run.resolvedAgent.resolvedAgentId,
    model = overrides.model ?: run.modelDirective?.model ?: "unspecified",
    path = overrides.path ?: "/",
    repairTurn = overrides.repairTurn ?: if (phaseId == run.phaseId) run.validationGateRepairTurn else 0,
    generationScoped = overrides.generationScoped ?: run.policy.generationScoped,
  )
}

internal data class PhaseBlockRequest(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val payload: BlockAndPersistPayload = BlockAndPersistPayload(),
  val failureDisposition: FeatureTaskRuntimeFailureDisposition =
    FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
)

internal data class CheckpointCommitMessageArgs(
  val branch: String,
  val phaseId: String,
  val loopId: String?,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val intent: String,
)

internal data class DeclaredLaunchArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val iteration: Int?,
  val priorCorrection: PriorAttemptCorrection?,
  val context: LaunchRejectionMeasurementContext,
  val prompt: PhaseStepPromptSource,
  val boundStep: PhaseStepBinding,
)

internal data class PauseAndPersistInPhaseArgs(
  val run: PhaseRun,
  val attemptCount: Int,
  val reason: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
)

internal data class SettleRecordRejectionArgs(
  val run: PhaseRun,
  val state: FeatureTaskRuntimeProgressSnapshotAccess,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
  val rejection: RecordRejection,
)

internal data class MissingProducerAgentBlockArgs(
  val run: PhaseRun,
  val iteration: Int,
  val consumer: String,
  val producer: String,
  val observability: FeatureTaskRuntimeRunObservability,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
)

internal data class WriteQuarantineRejectedOutputArgs(
  val run: PhaseRun,
  val producingIteration: Int,
  val rejection: RecordRejection,
  val producer: String,
  val producerEvidence: ProducerOutputEvidence,
  val producerGenerationScoped: Boolean,
)

internal data class FinalizeValidatedOutputAcceptanceArgs(
  val capture: ValidatedOutputCapture,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val repositoryFingerprint: String?,
  val boundStep: PhaseStepBinding,
  val stepHooks: PhaseStepHooks,
)

internal data class ShouldRetryPersistedBlockArgs(
  val phaseId: String,
  val durable: FeatureTaskRuntimePhaseRecord?,
  val resume: PhaseBlockResume,
  val reenterableRecordRejection: Boolean,
)

internal data class RecordFinalisedCheckpointIdentityArgs(
  val phaseId: String,
  val branch: String,
  val ledger: SubtaskCommitLedgerState,
  val commitSha: String,
  val stagedPaths: List<String>,
)

internal fun BlockAndPersistInPhaseArgs.withDisposition(
  failureDisposition: FeatureTaskRuntimeFailureDisposition,
): BlockAndPersistInPhaseArgs = copy(failureDisposition = failureDisposition)

internal fun phaseBlockArgs(
  run: PhaseRun,
  attemptCount: Int,
  reason: String,
  observability: FeatureTaskRuntimeRunObservability,
  payload: BlockAndPersistPayload = BlockAndPersistPayload(),
): BlockAndPersistInPhaseArgs =
  BlockAndPersistInPhaseArgs(
    run = run,
    attemptCount = attemptCount,
    reason = reason,
    observability = observability,
    failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    payload = payload,
  )
