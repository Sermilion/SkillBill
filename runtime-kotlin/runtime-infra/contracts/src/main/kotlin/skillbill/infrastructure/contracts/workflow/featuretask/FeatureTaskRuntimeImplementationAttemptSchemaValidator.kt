package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationAttemptSchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeImplementationAttemptSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS

object FeatureTaskRuntimeImplementationAttemptSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      throw invalidFeatureTaskRuntimeImplementationAttemptSchema(
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
      cacheKey = FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = FeatureTaskRuntimeImplementationAttemptSchemaValidator::class.java.classLoader,
      classpathResource = FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidFeatureTaskRuntimeImplementationAttemptSchema(
          sourceLabel = FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE,
          reason =
            "Canonical feature-task-runtime implementation-attempt schema is missing. Expected classpath " +
              "resource '${FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE}'.",
        )
      },
      processingFailure = { cause ->
        invalidFeatureTaskRuntimeImplementationAttemptSchema(
          sourceLabel = FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeImplementationAttemptSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPT_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidFeatureTaskRuntimeImplementationAttemptSchema(
          sourceLabel = FeatureTaskRuntimeImplementationAttemptSchemaPaths.CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
    ),
  )
