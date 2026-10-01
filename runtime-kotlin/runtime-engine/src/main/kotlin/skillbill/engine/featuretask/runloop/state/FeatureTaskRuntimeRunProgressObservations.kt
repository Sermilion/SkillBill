package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

/**
 * Read-only progress and attempt facts for one run; mutation routes through [FeatureTaskRuntimeRunTransitionOwner].
 */
internal interface FeatureTaskRuntimeRunProgressObservations {
  fun phase(phaseId: String): PhaseProgressObservation

  val transitions: FeatureTaskRuntimeTransitionDeclaration

  val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>

  val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>

  fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput?

  fun outputs(requiredPhaseIds: Collection<String> = emptyList()): List<FeatureTaskRuntimePhaseOutput>

  val phasesRequiringDurableGateInvalidation: Set<String>

  fun explicitResumeStart(requestedPhaseId: String): ExplicitResumeStart

  val completedPhaseIds: List<String>

  fun fixLoopIterationFor(
    phaseId: String,
    absoluteIteration: Int,
  ): Int

  fun legacyLaunchSeamRejectionConsumedBudget(
    phaseId: String,
    currentReason: String,
  ): Boolean

  fun loop(loopId: String): LoopProgressObservation

  val reviewEvidenceGeneration: Int

  fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict

  fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict

  fun spanBlockedByEntryGate(span: List<String>): Boolean
}

/** Run-loop phase blocking inputs that need review-pass and resume metadata. */
internal interface FeatureTaskRuntimeProgressSnapshotAccess : FeatureTaskRuntimeRunProgressObservations {
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

internal fun detachedProgressObservations(
  captured: FeatureTaskRuntimeRunState,
): FeatureTaskRuntimeProgressSnapshotAccess = DetachedProgressObservations(captured)

private class DetachedProgressObservations(
  private val captured: FeatureTaskRuntimeRunState,
) : FeatureTaskRuntimeProgressSnapshotAccess by captured

internal data class PhaseProgressObservation(
  val completed: Boolean,
  val hasPriorRecord: Boolean,
  val resumedFromPriorProcess: Boolean,
  val blockedReason: String?,
  val branchSetupBlocked: Boolean,
  val record: FeatureTaskRuntimePhaseRecord?,
  val output: FeatureTaskRuntimePhaseOutput?,
  val nextIteration: Int,
)

internal data class LoopProgressObservation(
  val iteration: Int,
  val liveClaimed: Boolean,
)

internal fun detachedOutput(output: FeatureTaskRuntimePhaseOutput): FeatureTaskRuntimePhaseOutput =
  output.copy(
    normalizedOutput =
      output.normalizedOutput?.let { normalized ->
        NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
          FeatureTaskRuntimeWorkflowArtifactMap.from(detachedEnvelope(normalized)),
        )
      },
  )

internal fun detachedEnvelope(normalized: NormalizedFeatureTaskRuntimePhaseOutput): Map<String, Any?> {
  return normalized.envelopeWireMap().entries.associate { (key, value) -> key to detachedJsonValue(value) }
}

internal fun detachedRecord(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseRecord =
  record.copy(
    fileManifestBefore = record.fileManifestBefore.toList(),
    fileManifestAfter = record.fileManifestAfter.toList(),
    fileManifestIntroduced = record.fileManifestIntroduced.toList(),
  )

internal fun detachedJsonValue(value: Any?): Any? =
  when (value) {
    is Map<*, *> -> value.entries.associate { (key, nested) -> key.toString() to detachedJsonValue(nested) }
    is List<*> -> value.map(::detachedJsonValue)
    is Set<*> -> value.mapTo(linkedSetOf(), ::detachedJsonValue)
    is Array<*> -> value.map(::detachedJsonValue)
    else -> value
  }
