package skillbill.goalrunner

import skillbill.contracts.SharedPayloadKeys
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

fun GoalRunnerSupervisionEvent.toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactsMap())

internal fun GoalRunnerSupervisionEvent.toArtifactsMap(): Map<String, Any?> =
  linkedMapOf(
    "phase" to phase,
    "reason" to reason,
    "continuation_mode" to continuationMode.wireValue,
    "process_state" to processState.wireValue,
    SharedPayloadKeys.WORKFLOW_ID to workflowId,
    SharedPayloadKeys.STEP_ID to stepId,
    "last_durable_progress" to lastDurableProgress,
    "last_workflow_snapshot_at" to lastWorkflowSnapshotAt,
    "last_file_activity_at" to lastFileActivityAt,
    "last_output_at" to lastOutputAt,
  )

const val WORKER_SUBTASK_REQUEST_OUTCOME_LIMIT = 50

fun GoalRunnerWorkerSubtaskRequestOutcome.toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

internal fun GoalRunnerWorkerSubtaskRequestOutcome.toArtifactMap(): Map<String, Any?> =
  when (this) {
    is GoalRunnerWorkerSubtaskRequestOutcome.Accepted ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "accepted",
        "source_stream" to sourceStream,
        "request" to request.toArtifactMap(),
        SharedPayloadKeys.SUBTASK_ID to subtask.id,
        "spec_path" to subtask.specPath,
      )
    is GoalRunnerWorkerSubtaskRequestOutcome.Queued ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "queued",
        "source_stream" to sourceStream,
        "request" to request.toArtifactMap(),
        "reason" to reason,
      )
    is GoalRunnerWorkerSubtaskRequestOutcome.Rejected ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "rejected",
        "source_stream" to sourceStream,
        "reason" to reason.name.lowercase(),
        "message" to message,
      )
    is GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "requires_operator_confirmation",
        "source_stream" to sourceStream,
        "request" to request.toArtifactMap(),
        "reason" to reason,
      )
  }

internal fun GoalRunnerWorkerSubtaskRequest.toArtifactMap(): Map<String, Any?> =
  linkedMapOf<String, Any?>(
    "name" to name,
    "spec_path" to specPath,
    "rationale" to rationale,
    "depends_on_subtask_ids" to dependsOnSubtaskIds,
    "requires_operator_confirmation" to requiresOperatorConfirmation,
  ).filterValues { value -> value != null }
