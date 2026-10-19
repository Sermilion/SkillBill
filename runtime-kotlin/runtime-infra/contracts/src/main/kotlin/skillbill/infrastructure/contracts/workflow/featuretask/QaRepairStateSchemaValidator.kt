package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.qa.QA_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.qa.QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.qa.QaRepairStatePayloadKeys
import skillbill.error.featuretask.qa.invalidQaRepairStateSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.QaRepairStateSchemaPaths

object QaRepairStateSchemaValidator {
  private const val MAX_REPORTED_VIOLATIONS = 20

  private fun invalid(reason: String): Nothing = throw invalidQaRepairStateSchema(reason)

  fun validate(payload: Map<String, Any?>) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val violations =
      ClasspathContractSchemaLoader.validate(
        schema(payload[QaRepairStatePayloadKeys.CONTRACT_VERSION] == QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION),
        instance,
      )
    if (violations.isNotEmpty()) {
      invalid(formatReason(violations))
    }
    if (
      payload[QaRepairStatePayloadKeys.CONSUMED_ATTEMPTS] !=
      (payload[QaRepairStatePayloadKeys.ATTEMPTS] as? List<*>)?.size
    ) {
      invalid("consumed_attempts must equal attempts length")
    }
    val attempts = payload[QaRepairStatePayloadKeys.ATTEMPTS] as? List<*> ?: emptyList<Any?>()
    attempts.forEachIndexed { index, value ->
      val attempt = value as? Map<*, *> ?: return@forEachIndexed
      if (attempt[QaRepairStatePayloadKeys.ATTEMPT_NUMBER] != index + 1) {
        invalid("attempt numbers must be consecutive starting at one")
      }
    }
    val openFindings = payload[QaRepairStatePayloadKeys.OPEN_FINDINGS] as? List<*> ?: emptyList<Any?>()
    val findingIds =
      openFindings.mapNotNull { value ->
        (value as? Map<*, *>)?.get(QaRepairStatePayloadKeys.ID) as? String
      }
    if (findingIds.size != findingIds.toSet().size) {
      invalid("open finding ids must be unique")
    }
  }

  private fun formatReason(violations: Set<ValidationMessage>): String =
    violations.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { "${it.instanceLocation?.toString() ?: "<root>"}: ${it.message.orEmpty()}" }

  private fun schema(previous: Boolean): JsonSchema {
    val resource =
      if (previous) {
        QaRepairStateSchemaPaths.PREVIOUS_CLASSPATH_RESOURCE
      } else {
        QaRepairStateSchemaPaths.CLASSPATH_RESOURCE
      }
    return ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = resource,
        classLoader = QaRepairStateSchemaValidator::class.java.classLoader,
        classpathResource = resource,
        missingResource = { invalidQaRepairStateSchema("Canonical schema resource is missing") },
        processingFailure = { cause -> invalidQaRepairStateSchema(cause.message.orEmpty(), cause) },
        loadFailureLogger = {},
        expectedSchemaId = QaRepairStateSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion =
          if (previous) QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION else QA_REPAIR_STATE_CONTRACT_VERSION,
        identityFailure = ::invalidQaRepairStateSchema,
      ),
    )
  }
}
