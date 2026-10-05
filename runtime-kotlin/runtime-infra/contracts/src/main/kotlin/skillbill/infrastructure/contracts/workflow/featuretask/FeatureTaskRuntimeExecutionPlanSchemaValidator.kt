package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.error.core.UnsupportedJsonValueError
import skillbill.error.featuretask.InvalidFeatureTaskRuntimeExecutionPlanSchemaError
import skillbill.error.featuretask.UnsupportedFeatureTaskRuntimeExecutionPlanError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimeExecutionPlanSchemaPaths
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator as FeatureTaskRuntimeExecutionPlanValidatorPort

@Inject
class FeatureTaskRuntimeExecutionPlanSchemaValidator : FeatureTaskRuntimeExecutionPlanValidatorPort {
  override fun canonicalize(
    encoded: ByteArray,
    sourceLabel: String,
  ): ByteArray = JsonCodec.mapToJsonString(read(encoded, sourceLabel)).toByteArray(Charsets.UTF_8)

  fun read(
    encoded: ByteArray,
    sourceLabel: String,
  ): Map<String, Any?> {
    if (sourceLabel.isBlank()) invalidPlan("execution plan source label must be non-blank")
    requireBoundedBytes(encoded)
    val raw =
      try {
        Charsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded)).toString()
      } catch (_: CharacterCodingException) {
        invalidPlan("invalid UTF-8 encoding")
      }
    val instance =
      try {
        STRICT_JSON.readTree(raw)
      } catch (_: JsonProcessingException) {
        invalidPlan("malformed or ambiguous JSON")
      }
    if (instance == null || !instance.isObject) {
      invalidPlan("execution plan must be a JSON object")
    }
    val version = instance.path(FeatureTaskRuntimeExecutionPlanKeys.CONTRACT_VERSION)
    val versionText = version.takeIf { it.isTextual }?.asText()
    val historical =
      versionText == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION
    if (unsupportedCurrentVersion(versionText, historical)) {
      throw UnsupportedFeatureTaskRuntimeExecutionPlanError()
    }
    validateInstance(instance, historical)
    return JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(canonicalExecutionPlan(instance).toString()))
      ?: invalidPlan("execution plan must be a JSON object")
  }

  fun write(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ): ByteArray {
    val encoded =
      try {
        JsonCodec.mapToJsonString(payload).toByteArray(Charsets.UTF_8)
      } catch (_: UnsupportedJsonValueError) {
        throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("unsupported JSON value")
      }
    return JsonCodec.mapToJsonString(read(encoded, sourceLabel)).toByteArray(Charsets.UTF_8)
  }

  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    write(payload, sourceLabel)
  }

  private fun validateInstance(
    instance: JsonNode,
    historical: Boolean,
  ) {
    val errors: Set<ValidationMessage> =
      ClasspathContractSchemaLoader.validate(if (historical) historicalSchema() else schema(), instance)
    if (errors.isNotEmpty()) {
      throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
        "execution plan violates its schema",
      )
    }
    validateExecutionPlanCoherence(instance, historical)
  }
}

private fun schema(): JsonSchema =
  compiledExecutionPlanSchema(
    FeatureTaskRuntimeExecutionPlanSchemaPaths.CLASSPATH_RESOURCE,
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION,
  )

private fun historicalSchema(): JsonSchema =
  compiledExecutionPlanSchema(
    FeatureTaskRuntimeExecutionPlanSchemaPaths.HISTORICAL_0_1,
    FEATURE_TASK_RUNTIME_EXECUTION_PLAN_PREVIOUS_CONTRACT_VERSION,
  )

private fun compiledExecutionPlanSchema(
  resource: String,
  expectedVersion: String,
): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = resource,
      classLoader = FeatureTaskRuntimeExecutionPlanSchemaValidator::class.java.classLoader,
      classpathResource = resource,
      missingResource = {
        InvalidFeatureTaskRuntimeExecutionPlanSchemaError(
          "Canonical schema is missing: $resource",
        )
      },
      processingFailure = { cause ->
        InvalidFeatureTaskRuntimeExecutionPlanSchemaError(cause.message ?: cause::class.simpleName.orEmpty())
      },
      loadFailureLogger = {},
      expectedSchemaId = FeatureTaskRuntimeExecutionPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = expectedVersion,
      identityFailure = ::InvalidFeatureTaskRuntimeExecutionPlanSchemaError,
    ),
  )

private const val MAXIMUM_ENCODED_BYTES: Int = 65536

private val STRICT_JSON =
  ObjectMapper()
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

private fun requireBoundedBytes(encoded: ByteArray) {
  if (encoded.size > MAXIMUM_ENCODED_BYTES) {
    throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError("execution plan exceeds $MAXIMUM_ENCODED_BYTES UTF-8 bytes")
  }
}

private fun invalidPlan(reason: String): Nothing = throw InvalidFeatureTaskRuntimeExecutionPlanSchemaError(reason)

private fun unsupportedCurrentVersion(
  versionText: String?,
  historical: Boolean,
): Boolean {
  if (versionText == null || historical) return false
  if (versionText == FEATURE_TASK_RUNTIME_EXECUTION_PLAN_CONTRACT_VERSION) return false
  return EXECUTION_PLAN_VERSION.matches(versionText)
}

private val EXECUTION_PLAN_VERSION: Regex = Regex("[0-9]{1,8}\\.[0-9]{1,8}")
