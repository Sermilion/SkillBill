package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_QUARANTINE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeQuarantineSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeQuarantineSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS

object FeatureTaskRuntimeQuarantineSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      throw invalidFeatureTaskRuntimeQuarantineSchema(
        sourceLabel = sourceLabel,
        reason = formatReason(errors),
      )
    }
  }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(separator = " | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  private const val MAX_REPORTED_VIOLATIONS: Int = 3
}

private fun schema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = FeatureTaskRuntimeQuarantineSchemaValidator::class.java.classLoader,
      classpathResource = FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidFeatureTaskRuntimeQuarantineSchema(
          sourceLabel = FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE,
          reason =
            "Canonical feature-task-runtime quarantine schema is missing. Expected classpath resource " +
              "'${FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE}'.",
        )
      },
      processingFailure = { cause ->
        invalidFeatureTaskRuntimeQuarantineSchema(
          sourceLabel = FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeQuarantineSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_QUARANTINE_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidFeatureTaskRuntimeQuarantineSchema(
          sourceLabel = FeatureTaskRuntimeQuarantineSchemaPaths.CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
    ),
  )
