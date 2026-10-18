package skillbill.engine.featuretask.runner

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION

internal const val AUDIT_GAP_MESSAGE = "AC-002 acceptance criterion is not yet implemented"

internal const val AUDIT_SATISFIED_VALUE = "All acceptance criteria are met; no production criteria remain."

internal fun noChangeImplementationOutput(): String =
  JsonCodec.mapToJsonString(
    mapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID to "implement",
      SharedPayloadKeys.STATUS to "completed",
      SharedPayloadKeys.PRODUCED_OUTPUTS to
        mapOf(
          SharedPayloadKeys.VALUE to "The source already satisfies the requirements.",
          "no_change" to
            mapOf(
              "reason" to "already_satisfied",
              "criteria" to
                listOf("AC-001", "AC-002").map { id ->
                  mapOf(
                    "criterion_id" to id,
                    "verdict" to "already_satisfied",
                    "evidence" to "The production caller meets $id.",
                  )
                },
              "citations" to listOf("src/Admission.kt:12"),
              "boundary_trace" to "The request enters the production admission owner.",
            ),
        ),
    ),
  )

internal fun auditSatisfiedOutput(): String = settledAuditSatisfiedRecord()

internal fun settledAuditSatisfiedRecord(): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "audit",
    "status": "completed",
    "summary": "Every acceptance criterion is met.",
    "verdict": "satisfied",
    "produced_outputs": {
      "value": "$AUDIT_SATISFIED_VALUE"
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

internal fun auditRepairPlanOutput(prompt: String): String {
  val audit = prompt.substringAfter("### from: audit\n", prompt).substringBefore("### from:")
  val openLines = audit.lines().filterNot { it.contains("no remaining production gap", ignoreCase = true) }
  val criteria = Regex("(?:S\\d+-)?AC-?\\d+").findAll(openLines.joinToString("\n")).map { it.value }.distinct().toList()
  return auditRepairPlanFor(criteria)
}

internal fun auditRepairPlanFor(criteria: List<String>): String =
  JsonCodec.mapToJsonString(
    mapOf(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      SharedPayloadKeys.PHASE_ID to "audit_plan_fix",
      SharedPayloadKeys.STATUS to "completed",
      SharedPayloadKeys.PRODUCED_OUTPUTS to
        mapOf(
          SharedPayloadKeys.VALUE to
            criteria.joinToString("\n\n") { criterion ->
              """
              ### $criterion
              Gap: Missing production admission before recovery.
              Production path: src/Foo.kt recovery caller and transaction owner.
              Changes: Check admission inside the mutation transaction before updating recovery state.
              Closure evidence: The recovery caller cannot mutate state before admission succeeds.
              """.trimIndent()
            },
        ),
    ),
  )
