package skillbill.engine.featuretask.slot.state

import skillbill.contracts.JsonCodec
import skillbill.error.core.catchingNonCancellation
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * How the durable records one strategy step left behind resume in a new process. The shared run state asks the
 * strategy that owns a step for its rules while it rebuilds the run from its records and ledger, so the legacy
 * shapes and resume decisions a step owns live with the step instead of in the shared reconstruction. A step declares
 * only the decisions it owns, each as a capability next to this interface, and reconstruction reads the neutral
 * answer for every capability the step does not declare.
 */
internal interface PhaseResumeRules {
  /** The form resumed [record] of this step takes, given [stripped], the record without its retired loop. */
  fun resumedRecord(
    record: FeatureTaskRuntimePhaseRecord,
    stripped: FeatureTaskRuntimePhaseRecord,
  ): FeatureTaskRuntimePhaseRecord = stripped

  /** How a persisted block of this step with [reason] resumes, given its [recentBlockedReasons], newest first. */
  fun persistedBlockResume(
    reason: String,
    recentBlockedReasons: List<String?>,
  ): PhaseBlockResume = PhaseBlockResume.DEFAULT

  companion object {
    val None: PhaseResumeRules = object : PhaseResumeRules {}
  }
}

/** A step whose completed receipt must validate before reconstruction can change any completion. */
internal interface PhaseCompletedReceiptResume : PhaseResumeRules {
  val requiresValidCompletedOutput: Boolean
}

/** A step that invalidates its resumed completion from its lazily validated output. */
internal interface PhaseResumedCompletionInvalidation : PhaseResumeRules {
  /** Whether the resumed completion of [record] is invalidated, given its lazily validated [output]. */
  fun invalidatesResumedCompletion(
    record: FeatureTaskRuntimePhaseRecord,
    output: () -> FeatureTaskRuntimePhaseOutput?,
  ): Boolean
}

/** A step that does not buffer its incomplete output and drops its resumed completion with its sibling steps. */
internal interface PhasePlanResume : PhaseResumeRules {
  /** Whether validated output of this step re-enters the output buffer while the step is not completed. */
  val buffersIncompleteOutput: Boolean

  /** Whether the resumed completion of this step is dropped, given the [completedStepIds] the records carry. */
  fun dropsResumedCompletion(completedStepIds: Set<String>): Boolean
}

/** A step that invalidates later steps, and drops or withholds ledger entries and durable output, while incomplete. */
internal interface PhaseAuditLedgerResume : PhaseResumeRules {
  /** Whether later forward steps lose their completion while this step is not completed. */
  val invalidatesLaterStepsWhileIncomplete: Boolean

  /** Whether a blocked ledger entry of this step is dropped, given its [raw] and [resumed] record. */
  fun dropsBlockedLedgerEntry(
    raw: FeatureTaskRuntimePhaseRecord?,
    resumed: FeatureTaskRuntimePhaseRecord?,
  ): Boolean

  /** Whether the durable output of [record] is withheld from the resumed run. */
  fun withholdsDurableOutput(record: FeatureTaskRuntimePhaseRecord): Boolean
}

/** A step that records review passes, so the run state tracks its pass numbers and invalidation tombstone. */
internal interface PhaseReviewPassResume : PhaseResumeRules {
  val tracksReviewPasses: Boolean
}

/** A step whose completion returns from a loop, so reconstruction can tell a completed return from a re-entry. */
internal interface PhaseReturnLoopResume : PhaseResumeRules {
  val completedStepReturnLoopId: String?
}

internal val PhaseResumeRules.completedStepReturnLoopId: String?
  get() = (this as? PhaseReturnLoopResume)?.completedStepReturnLoopId

internal val PhaseResumeRules.requiresValidCompletedOutput: Boolean
  get() = (this as? PhaseCompletedReceiptResume)?.requiresValidCompletedOutput ?: false

internal val PhaseResumeRules.tracksReviewPasses: Boolean
  get() = (this as? PhaseReviewPassResume)?.tracksReviewPasses ?: false

internal val PhaseResumeRules.buffersIncompleteOutput: Boolean
  get() = (this as? PhasePlanResume)?.buffersIncompleteOutput ?: true

internal val PhaseResumeRules.invalidatesLaterStepsWhileIncomplete: Boolean
  get() = (this as? PhaseAuditLedgerResume)?.invalidatesLaterStepsWhileIncomplete ?: false

internal fun PhaseResumeRules.dropsBlockedLedgerEntry(
  raw: FeatureTaskRuntimePhaseRecord?,
  resumed: FeatureTaskRuntimePhaseRecord?,
): Boolean = (this as? PhaseAuditLedgerResume)?.dropsBlockedLedgerEntry(raw, resumed) ?: false

internal fun PhaseResumeRules.withholdsDurableOutput(record: FeatureTaskRuntimePhaseRecord): Boolean =
  (this as? PhaseAuditLedgerResume)?.withholdsDurableOutput(record) ?: false

internal fun PhaseResumeRules.dropsResumedCompletion(completedStepIds: Set<String>): Boolean =
  (this as? PhasePlanResume)?.dropsResumedCompletion(completedStepIds) ?: false

internal fun PhaseResumeRules.invalidatesResumedCompletion(
  record: FeatureTaskRuntimePhaseRecord,
  output: () -> FeatureTaskRuntimePhaseOutput?,
): Boolean = (this as? PhaseResumedCompletionInvalidation)?.invalidatesResumedCompletion(record, output) ?: false

internal enum class PhaseBlockResume {
  DEFAULT,
  RELAUNCH,
  RELAUNCH_WITH_FRESH_BUDGET,
}

internal fun isRetiredAuditGapLoop(loopId: String?): Boolean =
  loopId == FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID

private val PULL_REQUEST_INCIDENT_PHASES =
  setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
  )

internal fun carriesPullRequestIncidentFields(phaseId: String): Boolean = phaseId in PULL_REQUEST_INCIDENT_PHASES

internal fun recordEnvelope(record: FeatureTaskRuntimePhaseRecord): Map<String, Any?>? =
  record.outputArtifact?.let { artifact ->
    catchingNonCancellation {
      JsonCodec.parseObjectOrNull(artifact)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    }.getOrNull()
  }
