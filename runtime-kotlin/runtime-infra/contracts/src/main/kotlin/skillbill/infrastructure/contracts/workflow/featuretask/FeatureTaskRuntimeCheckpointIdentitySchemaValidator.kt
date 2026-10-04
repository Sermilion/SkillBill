package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeCheckpointIdentitySchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeCheckpointIdentitySchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.infrastructure.contracts.workflow.issue.inlineIssueKeySchemaRefs

object FeatureTaskRuntimeCheckpointIdentitySchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(payload)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema(), instance)
    if (errors.isNotEmpty()) {
      throw invalidFeatureTaskRuntimeCheckpointIdentitySchema(
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
      cacheKey = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
      classLoader = FeatureTaskRuntimeCheckpointIdentitySchemaValidator::class.java.classLoader,
      classpathResource = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        invalidFeatureTaskRuntimeCheckpointIdentitySchema(
          sourceLabel = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
          reason =
            "Canonical feature-task-runtime checkpoint-identity schema is missing. Expected classpath " +
              "resource '${FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE}'.",
        )
      },
      processingFailure = { cause ->
        invalidFeatureTaskRuntimeCheckpointIdentitySchema(
          sourceLabel = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITY_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidFeatureTaskRuntimeCheckpointIdentitySchema(
          sourceLabel = FeatureTaskRuntimeCheckpointIdentitySchemaPaths.CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
      prepareSchemaDocument = { yamlNode ->
        yamlNode.inlineIssueKeySchemaRefs()
      },
    ),
  )
