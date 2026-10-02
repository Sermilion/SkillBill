package skillbill.infrastructure.contracts.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.InvalidReviewContextSchemaError
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.ValidatedClasspathYamlNodeRequest
import skillbill.infrastructure.contracts.locator.ReviewContextSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.ReviewContextWireMap
import java.util.logging.Level
import java.util.logging.Logger

internal const val MAX_REPORTED_VIOLATIONS: Int = 4

private val reviewContextLog: Logger =
  Logger.getLogger("skillbill.contracts.review.ReviewContextSchemaValidator")

@Inject
class ReviewContextSchemaValidator : ReviewContextEnvelopeValidator {
  private val schemas: ReviewContextSchemas
    get() = loadReviewContextSchema()
  private val mapper: ObjectMapper
    get() = ClasspathContractSchemaLoader.sharedObjectMapper()

  override fun validate(
    envelope: ReviewContextWireMap,
    sourceLabel: String,
  ) {
    validate(envelope as Map<String, Any?>, sourceLabel)
  }

  override fun validateSpecIntentProjection(
    envelope: ReviewContextWireMap,
    sourceLabel: String,
  ) {
    validateSpecIntentProjection(envelope as Map<String, Any?>, sourceLabel)
  }

  fun validate(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val kind = envelope["kind"] as? String
    validatePayloadAgainst(envelope, sourceLabel, kind, schemas.forKind(kind), mapper)
  }

  fun validateParentPacket(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "parent_packet", schemas, mapper)

  fun validateAssignment(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "assignment", schemas, mapper)

  fun validateLaunch(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "launch", schemas, mapper)

  fun validateIntegrationLaunch(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "integration_launch", schemas, mapper)

  fun validateVerificationLaunch(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "verification_launch", schemas, mapper)

  fun validateAdjudicationLaunch(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "adjudication_launch", schemas, mapper)

  fun validateFindingVerdict(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) = validateExpectedKind(envelope, sourceLabel, "finding_verdict", schemas, mapper)

  fun validateSpecIntentProjection(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) = validatePayloadAgainst(
    payload,
    sourceLabel,
    "spec_intent_projection",
    schemas.forDefinition("spec_intent_projection"),
    mapper,
  )

  companion object {
    private val canonical: ReviewContextSchemaValidator by lazy(::ReviewContextSchemaValidator)

    fun validate(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validate(envelope, sourceLabel)

    fun validateParentPacket(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateParentPacket(envelope, sourceLabel)

    fun validateAssignment(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateAssignment(envelope, sourceLabel)

    fun validateLaunch(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateLaunch(
      envelope,
      sourceLabel,
    )

    fun validateIntegrationLaunch(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateIntegrationLaunch(envelope, sourceLabel)

    fun validateVerificationLaunch(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateVerificationLaunch(envelope, sourceLabel)

    fun validateAdjudicationLaunch(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateAdjudicationLaunch(envelope, sourceLabel)

    fun validateFindingVerdict(
      envelope: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateFindingVerdict(envelope, sourceLabel)

    fun validateSpecIntentProjection(
      payload: Map<String, Any?>,
      sourceLabel: String,
    ) = canonical.validateSpecIntentProjection(payload, sourceLabel)
  }
}

private fun validateExpectedKind(
  envelope: Map<String, Any?>,
  sourceLabel: String,
  expectedKind: String,
  schemas: ReviewContextSchemas,
  mapper: ObjectMapper,
) {
  val kind = envelope["kind"]
  if (kind != expectedKind) {
    throw InvalidReviewContextSchemaError(
      sourceLabel = sourceLabel,
      reason = "Expected a '$expectedKind' envelope but the payload declares kind='${kind ?: "<missing>"}'.",
      definitionName = expectedKind,
    )
  }
  validatePayloadAgainst(envelope, sourceLabel, expectedKind, schemas.forKind(expectedKind), mapper)
}

private fun requireMatchingContractVersion(
  payload: Map<String, Any?>,
  sourceLabel: String,
  definitionName: String?,
) {
  val declared = payload[SharedPayloadKeys.CONTRACT_VERSION] ?: return
  val declaredText = declared as? String ?: declared.toString()
  if (declaredText == REVIEW_CONTEXT_CONTRACT_VERSION) return
  throw InvalidReviewContextSchemaError(
    sourceLabel = sourceLabel,
    reason =
      "contract_version mismatch: envelope declares '$declaredText' but the runtime requires " +
        "'$REVIEW_CONTEXT_CONTRACT_VERSION'.",
    definitionName = definitionName,
  )
}

private fun validatePayloadAgainst(
  payload: Map<String, Any?>,
  sourceLabel: String,
  definitionName: String?,
  schema: JsonSchema,
  mapper: ObjectMapper,
) {
  requireMatchingContractVersion(payload, sourceLabel, definitionName)
  val instance: JsonNode = mapper.valueToTree(payload)
  val errors: Set<ValidationMessage> = schema.validate(instance)
  if (errors.isNotEmpty()) {
    val sorted = errors.sortedWith(violationOrdering)
    reviewContextLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, sorted, instance))
    throw InvalidReviewContextSchemaError(
      sourceLabel = sourceLabel,
      reason = formatValidationReason(sorted, instance),
      definitionName = definitionName,
    )
  }
}

private fun buildSchemaDriftLog(
  sourceLabel: String,
  sorted: List<ValidationMessage>,
  instance: JsonNode,
): String {
  val parts =
    sorted.take(2).map { error ->
      val location = error.instanceLocation?.toString().orEmpty()
      val fieldPath = dottedFieldPath(location).ifBlank { "<root>" }
      val offendingValue = offendingValue(instance, location)
      if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
    }
  return "Review context envelope failed schema validation: source='$sourceLabel' " +
    "violations=${parts.joinToString(", ")} totalViolations=${sorted.size}"
}

internal fun formatValidationReason(
  sorted: List<ValidationMessage>,
  instance: JsonNode,
): String {
  val firstError = sorted.first()
  val offendingValue = offendingValue(instance, firstError.instanceLocation?.toString().orEmpty())
  return buildString {
    append(dottedFieldPath(firstError.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
    append(": ")
    append(firstError.message)
    if (offendingValue.isNotBlank()) {
      append(" — offending value: ")
      append(offendingValue)
    }
    sorted.drop(1).take(MAX_REPORTED_VIOLATIONS).forEach { other ->
      append(" | ")
      append(dottedFieldPath(other.instanceLocation?.toString().orEmpty()).ifBlank { "<root>" })
      append(": ")
      append(other.message)
    }
  }
}

internal val violationOrdering: Comparator<ValidationMessage> =
  compareBy(
    { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
    { it.instanceLocation?.toString().orEmpty() },
    { it.message.orEmpty() },
  )

private const val MAX_OFFENDING_VALUE_CHARS: Int = 120

private val REDACTED_FIELD_SEGMENTS: Set<String> =
  setOf("excerpt", "content", "reason", "reachability_reason", "rubric", "specialist_contract", "status")

internal fun offendingValue(
  instance: JsonNode,
  instanceLocation: String,
): String {
  val dotted = dottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  val segments = dotted.split('.')
  if (segments.any { it in REDACTED_FIELD_SEGMENTS }) return "<redacted>"
  var node: JsonNode = instance
  segments.forEach { segment ->
    if (segment.isBlank()) return@forEach
    node = if (segment.toIntOrNull() != null) node.path(segment.toInt()) else node.path(segment)
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText().take(MAX_OFFENDING_VALUE_CHARS)
    else -> ""
  }
}

internal fun dottedFieldPath(instanceLocation: String): String =
  when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }

internal const val REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE: String =
  ReviewContextSchemaPaths.CLASSPATH_RESOURCE

internal const val REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH: String =
  ReviewContextSchemaPaths.REPO_RELATIVE_PATH

private val NESTED_REVIEW_CONTEXT_DEFINITIONS: List<String> = listOf("spec_intent_projection")

internal class ReviewContextSchemas(private val envelope: JsonSchema, private val branches: Map<String, JsonSchema>) {
  fun forKind(kind: String?): JsonSchema = branches[kind] ?: envelope

  fun forDefinition(name: String): JsonSchema =
    branches[name]
      ?: throw InvalidReviewContextSchemaError(
        sourceLabel = ReviewContextSchemaPaths.CLASSPATH_RESOURCE,
        reason = "Canonical review context schema has no compiled definition '$name'.",
        definitionName = name,
      )
}

private fun logReviewContextSchemaFailure(error: Throwable): Throwable {
  logSchemaLoadFailure(
    reviewContextLog,
    "review context",
    REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
    REVIEW_CONTEXT_SCHEMA_REPO_RELATIVE_PATH,
    error,
  )
  return error
}

private fun loadReviewContextSchema(): ReviewContextSchemas {
  return compileReviewContextSchemas(readReviewContextSchemaNode())
}

private fun readReviewContextSchemaNode(): JsonNode {
  return try {
    ClasspathContractSchemaLoader.readValidatedClasspathYamlNode(
      ValidatedClasspathYamlNodeRequest(
        classLoader = ReviewContextSchemaValidator::class.java.classLoader,
        resource = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
        missingResource = {
          InvalidReviewContextSchemaError(
            sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
            reason = "Canonical review context schema is missing from the classpath.",
          )
        },
        processingFailure = { cause ->
          InvalidReviewContextSchemaError(
            sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
            reason = cause.message ?: cause::class.simpleName.orEmpty(),
            cause = cause,
          )
        },
        expectedSchemaId = ReviewContextSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
        contractVersionMatches = ::reviewContextContractVersionMatches,
        identityFailure = { reason ->
          InvalidReviewContextSchemaError(
            sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
            reason = reason,
          )
        },
      ),
    )
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    throw logReviewContextSchemaFailure(error)
  }
}

private fun reviewContextContractVersionMatches(
  node: JsonNode,
  expected: String,
): Boolean =
  node.path("\$defs").fields().asSequence()
    .map { (_, definition) ->
      definition.path("properties").path(SharedPayloadKeys.CONTRACT_VERSION).path("const").asText("")
    }
    .filter(String::isNotBlank)
    .all { it == expected }

private fun compileReviewContextSchemas(yamlNode: JsonNode): ReviewContextSchemas {
  try {
    val mapper = ClasspathContractSchemaLoader.sharedObjectMapper()
    val envelopeSchema =
      ClasspathContractSchemaLoader.compiledSchemaFromYamlNode(
        cacheKey = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
        yamlNode = yamlNode,
        processingFailure = { cause ->
          InvalidReviewContextSchemaError(
            sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
            reason = cause.message ?: cause::class.simpleName.orEmpty(),
            cause = cause,
          )
        },
      )
    val defs = yamlNode.path("\$defs")
    val oneOfNames =
      yamlNode.path("oneOf").asSequence()
        .map { branch -> branch.path("\$ref").asText("").substringAfterLast('/') }
        .filter { name -> name.isNotBlank() && !defs.path(name).isMissingNode }
        .toList()
    val definitionNames =
      (oneOfNames + NESTED_REVIEW_CONTEXT_DEFINITIONS)
        .distinct()
    val branches =
      definitionNames.associateWith { name ->
        if (defs.path(name).isMissingNode) {
          throw InvalidReviewContextSchemaError(
            sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
            reason = "Canonical review context schema is missing definition '$name'.",
            definitionName = name,
          )
        }
        val wrapper = mapper.createObjectNode()
        wrapper.put("\$schema", yamlNode.path("\$schema").asText())
        wrapper.put("\$ref", "#/\$defs/" + name)
        wrapper.set<ObjectNode>("\$defs", defs.deepCopy())
        ClasspathContractSchemaLoader.compiledSchemaFromYamlNode(
          cacheKey = "$REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE#$name",
          yamlNode = wrapper,
          processingFailure = { cause ->
            InvalidReviewContextSchemaError(
              sourceLabel = REVIEW_CONTEXT_SCHEMA_CLASSPATH_RESOURCE,
              reason = cause.message ?: cause::class.simpleName.orEmpty(),
              definitionName = name,
              cause = cause,
            )
          },
        )
      }
    return ReviewContextSchemas(envelopeSchema, branches)
  } catch (error: InvalidReviewContextSchemaError) {
    throw logReviewContextSchemaFailure(error)
  }
}
