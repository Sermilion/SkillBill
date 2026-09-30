package skillbill.engine

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION

internal const val AUDIT_GAP_MESSAGE = "AC-002 acceptance criterion is not yet implemented"

internal fun auditSatisfiedOutput(): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Every acceptance criterion is met.",
    "produced_outputs": {
      "value": "[]"
    }
  }
  """.trimIndent()

internal fun auditRemainingAcOutput(remainingText: String): String {
  val escaped = remainingText.replace("\\", "\\\\").replace("\"", "\\\"")
  return """
    {
      "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
      "phase_id": "audit",
      "status": "completed",
      "summary": "Audit found remaining acceptance criteria.",
      "produced_outputs": {
        "value": "$escaped"
      }
    }
    """.trimIndent()
}

internal fun auditGapsFoundOutput(): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Audit found unmet acceptance criteria.",
    "verdict": "gaps_found",
    "produced_outputs": {
      "value": "{\"gaps\":[{\"criterion\":\"AC-002\",\"note\":\"$AUDIT_GAP_MESSAGE\"}],\"non_blocking_findings\":[]}"
    }
  }
  """.trimIndent()

internal fun auditBlockedOutput(reason: String): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "audit",
    "status": "blocked",
    "failure_disposition": "needs_user_action",
    "summary": "$reason",
    "produced_outputs": {
      "value": "$reason"
    }
  }
  """.trimIndent()

internal fun satisfiedAuditLauncher(): RuntimeRecordingLauncher =
  RuntimeRecordingLauncher { request ->
    val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
    if (phaseId == "audit") {
      facts(auditSatisfiedOutput())
    } else {
      facts(defaultPhaseOutput(request))
    }
  }
