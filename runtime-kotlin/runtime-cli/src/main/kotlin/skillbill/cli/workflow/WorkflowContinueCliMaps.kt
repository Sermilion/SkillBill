package skillbill.cli.workflow

import skillbill.application.workflow.persist.WorkflowWireProjections
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.workflow.engine.model.WorkflowContinueView
import skillbill.workflow.model.WorkflowContinueStatus

internal fun standardContinueMap(
  view: WorkflowContinueView,
  dbPath: String,
  decompositionExtras: Map<String, Any?>,
): Map<String, Any?> {
  val map = LinkedHashMap(WorkflowWireProjections.compactContinueMap(view.compact).toPayload())
  map[WorkflowWirePayloadKeys.SESSION_SUMMARY] = view.sessionSummary.toPayload()
  val quotedDbPath = "'${dbPath.replace("'", "'\"'\"'")}'"
  val quotedWorkflowId = "'${view.resume.snapshot.workflowId.replace("'", "'\"'\"'")}'"
  map["read_only_full_state_command"] =
    "skill-bill --db $quotedDbPath verify-workflow show $quotedWorkflowId --format json"
  decompositionExtras.forEach { (key, value) -> map[key] = value }
  map["db_path"] = dbPath
  if (view.continueStatus == WorkflowContinueStatus.BLOCKED) {
    val missingArtifacts = view.resume.missingArtifacts
    map[SharedPayloadKeys.STATUS] = "error"
    map["error"] =
      "Cannot continue workflow until the missing artifacts are restored: " +
      missingArtifacts.joinToString()
  } else {
    map[SharedPayloadKeys.STATUS] = CliPayloadStatus.OK
  }
  return map
}
