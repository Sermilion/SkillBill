package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalChildHistoryEvidenceSchemaPaths

object GoalChildHistoryEvidenceSchemaValidator {
  private const val MAX_REPORTED_ERRORS = 3

  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      val detail =
        errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
          .take(MAX_REPORTED_ERRORS)
          .joinToString(" | ") { "${it.instanceLocation?.toString() ?: "<root>"}: ${it.message.orEmpty()}" }
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE,
        "Goal-child history evidence '$sourceLabel' fails schema " +
          "${GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION}: $detail",
      )
    }
  }

  private fun schema(): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = GoalChildHistoryEvidenceSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = GoalChildHistoryEvidenceSchemaValidator::class.java.classLoader,
        classpathResource = GoalChildHistoryEvidenceSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = { invalid("canonical schema is missing") },
        processingFailure = { failure ->
          invalid(
            failure.message ?: failure::class.simpleName.orEmpty(),
            failure,
          )
        },
        loadFailureLogger = {},
        expectedSchemaId = GoalChildHistoryEvidenceSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = GOAL_CHILD_HISTORY_EVIDENCE_CONTRACT_VERSION,
        identityFailure = { reason -> invalid(reason) },
      ),
    )

  private fun invalid(
    reason: String,
    cause: Throwable? = null,
  ): SkillBillRuntimeException =
    SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_CHILD_HISTORY_EVIDENCE, reason, cause)
}
