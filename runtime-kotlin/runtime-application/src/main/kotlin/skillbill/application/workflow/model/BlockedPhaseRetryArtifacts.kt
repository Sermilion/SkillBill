package skillbill.application.workflow.model

import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord

/**
 * Lets a phase owner reopen its own durable state inside the same transaction as an operator blocked-phase retry.
 *
 * The application layer only reopens the phase record. Phase-owned artifacts, such as plan bundle feasibility
 * state, use codecs that live in the engine, so the engine supplies their reopened entries through this hook.
 */
fun interface BlockedPhaseRetryArtifacts {
  fun reopen(request: BlockedPhaseRetryArtifactsRequest): BlockedPhaseRetryArtifactsResult
}

data class BlockedPhaseRetryArtifactsRequest(
  val workflowId: String,
  val phaseId: String,
  val artifacts: DurableWorkflowArtifacts,
  val retryLedgerSequence: Int,
  val blockedRecord: FeatureTaskRuntimePhaseRecord,
)

sealed interface BlockedPhaseRetryArtifactsResult {
  data class Patch(val entries: WorkflowArtifactPatch) : BlockedPhaseRetryArtifactsResult

  data class Refused(val reason: String) : BlockedPhaseRetryArtifactsResult

  companion object {
    val Unchanged: BlockedPhaseRetryArtifactsResult = Patch(WorkflowArtifactPatch.EMPTY)
  }
}
