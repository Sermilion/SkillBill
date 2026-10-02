package skillbill.infrastructure.contracts.workflow.decomposition

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.featuretask.DECOMPOSITION_MANIFEST_CONTRACT_VERSION
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.DecompositionManifestSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.phaseoutput.FeatureTaskRuntimePhaseOutputStructuralRepair
import skillbill.infrastructure.contracts.phaseoutput.FeatureTaskRuntimePhaseOutputStructuralRepairDecision
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.decodeManifest
import skillbill.workflow.decomposition.model.DecompositionManifestRepairEvidence
import skillbill.workflow.decomposition.model.DecompositionManifestRepairOperation
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFormat
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.DecompositionManifestValidationSourceLocation
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputFormat
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairOperation
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputSourceLocation
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

private val decompositionManifestLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.DecompositionManifestSchemaValidator")

@Inject
class DecompositionManifestSchemaValidator : DecompositionManifestValidator {
  private val yamlMapper: YAMLMapper =
    YAMLMapper(YAMLFactory()).apply { enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY) }
  private val mapType = object : TypeReference<Map<String, Any?>>() {}

  override fun validate(
    manifest: DecompositionManifestWireMap,
    sourceLabel: String,
  ) {
    validate(manifest as Map<String, Any?>, sourceLabel)
  }

  fun validate(
    manifest: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(manifest)
    val errors: Set<ValidationMessage> =
      ClasspathContractSchemaLoader.validate(decompositionManifestSchema(), instance)
    if (errors.isEmpty()) {
      DecompositionManifestCoherenceValidator.validate(manifest, sourceLabel)
      return
    }
    decompositionManifestLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = sourceLabel,
      reason = formatValidationReason(errors.sortedWith(violationOrdering), instance),
      failureCode = "schema_invalid",
    )
  }

  override fun validateYamlText(
    yamlText: String,
    sourceLabel: String,
  ) = decodeManifest(
    DecompositionManifestWireMap.from(validateYamlTextMap(yamlText, sourceLabel)),
    sourceLabel,
  )

  fun validateYamlTextMap(
    yamlText: String,
    sourceLabel: String,
  ): Map<String, Any?> {
    val node = readYamlObjectNode(yamlText, sourceLabel)
    val parsed = yamlObjectNodeToMap(node, sourceLabel)
    validate(parsed, sourceLabel)
    return parsed
  }

  private fun readYamlObjectNode(
    yamlText: String,
    sourceLabel: String,
  ): JsonNode {
    val node = parseYamlNode(yamlText, sourceLabel)
    if (node == null || !node.isObject) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = sourceLabel,
        reason = "<root> must be an object.",
        failureCode = "root_not_object",
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
    } catch (error: MismatchedInputException) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = sourceLabel,
        reason = "YAML contains a duplicate key; duplicate keys are never repaired.",
        failureCode = DecompositionManifestValidationFailureCode.DUPLICATE_KEY.wireValue,
        cause = error,
      )
    } catch (error: JsonProcessingException) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = sourceLabel,
        reason = "YAML is malformed: ${error.message.orEmpty()}",
        failureCode = DecompositionManifestValidationFailureCode.MALFORMED.wireValue,
        cause = error,
      )
    } catch (error: IllegalArgumentException) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = sourceLabel,
        reason = "YAML is malformed: ${error.message.orEmpty()}",
        failureCode = "malformed",
        cause = error,
      )
    }

  private fun yamlObjectNodeToMap(
    node: JsonNode,
    sourceLabel: String,
  ): Map<String, Any?> =
    try {
      ClasspathContractSchemaLoader.sharedObjectMapper().convertValue(node, mapType)
    } catch (error: IllegalArgumentException) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = sourceLabel,
        reason = "YAML root object cannot be converted to a string-keyed map: ${error.message.orEmpty()}",
        failureCode = "invalid_shape",
        cause = error,
      )
    }

  private fun buildSchemaDriftLog(
    sourceLabel: String,
    errors: Set<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val parts =
      errors.sortedWith(violationOrdering).take(2).map { error ->
        val location = error.instanceLocation?.toString().orEmpty()
        val fieldPath = decompositionManifestDottedFieldPath(location).ifBlank { "<root>" }
        val offendingValue = extractDecompositionManifestOffendingValue(instance, location)
        if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
      }
    return "Decomposition manifest failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(
    sorted: List<ValidationMessage>,
    instance: JsonNode,
  ): String {
    val firstError = sorted.first()
    val instanceLocation = firstError.instanceLocation?.toString().orEmpty()
    val fieldPath = decompositionManifestDottedFieldPath(instanceLocation).ifBlank { "<root>" }
    val offendingValue = extractDecompositionManifestOffendingValue(instance, instanceLocation)
    return buildString {
      append(fieldPath)
      append(": ")
      append(firstError.message)
      if (offendingValue.isNotBlank()) {
        append(" — offending value: ")
        append(offendingValue)
      }
      sorted.drop(1).forEach { other ->
        val otherLocation = other.instanceLocation?.toString().orEmpty()
        val otherPath = decompositionManifestDottedFieldPath(otherLocation).ifBlank { "<root>" }
        val otherValue = extractDecompositionManifestOffendingValue(instance, otherLocation)
        append(" | ")
        append(otherPath)
        append(": ")
        append(other.message)
        if (otherValue.isNotBlank()) {
          append(" — offending value: ")
          append(otherValue)
        }
      }
    }
  }

  private val violationOrdering: Comparator<ValidationMessage> =
    compareBy(
      { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
      { it.instanceLocation?.toString().orEmpty() },
      { it.message.orEmpty() },
    )

  override fun validateYamlTextResult(
    yamlText: String,
    sourceLabel: String,
  ): DecompositionManifestValidationResult {
    val decision = FeatureTaskRuntimePhaseOutputStructuralRepair.inspectWholeDocument(yamlText, sourceLabel)
    return when (decision) {
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Rejected ->
        DecompositionManifestValidationResult.Rejected(
          code = decision.code.toManifestFailureCode(),
          reason = decision.reason,
          sourceLocation = decision.sourceLocation?.toManifestLocation(),
        )
      is FeatureTaskRuntimePhaseOutputStructuralRepairDecision.Accepted ->
        try {
          val wireMap = DecompositionManifestWireMap.from(validateYamlTextMap(decision.text, sourceLabel))
          val manifest = decodeManifest(wireMap, sourceLabel)
          val evidence = decision.evidence?.toManifestEvidence()
          if (evidence == null) {
            DecompositionManifestValidationResult.AcceptedUnchanged(manifest, decision.text)
          } else {
            DecompositionManifestValidationResult.AcceptedAfterRepair(manifest, decision.text, evidence)
          }
        } catch (error: InvalidDecompositionManifestSchemaError) {
          DecompositionManifestValidationResult.Rejected(
            code = DecompositionManifestValidationFailureCode.fromWire(error.failureCode),
            reason = error.reason,
          )
        }
    }
  }

  private fun FeatureTaskRuntimePhaseOutputFailureCode.toManifestFailureCode():
    DecompositionManifestValidationFailureCode =
    when (this) {
      FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED ->
        DecompositionManifestValidationFailureCode.MALFORMED
      FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT ->
        DecompositionManifestValidationFailureCode.ROOT_NOT_OBJECT
      FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY ->
        DecompositionManifestValidationFailureCode.DUPLICATE_KEY
      FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE ->
        DecompositionManifestValidationFailureCode.NO_REPAIR_CANDIDATE
      FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR,
      FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES,
      -> DecompositionManifestValidationFailureCode.AMBIGUOUS_REPAIR
      FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED ->
        DecompositionManifestValidationFailureCode.REPAIR_LIMIT_EXCEEDED
      FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR ->
        DecompositionManifestValidationFailureCode.UNSUPPORTED_REPAIR
      FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID,
      FeatureTaskRuntimePhaseOutputFailureCode.PHASE_ID_MISMATCH,
      FeatureTaskRuntimePhaseOutputFailureCode.SEMANTIC_INVALID,
      -> DecompositionManifestValidationFailureCode.SCHEMA_INVALID
    }

  private fun FeatureTaskRuntimePhaseOutputRepairEvidence.toManifestEvidence(): DecompositionManifestRepairEvidence =
    DecompositionManifestRepairEvidence(
      format =
        when (format) {
          FeatureTaskRuntimePhaseOutputFormat.JSON -> DecompositionManifestValidationFormat.JSON
          FeatureTaskRuntimePhaseOutputFormat.YAML -> DecompositionManifestValidationFormat.YAML
        },
      originalDigest = originalDigest,
      repairedDigest = repairedDigest,
      operation =
        when (operation) {
          FeatureTaskRuntimePhaseOutputRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER ->
            DecompositionManifestRepairOperation.REMOVE_EXTRA_CLOSING_DELIMITER
          FeatureTaskRuntimePhaseOutputRepairOperation.ADD_MISSING_CLOSING_DELIMITER ->
            DecompositionManifestRepairOperation.ADD_MISSING_CLOSING_DELIMITER
          FeatureTaskRuntimePhaseOutputRepairOperation.DEDUPLICATE_KEYS,
          FeatureTaskRuntimePhaseOutputRepairOperation.RESTORE_EXPECTED_SHAPE,
          ->
            error("Decomposition-manifest repair does not include ${operation.wireValue}.")
        },
      sourceLocation = sourceLocation.toManifestLocation(),
    )

  private fun FeatureTaskRuntimePhaseOutputSourceLocation.toManifestLocation():
    DecompositionManifestValidationSourceLocation =
    DecompositionManifestValidationSourceLocation(sourceLabel, offset, line, column)

  companion object {
    private val canonical: DecompositionManifestSchemaValidator by lazy(::DecompositionManifestSchemaValidator)

    fun validate(
      manifest: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validate(manifest, sourceLabel)

    fun validateYamlText(
      yamlText: String,
      sourceLabel: String,
    ): Map<String, Any?> = canonical.validateYamlTextMap(yamlText, sourceLabel)
  }
}

internal const val DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE: String =
  DecompositionManifestSchemaPaths.CLASSPATH_RESOURCE

internal const val DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH: String =
  DecompositionManifestSchemaPaths.REPO_RELATIVE_PATH

private fun decompositionManifestSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = DecompositionManifestSchemaValidator::class.java.classLoader,
      classpathResource = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        InvalidDecompositionManifestSchemaError(
          sourceLabel = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
          reason =
            "Canonical decomposition manifest schema is missing. Expected to find it on the JVM classpath at " +
              "'$DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        InvalidDecompositionManifestSchemaError(
          sourceLabel = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          decompositionManifestLog,
          "decomposition manifest",
          DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
          DECOMPOSITION_MANIFEST_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = DecompositionManifestSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = DECOMPOSITION_MANIFEST_CONTRACT_VERSION,
      identityFailure = { reason ->
        InvalidDecompositionManifestSchemaError(
          sourceLabel = DECOMPOSITION_MANIFEST_SCHEMA_CLASSPATH_RESOURCE,
          reason = reason,
        )
      },
    ),
  )

internal fun decompositionManifestDottedFieldPath(instanceLocation: String): String =
  when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }

internal fun extractDecompositionManifestOffendingValue(
  instance: JsonNode,
  instanceLocation: String,
): String {
  val dotted = decompositionManifestDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) {
          node = node.path(keyPart)
        }
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> {
        node = node.path(rawSegment.toInt())
      }
      else -> {
        node = node.path(rawSegment)
      }
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}
