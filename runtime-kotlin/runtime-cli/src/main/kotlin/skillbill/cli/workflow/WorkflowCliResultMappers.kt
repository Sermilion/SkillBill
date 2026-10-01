package skillbill.cli.workflow

import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowLatestResult
import skillbill.application.workflow.model.WorkflowListResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowResumeResult
import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys

internal fun WorkflowOpenResult.toCliMap(): Map<String, Any?> =
  when (this) {
    is WorkflowOpenResult.Ok ->
      workflowSnapshotCliMap(snapshot, goalObservability).apply {
        launchProjection?.let { put("launch_projection", WorkflowWireProjections.inputProjectionMap(it).toPayload()) }
        put(SharedPayloadKeys.STATUS, CliPayloadStatus.OK)
        put("db_path", dbPath)
      }
    is WorkflowOpenResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "error" to error,
      )
  }

internal fun WorkflowGetResult.toCliMap(): Map<String, Any?> =
  when (this) {
    is WorkflowGetResult.Ok ->
      workflowSnapshotCliMap(snapshot, goalObservability).apply {
        put(SharedPayloadKeys.STATUS, CliPayloadStatus.OK)
        put("db_path", dbPath)
      }
    is WorkflowGetResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "error" to error,
        "db_path" to dbPath,
      )
  }

internal fun WorkflowListResult.toCliMap(): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
    "db_path" to dbPath,
    "workflow_count" to workflowCount,
    "workflows" to workflows.map { WorkflowWireProjections.summaryMap(it).toPayload() },
  )

internal fun WorkflowLatestResult.toCliMap(): Map<String, Any?> =
  when (this) {
    is WorkflowLatestResult.Ok ->
      LinkedHashMap(WorkflowWireProjections.summaryMap(summary).toPayload()).apply {
        put(SharedPayloadKeys.STATUS, CliPayloadStatus.OK)
        put("db_path", dbPath)
      }
    is WorkflowLatestResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        "error" to error,
        "db_path" to dbPath,
      )
  }

internal fun WorkflowResumeResult.toCliMap(): Map<String, Any?> =
  when (this) {
    is WorkflowResumeResult.Ok ->
      LinkedHashMap(WorkflowWireProjections.resumeMap(resume).toPayload()).apply {
        put(SharedPayloadKeys.STATUS, CliPayloadStatus.OK)
        put("db_path", dbPath)
      }
    is WorkflowResumeResult.Error ->
      linkedMapOf(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "error" to error,
        "db_path" to dbPath,
      )
  }
