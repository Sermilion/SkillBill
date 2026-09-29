package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

/** Read-only progress and attempt facts for one run; mutation routes through [FeatureTaskRuntimeRunLoopTransitionOwner]. */
internal interface FeatureTaskRuntimeRunProgressObservations {
  val transitions: FeatureTaskRuntimeTransitionDeclaration

  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>

  val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>

  fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput?

  fun outputs(requiredPhaseIds: Collection<String> = emptyList()): List<FeatureTaskRuntimePhaseOutput>

  fun outputs(): List<FeatureTaskRuntimePhaseOutput> = outputs(emptyList())

  fun phasesRequiringDurableGateInvalidation(): Set<String>

  fun recordFor(phaseId: String): FeatureTaskRuntimePhaseRecord?

  fun explicitResumeStart(requestedPhaseId: String): ExplicitResumeStart

  fun isComplete(phaseId: String): Boolean

  fun completedPhaseIds(): List<String>

  fun fixLoopIterationFor(
    phaseId: String,
    absoluteIteration: Int,
  ): Int

  fun legacyLaunchSeamRejectionConsumedBudget(
    phaseId: String,
    currentReason: String,
  ): Boolean

  fun hasPriorRecord(phaseId: String): Boolean

  fun resumedFromPriorProcess(phaseId: String): Boolean

  fun persistedBlockedReason(phaseId: String): String?

  fun hasBranchSetupBlock(phaseId: String): Boolean

  fun edgeIterationCount(loopId: String): Int

  fun isLoopLiveClaimed(loopId: String): Boolean

  fun outputFor(phaseId: String): FeatureTaskRuntimePhaseOutput?

  fun nextIteration(phaseId: String): Int

  fun evidenceGeneration(generationScoped: Boolean): Int

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict

  fun spanBlockedByEntryGate(span: List<String>): Boolean

  fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict
}

/** Run-loop phase blocking inputs that need review-pass and resume metadata. */
internal interface FeatureTaskRuntimeRunLoopProgressObservations : FeatureTaskRuntimeRunProgressObservations {
  fun persistedBlockResume(
    phaseId: String,
    reason: String,
  ): PhaseBlockResume

  fun trailingNonOutputAttempts(
    phaseId: String,
    isProcessFailure: (String) -> Boolean,
  ): List<FeatureTaskRuntimeNonOutputAttempt>

  val currentReviewPassNumber: Int?

  val resumeRules: (String) -> PhaseResumeRules
}
