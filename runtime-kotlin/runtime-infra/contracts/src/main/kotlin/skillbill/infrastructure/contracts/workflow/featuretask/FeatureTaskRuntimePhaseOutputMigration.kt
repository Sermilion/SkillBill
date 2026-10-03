package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import kotlinx.serialization.json.JsonPrimitive
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseOutputSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import java.util.logging.Logger
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration as FeatureTaskRuntimePhaseOutputMigrationPort

sealed interface FeatureTaskRuntimePhaseOutputMigration {
  data class Current(val payload: Map<String, Any?>) : FeatureTaskRuntimePhaseOutputMigration

  data class Migrated(
    val payload: Map<String, Any?>,
    val sourceVersion: String,
    val targetVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigration

  data class Unsupported(val sourceVersion: String?) : FeatureTaskRuntimePhaseOutputMigration

  data class Corrupt(val sourceVersion: String?) : FeatureTaskRuntimePhaseOutputMigration

  data class NonConvertible(
    val sourceVersion: String,
    val targetVersion: String,
  ) : FeatureTaskRuntimePhaseOutputMigration
}

object FeatureTaskRuntimePhaseOutputMigrator {
  private val logger = Logger.getLogger(FeatureTaskRuntimePhaseOutputMigrator::class.java.name)

  fun migrate(payload: Map<String, Any?>): FeatureTaskRuntimePhaseOutputMigration {
    val instance = ClasspathContractSchemaLoader.valueToTree(payload)
    val sourceVersion = instance.path(SharedPayloadKeys.CONTRACT_VERSION).takeIf(JsonNode::isTextual)?.asText()
    if (sourceVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION) {
      return FeatureTaskRuntimePhaseOutputMigration.Current(payload)
    }
    if (sourceVersion == null) return FeatureTaskRuntimePhaseOutputMigration.Unsupported(null)
    val targetVersion =
      FEATURE_TASK_RUNTIME_SUPPORTED_PHASE_OUTPUT_MIGRATIONS[sourceVersion]
        ?: return FeatureTaskRuntimePhaseOutputMigration.Unsupported(sourceVersion)
    return convert(instance, sourceVersion, targetVersion)
  }

  private fun convert(
    instance: JsonNode,
    sourceVersion: String,
    targetVersion: String,
  ): FeatureTaskRuntimePhaseOutputMigration {
    if (
      !validate(
        instance,
        schema(
          resource = FeatureTaskRuntimePhaseOutputSchemaPaths.HISTORICAL_0_6_CLASSPATH_RESOURCE,
          version = FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION,
        ),
      )
    ) {
      return FeatureTaskRuntimePhaseOutputMigration.Corrupt(sourceVersion)
    }
    val converted =
      instance.deepCopy<ObjectNode>().apply {
        put(SharedPayloadKeys.CONTRACT_VERSION, targetVersion)
      }
    if (
      !validate(
        converted,
        schema(
          resource = FeatureTaskRuntimePhaseOutputSchemaPaths.CURRENT_CLASSPATH_RESOURCE,
          version = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        ),
      )
    ) {
      return FeatureTaskRuntimePhaseOutputMigration.NonConvertible(sourceVersion, targetVersion)
    }
    return FeatureTaskRuntimePhaseOutputMigration.Migrated(
      ClasspathContractSchemaLoader.sharedObjectMapper().convertValue(
        converted,
        object : TypeReference<Map<String, Any?>>() {},
      ),
      sourceVersion,
      targetVersion,
    )
  }

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
        missingResource = {
          InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            resource,
            "required phase-output contract resource is missing",
          )
        },
        processingFailure = { cause ->
          InvalidFeatureTaskRuntimePhaseOutputSchemaError(
            resource,
            cause.message ?: cause::class.simpleName.orEmpty(),
            cause,
          )
        },
        loadFailureLogger = { error ->
          logSchemaLoadFailure(logger, "phase output", resource, resource, error)
        },
        expectedSchemaId = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = version,
        identityFailure = { reason ->
          InvalidFeatureTaskRuntimePhaseOutputSchemaError(resource, reason)
        },
      ),
    )
}

@Inject
class ContractFeatureTaskRuntimePhaseOutputMigration : FeatureTaskRuntimePhaseOutputMigrationPort {
  override fun migrate(payload: String): FeatureTaskRuntimePhaseOutputMigrationResult {
    val parsed = JsonCodec.parseObjectOrNull(payload)
    if (parsed == null) {
      return FeatureTaskRuntimePhaseOutputMigrationResult.Refused(
        null,
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT,
      )
    }
    val sourceVersion =
      parsed[SharedPayloadKeys.CONTRACT_VERSION]
        ?.takeIf { it is JsonPrimitive && it.isString }
        ?.let { it as JsonPrimitive }
        ?.content
    val fields = JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(parsed))
    if (fields == null) {
      return FeatureTaskRuntimePhaseOutputMigrationResult.Refused(
        sourceVersion,
        FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        FeatureTaskRuntimePhaseOutputMigrationResult.Refusal.CORRUPT,
      )
    }
    return when (val result = FeatureTaskRuntimePhaseOutputMigrator.migrate(fields)) {
      is FeatureTaskRuntimePhaseOutputMigration.Current -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Current(payload)
      }
      is FeatureTaskRuntimePhaseOutputMigration.Migrated -> {
        FeatureTaskRuntimePhaseOutputMigrationResult.Migrated(
          JsonCodec.mapToJsonString(result.payload),
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
}
