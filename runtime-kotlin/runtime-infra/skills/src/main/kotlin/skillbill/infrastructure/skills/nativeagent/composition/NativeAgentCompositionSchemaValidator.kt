package skillbill.infrastructure.skills.nativeagent.composition

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.error.shellcontent.invalidNativeAgentCompositionSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import java.util.logging.Level
import java.util.logging.Logger

private val log: Logger = Logger.getLogger("skillbill.nativeagent.NativeAgentCompositionSchemaValidator")

object NativeAgentCompositionSchemaValidator {
  private val yamlMapper: YAMLMapper = YAMLMapper()

  fun validate(
    yamlText: String,
    sourceLabel: String,
  ) {
    val instance: JsonNode =
      try {
        yamlMapper.readTree(yamlText)
      } catch (error: JsonProcessingException) {
        throw invalidNativeAgentCompositionSchemaError(
          sourceLabel = sourceLabel,
          reason = "could not parse YAML for schema validation: ${error.message.orEmpty()}",
          cause = error,
        )
      }
    validateNode(instance, sourceLabel)
  }

  fun validateParsedNode(
    node: JsonNode,
    sourceLabel: String,
  ) {
    validateNode(node, sourceLabel)
  }

  private fun validateNode(
    instance: JsonNode,
    sourceLabel: String,
  ) {
    val errors: Set<ValidationMessage> = loadSchema().validate(instance)
    if (errors.isEmpty()) {
      return
    }

    log.log(Level.WARNING, buildSchemaDriftLog(errors, sourceLabel))
    val sorted = errors.sortedWith(violationOrdering)
    val reason = formatValidationReason(sorted)
    throw invalidNativeAgentCompositionSchemaError(sourceLabel = sourceLabel, reason = reason)
  }

  private fun buildSchemaDriftLog(
    errors: Set<ValidationMessage>,
    sourceLabel: String,
  ): String {
    val sorted = errors.sortedWith(violationOrdering)
    val topTwo = sorted.take(2)
    val parts =
      topTwo.map { error ->
        val location = error.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
        "$location: ${error.message.orEmpty()}"
      }
    return "Native agent composition source '$sourceLabel' failed schema validation: " +
      "violations=${parts.joinToString(" | ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(sorted: List<ValidationMessage>): String =
    buildString {
      val first = sorted.first()
      val firstLocation = first.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
      append(firstLocation).append(": ").append(first.message.orEmpty())
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty().ifBlank { "<root>" }
        append(" | ").append(otherLocation).append(": ").append(other.message.orEmpty())
      }
    }

  private val violationOrdering: Comparator<ValidationMessage> =
    compareBy(
      { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
      { it.instanceLocation?.toString().orEmpty() },
      { it.message.orEmpty() },
    )

  private fun loadSchema(): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = NativeAgentCompositionSchemaValidator::class.java.classLoader,
        classpathResource = NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = {
          invalidNativeAgentCompositionSchemaError(
            sourceLabel = "<schema-load>",
            reason =
              "Canonical native-agent composition schema is missing. Expected classpath resource " +
                "'${NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE}'.",
          )
        },
        processingFailure = { cause ->
          invalidNativeAgentCompositionSchemaError(
            sourceLabel = "<schema-load>",
            reason = cause.message ?: cause::class.simpleName.orEmpty(),
            cause = cause,
          )
        },
        loadFailureLogger = { error ->
          logSchemaLoadFailure(
            log,
            "native-agent composition",
            NativeAgentCompositionSchemaPaths.CLASSPATH_RESOURCE,
            NativeAgentCompositionSchemaPaths.REPO_RELATIVE_PATH,
            error,
          )
        },
        expectedSchemaId = NativeAgentCompositionSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = NATIVE_AGENT_COMPOSITION_CONTRACT_VERSION,
        contractVersionPath = listOf("\$defs", "contractVersion", "const"),
        identityFailure = { reason ->
          invalidNativeAgentCompositionSchemaError(
            sourceLabel = "<schema-load>",
            reason = reason,
          )
        },
      ),
    )
}
