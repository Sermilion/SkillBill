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
public interface FeatureTaskRuntimeRunProgressObservations {
  public fun phase(phaseId: String): PhaseProgressObservation

  public val transitions: FeatureTaskRuntimeTransitionDeclaration

  public val initialRecords: Map<String, FeatureTaskRuntimePhaseRecord>

  public val settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>

  public fun validatedRecordToOutput(record: FeatureTaskRuntimePhaseRecord): FeatureTaskRuntimePhaseOutput?

  public fun outputs(requiredPhaseIds: Collection<String> = emptyList()): List<FeatureTaskRuntimePhaseOutput>

  public val phasesRequiringDurableGateInvalidation: Set<String>

  public fun explicitResumeStart(requestedPhaseId: String): ExplicitResumeStart

  public val completedPhaseIds: List<String>

  public fun fixLoopIterationFor(
    phaseId: String,
    absoluteIteration: Int,
  ): Int

  public fun legacyLaunchSeamRejectionConsumedBudget(
    phaseId: String,
    currentReason: String,
  ): Boolean

  public fun loop(loopId: String): LoopProgressObservation

  public val monitorFailedRepairCommitShas: Set<String>

  public val monitorFailureEvidenceUnavailable: Boolean

  public val reviewEvidenceGeneration: Int

  public fun durableVerdictFor(phaseId: String): FeatureTaskRuntimeVerdict

  public fun verdictFor(phaseId: String): FeatureTaskRuntimeVerdict

  public fun spanBlockedByEntryGate(span: List<String>): Boolean
}

/** Run-loop phase blocking inputs that need review-pass and resume metadata. */
public interface FeatureTaskRuntimeProgressSnapshotAccess : FeatureTaskRuntimeRunProgressObservations {
  public fun persistedBlockResume(
    phaseId: String,
    reason: String,
  ): PhaseBlockResume

  /** The reason of every durable BLOCKED ledger entry of [phaseId] in the reentry of [loopId] and [edgeIteration]. */
  public fun blockedReasons(
    phaseId: String,
    loopId: String?,
    edgeIteration: Int?,
  ): List<String>

  public fun trailingNonOutputAttempts(
    phaseId: String,
    isProcessFailure: (String) -> Boolean,
  ): List<FeatureTaskRuntimeNonOutputAttempt>

  public val currentReviewPassNumber: Int?

  public val resumeRules: (String) -> PhaseResumeRules
}

internal fun detachedProgressObservations(
  captured: FeatureTaskRuntimeRunState,
): FeatureTaskRuntimeProgressSnapshotAccess = DetachedProgressObservations(captured)

private class DetachedProgressObservations(
  private val captured: FeatureTaskRuntimeRunState,
) : FeatureTaskRuntimeProgressSnapshotAccess by captured

internal fun durablePhaseAttemptCount(
  progress: FeatureTaskRuntimeProgressSnapshotAccess,
  phaseId: String,
): Int {
  val observation = progress.phase(phaseId)
  return listOfNotNull(
    observation.record?.attemptCount,
    observation.output?.iteration,
    observation.acknowledgedAttemptCount,
  ).maxOrNull()
    ?: observation.nextIteration
}

public data class PhaseProgressObservation(
  val completed: Boolean,
  val hasPriorRecord: Boolean,
  val resumedFromPriorProcess: Boolean,
  val blockedReason: String?,
  val branchSetupBlocked: Boolean,
  val record: FeatureTaskRuntimePhaseRecord?,
  val output: FeatureTaskRuntimePhaseOutput?,
  val nextIteration: Int,
  val acknowledgedAttemptCount: Int? = null,
)

public data class LoopProgressObservation(
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
