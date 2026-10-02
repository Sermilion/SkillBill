package skillbill.engine.featuretask.slot.state

import skillbill.contracts.JsonCodec
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * How the durable records one strategy step left behind resume in a new process. The shared run state asks the
 * strategy that owns a step for its rules while it rebuilds the run from its records and ledger, so the legacy
 * shapes and resume decisions a step owns live with the step instead of in the shared reconstruction.
 */
internal interface PhaseResumeRules {
  /** Whether a completed receipt must validate before reconstruction can change any completion. */
  val requiresValidCompletedOutput: Boolean
    get() = false

  /** Whether this step records review passes, so the run state tracks its pass numbers and invalidation tombstone. */
  val tracksReviewPasses: Boolean
    get() = false

  /** Whether validated output of this step re-enters the output buffer while the step is not completed. */
  val buffersIncompleteOutput: Boolean
    get() = true

  /** Whether an explicit resume at this completed step starts at the furthest later step instead. */
  val resumesPastCompletion: Boolean
    get() = false

  /** Whether later forward steps lose their completion while this step is not completed. */
  val invalidatesLaterStepsWhileIncomplete: Boolean
    get() = false

  /** The form resumed [record] of this step takes, given [stripped], the record without its retired loop. */
  fun resumedRecord(
    record: FeatureTaskRuntimePhaseRecord,
    stripped: FeatureTaskRuntimePhaseRecord,
  ): FeatureTaskRuntimePhaseRecord = stripped

  /** Whether a blocked ledger entry of this step is dropped, given its [raw] and [resumed] record. */
  fun dropsBlockedLedgerEntry(
    raw: FeatureTaskRuntimePhaseRecord?,
    resumed: FeatureTaskRuntimePhaseRecord?,
  ): Boolean = false

  /** Whether the durable output of [record] is withheld from the resumed run. */
  fun withholdsDurableOutput(record: FeatureTaskRuntimePhaseRecord): Boolean = false

  /** Whether the resumed completion of this step is dropped, given the [completedStepIds] the records carry. */
  fun dropsResumedCompletion(completedStepIds: Set<String>): Boolean = false

  /** Whether the resumed completion of [record] is invalidated, given its lazily validated [output]. */
  fun invalidatesResumedCompletion(
    record: FeatureTaskRuntimePhaseRecord,
    output: () -> FeatureTaskRuntimePhaseOutput?,
  ): Boolean = false

  /** How a persisted block of this step with [reason] resumes, given its [recentBlockedReasons], newest first. */
  fun persistedBlockResume(
    reason: String,
    recentBlockedReasons: List<String?>,
  ): PhaseBlockResume = PhaseBlockResume.DEFAULT

  companion object {
    val None: PhaseResumeRules = object : PhaseResumeRules {}
  }
}

internal enum class PhaseBlockResume {
  DEFAULT,
  RELAUNCH,
  RELAUNCH_WITH_FRESH_BUDGET,
}

internal fun isRetiredAuditGapLoop(loopId: String?): Boolean =
  loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID

internal fun recordEnvelope(record: FeatureTaskRuntimePhaseRecord): Map<String, Any?>? =
  record.outputArtifact?.let { artifact ->
    runCatching {
      JsonCodec.parseObjectOrNull(artifact)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    }.getOrNull()
  }
