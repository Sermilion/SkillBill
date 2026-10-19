package skillbill.mcp.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.contracts.JsonCodec
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.workflow.verify.model.VerifyQaSelection

internal fun McpToolArguments.verifyQaSelection(): String? =
  optionalString(McpToolPayloadKeys.QA)?.let { verifyQaValue(it) }

internal fun verifyQaInitialArtifacts(qa: String?): Map<String, Any?> =
  mapOf(
    McpToolPayloadKeys.INPUT_CONTEXT to
      mapOf(McpToolPayloadKeys.QA_SELECTION to (qa ?: VerifyQaSelection.OFF.wireValue)),
  )

internal fun McpToolArguments.guardStoredVerifyQa(
  component: McpComponent,
  workflowId: String,
) {
  val asked = verifyQaSelection() ?: return
  val storedContext = storedInputContext(component, workflowId) ?: return
  refuseConflictingQa(workflowId, storedQaSelection(workflowId, storedContext), asked)
}

internal fun McpToolArguments.verifyQaArtifactsPatch(
  component: McpComponent,
  workflowId: String,
  artifactsPatch: Map<String, Any?>?,
): Map<String, Any?>? {
  val asked = verifyQaSelection()
  val rawPatchedContext = artifactsPatch?.get(McpToolPayloadKeys.INPUT_CONTEXT)
  val patchedContext = JsonCodec.anyToStringAnyMap(rawPatchedContext)
  val requested = requestedQaSelection(asked, rawPatchedContext, patchedContext)
  if (requested == null && patchedContext == null) return artifactsPatch
  val storedContext = storedInputContext(component, workflowId) ?: return artifactsPatch
  val stored = storedQaSelection(workflowId, storedContext)
  requested?.let { refuseConflictingQa(workflowId, stored, it) }
  val selection =
    requested ?: stored.takeIf { McpToolPayloadKeys.QA_SELECTION in storedContext } ?: return artifactsPatch
  val baseContext = patchedContext ?: storedContext
  return artifactsPatch.orEmpty() +
    (McpToolPayloadKeys.INPUT_CONTEXT to baseContext + (McpToolPayloadKeys.QA_SELECTION to selection))
}

private fun McpToolArguments.requestedQaSelection(
  asked: String?,
  rawPatchedContext: Any?,
  patchedContext: Map<String, Any?>?,
): String? {
  val patchedQa =
    patchedContext?.get(McpToolPayloadKeys.QA_SELECTION)?.let {
      verifyQaValue(it as? String ?: invalid(McpToolPayloadKeys.ARTIFACTS_PATCH, "qa_selection must be a string"))
    }
  if (asked != null && patchedQa != null && asked != patchedQa) {
    invalid(
      McpToolPayloadKeys.QA,
      "qa:$asked disagrees with artifacts_patch input_context.qa_selection '$patchedQa'; pass one value.",
    )
  }
  if (asked != null && rawPatchedContext != null && patchedContext == null) {
    invalid(McpToolPayloadKeys.ARTIFACTS_PATCH, "input_context must be an object when qa is set")
  }
  return asked ?: patchedQa
}

private fun McpToolArguments.verifyQaValue(raw: String): String =
  VerifyQaSelection.fromWire(raw.trim())?.wireValue
    ?: invalid(McpToolPayloadKeys.QA, "Unknown qa:$raw; expected ${VerifyQaSelection.EXPECTED}.")

private fun McpToolArguments.storedQaSelection(
  workflowId: String,
  storedContext: Map<String, Any?>,
): String {
  val raw = storedContext[McpToolPayloadKeys.QA_SELECTION] ?: return VerifyQaSelection.OFF.wireValue
  return VerifyQaSelection.fromWire(raw as? String)?.wireValue
    ?: invalid(
      McpToolPayloadKeys.QA,
      "Verify workflow '$workflowId' stores qa_selection '$raw', expected ${VerifyQaSelection.EXPECTED}; " +
        "rerun operation verify.",
    )
}

private fun McpToolArguments.refuseConflictingQa(
  workflowId: String,
  stored: String,
  asked: String,
) {
  if (asked != stored) {
    invalid(
      McpToolPayloadKeys.QA,
      "Verify workflow '$workflowId' was proposed with qa:$stored; it cannot change to qa:$asked. " +
        "Omit qa or pass qa:$stored, or start a new operation verify with qa:$asked.",
    )
  }
}

private fun storedInputContext(
  component: McpComponent,
  workflowId: String,
): Map<String, Any?>? =
  when (val result = component.workflowService.get(WorkflowFamilyKind.VERIFY, workflowId)) {
    is WorkflowGetResult.Ok ->
      JsonCodec.anyToStringAnyMap(result.snapshot.artifacts[McpToolPayloadKeys.INPUT_CONTEXT]).orEmpty()
    is WorkflowGetResult.Error -> null
  }
