package skillbill.mcp.workflow

import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.model.WorkflowContinueStatus

internal fun standardMcpContinueMap(
  view: WorkflowContinueView,
  dbPath: String,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowWireProjections.compactContinueMap(view.compact).toPayload())
  map[WorkflowWirePayloadKeys.SESSION_SUMMARY] = view.sessionSummary.toPayload()
  map[WorkflowWirePayloadKeys.READ_ONLY_FULL_STATE_COMMAND] =
    readOnlyFullStateCommand(dbPath, view.resume.snapshot.workflowId)
  map[WorkflowWirePayloadKeys.DB_PATH] = dbPath
  if (view.continueStatus == WorkflowContinueStatus.BLOCKED) {
    val missingArtifacts = view.resume.missingArtifacts
    map[SharedPayloadKeys.STATUS] = "error"
    map[LifecycleTelemetryPayloadKeys.ERROR] =
      "Cannot continue workflow until the missing artifacts are restored: " +
      missingArtifacts.joinToString()
  } else {
    map[SharedPayloadKeys.STATUS] = "ok"
  }
  return map
}
