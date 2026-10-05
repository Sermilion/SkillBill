package skillbill.infrastructure.contracts.workflow.decomposition

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.BUNDLE_JOURNAL_CONTRACT_VERSION
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestBundleJournal
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.DecompositionManifestBundleJournalSchemaPaths
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

object DecompositionManifestBundleJournalSchemaValidator {
  private val mapper: ObjectMapper
    get() = ClasspathContractSchemaLoader.sharedObjectMapper()
  private val yamlMapper: YAMLMapper =
    YAMLMapper(YAMLFactory().apply { enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) })
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  fun validateYamlText(
    yamlText: String,
    sourceLabel: String,
  ): Map<String, Any?> {
    val node = readYamlObjectNode(yamlText, sourceLabel)
    val parsed = yamlObjectNodeToMap(node, sourceLabel)
    validateMap(parsed, sourceLabel)
    return parsed
  }

  fun validateMap(
    manifest: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val contractVersion = manifest[SharedPayloadKeys.CONTRACT_VERSION]
    if (contractVersion != BUNDLE_JOURNAL_CONTRACT_VERSION) {
      throw invalidDecompositionManifestBundleJournal(
        sourceLabel = sourceLabel,
        reason =
          "Unsupported contract_version '$contractVersion'. Supported version is " +
            "$BUNDLE_JOURNAL_CONTRACT_VERSION. Back up the marker and its staging directory, " +
            "review evidence manually, then remove the marker only after backup.",
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_UNSUPPORTED_CONTRACT_VERSION,
      )
    }
    val instance: JsonNode = mapper.valueToTree(manifest)
    val errors: Set<ValidationMessage> = schema().validate(instance)
    if (errors.isNotEmpty()) {
      throw invalidDecompositionManifestBundleJournal(
        sourceLabel = sourceLabel,
        reason = errors.sortedBy { it.message }.joinToString("; ") { it.message },
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_INVALID,
      )
    }
  }

  private fun readYamlObjectNode(
    yamlText: String,
    sourceLabel: String,
  ): JsonNode {
    val node = parseYamlNode(yamlText, sourceLabel)
    if (node == null || !node.isObject) {
      throw invalidDecompositionManifestBundleJournal(
        sourceLabel = sourceLabel,
        reason = "<root> must be an object.",
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ROOT_NOT_OBJECT,
      )
    }
    return node
  }

  private fun parseYamlNode(
    yamlText: String,
    sourceLabel: String,
  ): JsonNode? =
    try {
      yamlMapper.factory.createParser(yamlText).use { parser ->
        val parsed = yamlMapper.readTree<JsonNode>(parser)
        require(parser.nextToken() == null) { "YAML contains trailing content or multiple documents." }
        parsed
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: IOException) {
      throw invalidDecompositionManifestBundleJournal(
        sourceLabel = sourceLabel,
        reason = error.message ?: "Malformed YAML.",
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_YAML_PARSE_ERROR,
        cause = error,
      )
    }

  private fun yamlObjectNodeToMap(
    node: JsonNode,
    sourceLabel: String,
  ): Map<String, Any?> =
    try {
      val converted =
        yamlMapper.readerFor(mapType).readValue<Map<String, Any?>>(node)
          ?: throw invalidDecompositionManifestBundleJournal(
            sourceLabel = sourceLabel,
            reason = "<root> must be an object.",
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_ROOT_NOT_OBJECT,
          )
      converted
    } catch (error: CancellationException) {
      throw error
    } catch (error: JsonProcessingException) {
      throw invalidDecompositionManifestBundleJournal(
        sourceLabel = sourceLabel,
        reason = error.message ?: "Malformed YAML object.",
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_YAML_OBJECT_ERROR,
        cause = error,
      )
    }

  private fun schema(): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = DecompositionManifestBundleJournalSchemaValidator::class.java.classLoader,
        classpathResource = DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = {
          invalidDecompositionManifestBundleJournal(
            sourceLabel = DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE,
            reason = "Canonical bundle journal schema resource is missing from the classpath.",
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_RESOURCE_MISSING,
          )
        },
        processingFailure = { cause ->
          invalidDecompositionManifestBundleJournal(
            sourceLabel = DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE,
            reason = cause.message ?: cause::class.simpleName.orEmpty(),
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_LOAD_ERROR,
            cause = cause,
          )
        },
        loadFailureLogger = {},
        expectedSchemaId = DecompositionManifestBundleJournalSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = BUNDLE_JOURNAL_CONTRACT_VERSION,
        identityFailure = { reason ->
          invalidDecompositionManifestBundleJournal(
            sourceLabel = DecompositionManifestBundleJournalSchemaPaths.CLASSPATH_RESOURCE,
            reason = reason,
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_BUNDLE_JOURNAL_SCHEMA_IDENTITY_ERROR,
          )
        },
      ),
    )
}
