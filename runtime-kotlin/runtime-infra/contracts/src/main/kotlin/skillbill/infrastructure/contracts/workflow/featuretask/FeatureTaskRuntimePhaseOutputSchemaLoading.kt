package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseOutputSchemaPaths
import java.util.logging.Level
import java.util.logging.Logger

internal val featureTaskRuntimePhaseOutputLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.FeatureTaskRuntimePhaseOutputWireSchema")

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE

internal const val FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH: String =
  FeatureTaskRuntimePhaseOutputSchemaPaths.REPO_RELATIVE_PATH

internal fun loadFeatureTaskRuntimePhaseOutputSchema(): JsonSchema =
  compilePhaseOutputSchema(
    cacheKey = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    prepareSchemaDocument = {},
  )

internal fun loadFeatureTaskRuntimePhaseOutputLegacyReadSchema(): JsonSchema =
  compilePhaseOutputSchema(
    cacheKey = "$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE$LEGACY_READ_CACHE_SUFFIX",
    prepareSchemaDocument = ::widenForLegacyRead,
  )

internal fun isLegacyReadableContractVersion(contractVersion: String?): Boolean =
  contractVersion == FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION

private const val LEGACY_READ_CACHE_SUFFIX: String = "#legacy-read"
private const val SCHEMA_PROPERTIES: String = "properties"
private const val SCHEMA_DEFS: String = "\$defs"
private const val SCHEMA_TYPE: String = "type"
private const val SCHEMA_ENUM: String = "enum"
private const val SCHEMA_STRING_TYPE: String = "string"
private const val UNIFORM_SETTLEMENT_DEF: String = "uniformSettlement"

private fun widenForLegacyRead(yamlNode: JsonNode) {
  val nodes = JsonNodeFactory.instance
  val readableVersions = nodes.arrayNode().add(FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION)
  val contractVersion = nodes.objectNode().put(SCHEMA_TYPE, SCHEMA_STRING_TYPE)
  contractVersion.set<JsonNode>(SCHEMA_ENUM, readableVersions)
  (yamlNode.path(SCHEMA_PROPERTIES) as ObjectNode).set<JsonNode>(SharedPayloadKeys.CONTRACT_VERSION, contractVersion)
  (yamlNode.path(SCHEMA_DEFS) as ObjectNode).set<JsonNode>(UNIFORM_SETTLEMENT_DEF, nodes.objectNode())
}

private fun compilePhaseOutputSchema(
  cacheKey: String,
  prepareSchemaDocument: (JsonNode) -> Unit,
): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = cacheKey,
      classLoader = FeatureTaskRuntimePhaseOutputWireSchema::class.java.classLoader,
      classpathResource = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
          reason =
            "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
              "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error -> logFeatureTaskRuntimePhaseOutputSchemaLoadFailure(error) },
      expectedSchemaId = FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      identityFailure = { reason ->
        InvalidFeatureTaskRuntimePhaseOutputSchemaError(
          sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
      prepareSchemaDocument = prepareSchemaDocument,
    ),
  )

private fun logFeatureTaskRuntimePhaseOutputSchemaLoadFailure(error: Throwable) {
  featureTaskRuntimePhaseOutputLog.log(
    Level.SEVERE,
    "Failed to load canonical feature-task-runtime phase output schema: " +
      "classpath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE' " +
      "repoRelativePath='$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_REPO_RELATIVE_PATH' " +
      "errorType='${error::class.qualifiedName}' message='${error.message.orEmpty()}'",
    error,
  )
}

internal fun readFeatureTaskRuntimePhaseOutputSchemaText(): String =
  ClasspathContractSchemaLoader.readClasspathYamlText(
    classLoader = FeatureTaskRuntimePhaseOutputWireSchema::class.java.classLoader,
    resource = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
    missingError = {
      InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE,
        reason =
          "Canonical feature-task-runtime phase output schema is missing. Expected to find it on the JVM " +
            "classpath at '$FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_CLASSPATH_RESOURCE'.",
      )
    },
  )

internal val FENCED_BLOCK = Regex("```[ \\t]*[A-Za-z0-9_-]*\\r?\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

internal fun phaseOutputObjectCandidates(raw: String): List<String> {
  val trimmed = raw.trim()
  return buildList {
    FENCED_BLOCK.findAll(trimmed).map { it.groupValues[1].trim() }.toList().asReversed().forEach(::add)
    balancedTopLevelObjectSpans(trimmed).asReversed().forEach(::add)
    val open = trimmed.indexOf('{')
    val close = trimmed.lastIndexOf('}')
    if (open in 0 until close) {
      add(trimmed.substring(open, close + 1))
    }
    add(trimmed)
  }.filter(String::isNotBlank).distinct()
}

internal fun balancedTopLevelObjectSpans(text: String): List<String> {
  val scanner = TopLevelObjectScanner(text)
  return text.indices.mapNotNull(scanner::consume)
}

private class TopLevelObjectScanner(private val text: String) {
  private var depth = 0
  private var start = -1
  private var inString = false
  private var escaped = false

  fun consume(index: Int): String? {
    val ch = text[index]
    if (inString) {
      advanceStringState(ch)
      return null
    }
    return advanceStructuralState(ch, index)
  }

  private fun advanceStringState(ch: Char) {
    if (escaped) {
      escaped = false
      return
    }
    when (ch) {
      '\\' -> escaped = true
      '"' -> inString = false
    }
  }

  private fun advanceStructuralState(
    ch: Char,
    index: Int,
  ): String? {
    when (ch) {
      '"' -> inString = true
      '{' -> openObject(index)
      '}' -> return closeObject(index)
    }
    return null
  }

  private fun openObject(index: Int) {
    if (depth == 0) start = index
    depth += 1
  }

  private fun closeObject(index: Int): String? {
    if (depth == 0) return null
    depth -= 1
    if (depth != 0 || start < 0) return null
    val span = text.substring(start, index + 1)
    start = -1
    return span
  }
}
