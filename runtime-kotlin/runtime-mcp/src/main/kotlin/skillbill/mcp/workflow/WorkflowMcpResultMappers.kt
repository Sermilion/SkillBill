package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys

internal fun WorkflowContinueResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowContinueResult.Standard -> standardMcpContinueMap(view, dbPath)
    is WorkflowContinueResult.UnknownWorkflow -> toUnknownWorkflowMcpMap()
    is WorkflowContinueResult.Error -> toErrorMcpMap()
    is WorkflowContinueResult.DecompositionStandard,
    is WorkflowContinueResult.DecompositionMissingSubtaskWorkflow,
    is WorkflowContinueResult.DecompositionBlockedSubtask,
    is WorkflowContinueResult.DecompositionBlockedBranchStart,
    is WorkflowContinueResult.DecompositionDone,
    is WorkflowContinueResult.DecompositionSubtaskOutcome,
    is WorkflowContinueResult.DecompositionBlockedGit,
    ->
      throw UnsupportedOperationException(
        "${this::class.simpleName} is not produced for verify workflows",
      )
  }

internal fun WorkflowOpenResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowOpenResult.Ok ->
      workflowSnapshotMcpMap(snapshot, goalObservability).apply {
        launchProjection?.let {
          put(
            WorkflowWirePayloadKeys.LAUNCH_PROJECTION,
            WorkflowWireProjections.inputProjectionMap(it).toPayload(),
          )
        }
        put(SharedPayloadKeys.STATUS, "ok")
        put(WorkflowWirePayloadKeys.DB_PATH, dbPath)
      }
    is WorkflowOpenResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        LifecycleTelemetryPayloadKeys.ERROR to error,
      )
  }

internal fun WorkflowUpdateResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowUpdateResult.Ok ->
      LinkedHashMap(
        WorkflowWireProjections.updateAcknowledgementMap(acknowledgement).toPayload(),
      ).apply {
        launchProjection?.let {
          put(
            WorkflowWirePayloadKeys.LAUNCH_PROJECTION,
            WorkflowWireProjections.inputProjectionMap(it).toPayload(),
          )
        }
        put(
          WorkflowWirePayloadKeys.READ_ONLY_FULL_STATE_COMMAND,
          readOnlyFullStateCommand(dbPath, acknowledgement.workflowId),
        )
        put(WorkflowWirePayloadKeys.DB_PATH, dbPath)
      }
    is WorkflowUpdateResult.Error ->
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        LifecycleTelemetryPayloadKeys.ERROR to error,
      ).apply { dbPath?.let { put(WorkflowWirePayloadKeys.DB_PATH, it) } }
  }

internal fun WorkflowGetResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowGetResult.Ok ->
      workflowSnapshotMcpMap(snapshot, goalObservability).apply {
        put(SharedPayloadKeys.STATUS, "ok")
        put(WorkflowWirePayloadKeys.DB_PATH, dbPath)
      }
    is WorkflowGetResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        LifecycleTelemetryPayloadKeys.ERROR to error,
        WorkflowWirePayloadKeys.DB_PATH to dbPath,
      )
  }

internal fun WorkflowListResult.toMcpMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to "ok",
    WorkflowWirePayloadKeys.DB_PATH to dbPath,
    WorkflowWirePayloadKeys.WORKFLOW_COUNT to workflowCount,
    WorkflowWirePayloadKeys.WORKFLOWS to workflows.map { WorkflowWireProjections.summaryMap(it).toPayload() },
  )

internal fun WorkflowLatestResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowLatestResult.Ok ->
      LinkedHashMap(WorkflowWireProjections.summaryMap(summary).toPayload()).apply {
        put(SharedPayloadKeys.STATUS, "ok")
        put(WorkflowWirePayloadKeys.DB_PATH, dbPath)
      }
    is WorkflowLatestResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        LifecycleTelemetryPayloadKeys.ERROR to error,
        WorkflowWirePayloadKeys.DB_PATH to dbPath,
      )
  }

internal fun WorkflowResumeResult.toMcpMap(): Map<String, Any?> =
  when (this) {
    is WorkflowResumeResult.Ok ->
      LinkedHashMap(WorkflowWireProjections.resumeMap(resume).toPayload()).apply {
        put(SharedPayloadKeys.STATUS, "ok")
        put(WorkflowWirePayloadKeys.DB_PATH, dbPath)
      }
    is WorkflowResumeResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        LifecycleTelemetryPayloadKeys.ERROR to error,
        WorkflowWirePayloadKeys.DB_PATH to dbPath,
      )
  }

internal fun readOnlyFullStateCommand(
  dbPath: String,
  workflowId: String,
): String {
  val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
  val quotedWorkflowId = "'${workflowId.replace("'", "'\"'\"'")}'"
  return "skill-bill --db $quotedDbPath verify-workflow show $quotedWorkflowId --format json"
}
