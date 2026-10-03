package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.packagedContractResourceFailure
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import java.util.logging.Logger
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration as FeatureTaskRuntimePhaseOutputMigrationPort

internal sealed interface FeatureTaskRuntimePhaseOutputMigration {
  data class Current(
    val payload: String,
    val sourceVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigration

  data class Migrated(
    val payload: String,
    val sourceVersion: String,
    val targetVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigration

  data class Unsupported(val sourceVersion: String) : FeatureTaskRuntimePhaseOutputMigration

  data class Corrupt(val sourceVersion: String?) : FeatureTaskRuntimePhaseOutputMigration

  data class NonConvertible(
    val sourceVersion: String,
    val targetVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigration
}

internal object FeatureTaskRuntimePhaseOutputMigrator {
  private val logger = Logger.getLogger(FeatureTaskRuntimePhaseOutputMigrator::class.java.name)
  private val validationMapper =
    ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)

  fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigration {
    val source = parseObject(payload) ?: return FeatureTaskRuntimePhaseOutputMigration.Corrupt(null)
    val sourceVersion =
      (source[SharedPayloadKeys.CONTRACT_VERSION] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: return FeatureTaskRuntimePhaseOutputMigration.Corrupt(null)
    return when {
      sourceVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION -> current(payload, sourceVersion)
      sourceVersion in FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS ->
        convert(
          source,
          payload,
          sourceVersion,
          FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS.getValue(sourceVersion),
        )
      else -> FeatureTaskRuntimePhaseOutputMigration.Unsupported(sourceVersion)
    }
  }

  private fun current(
    payload: String,
    sourceVersion: String,
  ): FeatureTaskRuntimePhaseOutputMigration =
    if (validate(payload, schema(FeatureTaskRuntimePhaseOutputSchemaPaths.CURRENT_CLASSPATH_RESOURCE, sourceVersion))) {
      FeatureTaskRuntimePhaseOutputMigration.Current(payload, sourceVersion)
    } else {
      FeatureTaskRuntimePhaseOutputMigration.Corrupt(sourceVersion)
    }

  private fun convert(
    source: JsonObject,
    sourcePayload: String,
    sourceVersion: String,
    targetVersion: String,
  ): FeatureTaskRuntimePhaseOutputMigration {
    if (
      !validate(
        sourcePayload,
        schema(
          resource = FeatureTaskRuntimePhaseOutputSchemaPaths.HISTORICAL_0_6_CLASSPATH_RESOURCE,
          version = FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION,
        ),
      )
    ) {
      return FeatureTaskRuntimePhaseOutputMigration.Corrupt(sourceVersion)
    }
    val converted = JsonObject(source + (SharedPayloadKeys.CONTRACT_VERSION to JsonPrimitive(targetVersion)))
    val convertedPayload = converted.toString()
    val targetInstance =
      validationView(convertedPayload)
        ?: return FeatureTaskRuntimePhaseOutputMigration.NonConvertible(sourceVersion, targetVersion)
    if (
      !validate(
        targetInstance,
        schema(
          resource = FeatureTaskRuntimePhaseOutputSchemaPaths.CURRENT_CLASSPATH_RESOURCE,
          version = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      )
    ) {
      return FeatureTaskRuntimePhaseOutputMigration.NonConvertible(sourceVersion, targetVersion)
    }
    return FeatureTaskRuntimePhaseOutputMigration.Migrated(convertedPayload, sourceVersion, targetVersion)
  }

  private fun parseObject(payload: String): JsonObject? =
    try {
      Json.parseToJsonElement(payload) as? JsonObject
    } catch (_: SerializationException) {
      null
    }

  private fun validationView(payload: String): JsonNode? =
    try {
      validationMapper.readTree(payload)
    } catch (_: JsonProcessingException) {
      null
    }

  private fun validate(
    payload: String,
    schema: JsonSchema,
  ): Boolean = validationView(payload)?.let { validate(it, schema) } == true

  private fun validate(
    instance: JsonNode,
    schema: JsonSchema,
  ): Boolean = ClasspathContractSchemaLoader.validate(schema, instance).isEmpty()

  private fun schema(
    resource: String,
    version: String,
  ): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = resource,
        classLoader = FeatureTaskRuntimePhaseOutputMigrator::class.java.classLoader,
        classpathResource = resource,
        missingResource = { packagedContractResourceFailure(resource) },
        processingFailure = { cause -> packagedContractResourceFailure(resource, cause) },
        loadFailureLogger = { error ->
          logSchemaLoadFailure(logger, "phase output", resource, resource, error)
        },
        expectedSchemaId = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = version,
        identityFailure = { _ -> packagedContractResourceFailure(resource) },
      ),
    )
}

@Inject
class ContractFeatureTaskRuntimePhaseOutputMigration : FeatureTaskRuntimePhaseOutputMigrationPort {
  override fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigrationResult =
    when (val result = FeatureTaskRuntimePhaseOutputMigrator.migrate(payload)) {
      is FeatureTaskRuntimePhaseOutputMigration.Current -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Current(result.payload)
      }
      is FeatureTaskRuntimePhaseOutputMigration.Migrated -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Migrated(
          result.payload,
          result.sourceVersion,
          result.targetVersion,
        )
      }
      is FeatureTaskRuntimePhaseOutputMigration.Unsupported -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Refused(
          result.sourceVersion,
          FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.UNSUPPORTED,
        )
      }
      is FeatureTaskRuntimePhaseOutputMigration.Corrupt -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Refused(
          result.sourceVersion,
          FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT,
        )
      }
      is FeatureTaskRuntimePhaseOutputMigration.NonConvertible -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Refused(
          result.sourceVersion,
          result.targetVersion,
          FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.NON_CONVERTIBLE,
        )
      }
    }
}
