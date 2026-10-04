package skillbill.engine.featuretask.runloop.core

import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.config.model.PhaseCompactionDirective
import skillbill.config.model.PhaseModelDirective
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.model.phase.ValidationFindingSetProjection
import skillbill.engine.featuretask.model.review.ReviewTarget
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.LaunchResult
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeProducerIteration
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy

internal data class RemediationCheckpointCommit(
  val commitSha: String,
  val parentSha: String?,
)

internal data class SubtaskCommitLedgerState(
  val commitSha: String?,
  val nextSequenceNumber: Int,
)

internal sealed interface PhaseSettlement {
  data object Stopped : PhaseSettlement

  data class Completed(
    val phaseId: String,
    val verdict: FeatureTaskRuntimeVerdict,
  ) : PhaseSettlement

  val completedPhaseId: String? get() = (this as? Completed)?.phaseId
  val completedVerdict: FeatureTaskRuntimeVerdict? get() = (this as? Completed)?.verdict

  companion object {
    fun stop(): PhaseSettlement = Stopped

    fun completed(
      phaseId: String,
      verdict: FeatureTaskRuntimeVerdict,
    ): PhaseSettlement = Completed(phaseId, verdict)
  }
}

internal data class PendingReentry(
  val phaseId: String,
  val loopId: String,
  val edgeIteration: Int,
  val drivingVerdict: FeatureTaskRuntimeVerdict,
  val expectedRepositoryCheckpoint: String? = null,
)

internal data class PhaseAttemptLoopCarryForward(
  var priorCorrection: PriorAttemptCorrection? = null,
)

internal class PhaseAttemptLoopState(
  var iteration: Int,
  var outputGateFailures: Int,
  var semanticIteration: Int,
  var continuationSegmentCount: Int,
  private var carryForward: PhaseAttemptLoopCarryForward = PhaseAttemptLoopCarryForward(),
) {
  var priorCorrection: PriorAttemptCorrection?
    get() = carryForward.priorCorrection
    set(value) {
      carryForward.priorCorrection = value
    }
}

internal data class CapturedPhaseOutput(
  val text: String,
  val bytes: ByteArray,
  val truncated: Boolean,
  val byteSize: Long,
  val sha256: String,
) {
  companion object {
    fun fromBytes(
      bytes: ByteArray,
      text: String = bytes.decodeToString(),
    ): CapturedPhaseOutput {
      val byteSize = bytes.size.toLong()
      return CapturedPhaseOutput(
        text = text,
        bytes = bytes,
        truncated = false,
        byteSize = byteSize,
        sha256 = RejectedOutputDiagnosticService.sha256(bytes),
      )
    }

    fun fromLaunchCaptured(captured: LaunchResult.Captured): CapturedPhaseOutput =
      CapturedPhaseOutput(
        text = captured.stdout,
        bytes = captured.stdoutBytes,
        truncated = captured.stdoutTruncated,
        byteSize = captured.stdoutByteSize,
        sha256 = captured.stdoutSha256,
      )
  }
}

internal data class FixLoopBranchContext(
  val run: PhaseRun,
  val attempt: AttemptResult,
  val loop: PhaseAttemptLoopState,
  val observability: FeatureTaskRuntimeRunObservability,
  val agentId: String,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
)

class ValidatedOutputCapture internal constructor(
  internal val run: PhaseRun,
  val iteration: Int,
  internal val captured: CapturedPhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  internal val fileManifest: FeatureTaskRuntimePhaseFileManifest,
) {
  val outputText: String get() = captured.text
  val outputBytes: ByteArray get() = captured.bytes
  val outputTruncated: Boolean get() = captured.truncated
  val outputByteSize: Long get() = captured.byteSize
  val outputSha256: String get() = captured.sha256
}

internal data class RejectedOutputTargeting(
  val phaseId: String,
  val agentId: String,
  val model: String,
  val path: String,
  val repairTurn: Int,
  val generationScoped: Boolean,
)

internal data class RecordRejectedOutputArgs(
  val run: PhaseRun,
  val iteration: Int,
  val rule: String,
  val reason: String,
  val captured: CapturedPhaseOutput,
  val targeting: RejectedOutputTargeting,
  val exhaustedFixLoop: Boolean? = null,
)

internal data class SettledOutputContext(
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
  val captured: CapturedPhaseOutput,
)

internal class SettleValidatedOutput(
  val run: PhaseRun,
  val iteration: Int,
  val output: SettledOutputContext,
  val settlementContext: PhaseOutputSettlementContext,
  val boundStep: PhaseStepBinding,
  val stepHooks: PhaseStepHooks,
) {
  val request get() = settlementContext.request
  val progress get() = settlementContext.progress
  val recorder get() = settlementContext.recorder
  val gitOperations get() = settlementContext.gitOperations
  val clock get() = settlementContext.clock
  val diagnostics get() = settlementContext.diagnostics
  val goalContinuationRecorder get() = settlementContext.goalContinuationRecorder
  val phaseSettlementService get() = settlementContext.phaseSettlementService
  val observability get() = settlementContext.observability
}

internal data class PhaseStateWriteArgs(
  val run: PhaseRun,
  val iteration: Int,
  val status: String,
  val finished: Boolean,
  val outputArtifact: String?,
)

internal data class PhaseStateRequestAttachments(
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput? = null,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
  val repositoryFingerprint: String? = null,
  val launched: LaunchedModelDirective? = null,
  val reviewRunId: String? = null,
)

internal data class PhaseStateRequestArgs(
  val write: PhaseStateWriteArgs,
  val extras: PhaseStateRequestAttachments = PhaseStateRequestAttachments(),
  val context: PhaseRunLoopAttemptCollaborators? = null,
)

internal data class PersistPhaseArgs(
  val write: PhaseStateWriteArgs,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  val launched: LaunchedModelDirective? = null,
  val reviewRunId: String? = null,
)

internal data class PhaseReviewPersistenceArgs(
  val run: PhaseRun,
  val iteration: Int,
  val observability: FeatureTaskRuntimeRunObservability,
  val fileManifest: FeatureTaskRuntimePhaseFileManifest,
)

internal sealed interface CommitPushFinalisation

data object CommitPushNotApplicable : CommitPushFinalisation

internal data class CommitPushSettled(
  val output: NormalizedFeatureTaskRuntimePhaseOutput,
) : CommitPushFinalisation

internal data class CommitPushBlocked(
  val reason: String,
) : CommitPushFinalisation

internal data class CheckpointRevisions(
  val base: String?,
  val head: String,
)

internal data class LaunchedModelDirective(
  val modelOverride: String?,
  val effortOverride: String?,
  val persistedEffort: String?,
)

internal data class LaunchRejectionMeasurementContext(
  val producerIteration: FeatureTaskRuntimeProducerIteration,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint?,
)

internal sealed interface LaunchPreparation

internal data class PreparedLaunchReady(
  val value: PreparedLaunch,
) : LaunchPreparation

internal data class LaunchMeasurementContextReady(
  val value: LaunchRejectionMeasurementContext,
) : LaunchPreparation

internal data class LaunchPreparationRejected(
  val result: LaunchResult,
) : LaunchPreparation

internal data class LaunchRequiredWriteRejected(
  val rejection: RequiredPhaseWrite.Rejected,
) : LaunchPreparation

internal data class PhaseRun(
  val phaseId: String,
  val declaration: FeatureTaskRuntimePhaseDeclaration,
  val resolvedAgent: FeatureTaskRuntimeResolvedPhaseAgent,
  val modelDirective: PhaseModelDirective?,
  val compaction: PhaseCompactionDirective?,
  val request: FeatureTaskRuntimeRunFacts,
  val specSource: SpecSource,
  val policy: PhaseStepPolicy,
  val reentry: PendingReentry? = null,
  val goalReviewInput: GoalSubtaskReviewInput? = null,
  val reviewTarget: ReviewTarget = ReviewTarget.LastCommit,
  val validationGateFindings: ValidationFindingSetProjection? = null,
  val validationGateTriagePlan: String? = null,
  val validationGateRepair: Boolean = false,
  val validationGateTriage: Boolean = false,
  val agentRunValidateFallback: Boolean = false,
  val validationGateRepairTurn: Int = 0,
)

internal data class PreLaunchBlock(
  val attemptCount: Int,
  val reason: String,
  val durableRecord: FeatureTaskRuntimePhaseRecord? = null,
)

internal data class PreparedLaunch(
  val briefing: FeatureTaskRuntimePhaseLaunchBriefing,
  val prompt: String,
)

internal data class RecordRejection(
  val rejectionClass: String,
  val rejectionDetail: String,
)
