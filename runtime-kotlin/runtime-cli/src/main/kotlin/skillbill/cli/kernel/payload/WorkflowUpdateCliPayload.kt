package skillbill.cli.kernel.payload

import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.SharedPayloadKeys
import skillbill.ports.workflow.WorkflowArtifactWireEncoder

internal fun WorkflowUpdateResult.toPayload(encoder: WorkflowArtifactWireEncoder): Map<String, Any?> =
  when (this) {
    is WorkflowUpdateResult.Ok ->
      LinkedHashMap(
        WorkflowWireProjections.updateAcknowledgementMap(acknowledgement).toPayload(),
      ).apply {
        launchProjection?.let {
          put("launch_projection", WorkflowWireProjections.inputProjectionMap(it, encoder).toPayload())
        }
        val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
        val quotedWorkflowId = "'${acknowledgement.workflowId.replace("'", "'\"'\"'")}'"
        put(
          "read_only_full_state_command",
          "skill-bill --db $quotedDbPath verify-workflow show $quotedWorkflowId --format json",
        )
        put("db_path", dbPath)
      }
    is WorkflowUpdateResult.Error ->
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.STATUS to "error",
        SharedPayloadKeys.WORKFLOW_ID to workflowId,
        "error" to error,
      ).apply { dbPath?.let { put("db_path", it) } }
  }
