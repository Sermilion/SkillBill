package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.goal.GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalObservabilityEventSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalObservabilityEventSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.infrastructure.contracts.review.violationOrdering
import java.util.logging.Level
import java.util.logging.Logger

private val goalObservabilityLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalObservabilityEventSchemaValidator")

object GoalObservabilityEventSchemaValidator {
  fun validate(
    event: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(event)
    val errors = ClasspathContractSchemaLoader.validate(goalObservabilityEventSchema(), instance)
    if (errors.isEmpty()) return
    goalObservabilityLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw invalidGoalObservabilityEventSchemaError(
      sourceLabel = sourceLabel,
      fieldPath =
        goalObservabilityDottedFieldPath(
          sortedErrors.first().instanceLocation?.toString().orEmpty(),
        ),
      reason = formatValidationReason(sortedErrors, instance),
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
        val fieldPath = goalObservabilityDottedFieldPath(location).ifBlank { "<root>" }
        val offendingValue = extractGoalObservabilityOffendingValue(instance, location)
        if (offendingValue.isNotBlank()) "$fieldPath=$offendingValue" else fieldPath
      }
    return "Goal observability event failed schema validation: source='$sourceLabel' " +
      "violations=${parts.joinToString(", ")} totalViolations=${errors.size}"
  }

  private fun formatValidationReason(
    sorted: List<ValidationMessage>,
    instance: JsonNode,
  ): String =
    sorted.joinToString(" | ") { error ->
      val instanceLocation = error.instanceLocation?.toString().orEmpty()
      val fieldPath = goalObservabilityDottedFieldPath(instanceLocation).ifBlank { "<root>" }
      val offendingValue = extractGoalObservabilityOffendingValue(instance, instanceLocation)
      buildString {
        append(fieldPath)
        append(": ")
        append(error.message)
        if (offendingValue.isNotBlank()) {
          append(" — offending value: ")
          append(offendingValue)
        }
      }
    }

  private val violationOrdering: Comparator<ValidationMessage> =
    compareBy(
      { it.instanceLocation?.toString().orEmpty().let { loc -> loc.isBlank() || loc == "$" || loc == "/" } },
      { it.instanceLocation?.toString().orEmpty() },
      { it.message.orEmpty() },
    )
}

internal const val GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE: String =
  GoalObservabilityEventSchemaPaths.CLASSPATH_RESOURCE

internal const val GOAL_OBSERVABILITY_EVENT_SCHEMA_REPO_RELATIVE_PATH: String =
  GoalObservabilityEventSchemaPaths.REPO_RELATIVE_PATH

private fun goalObservabilityEventSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = GoalObservabilityEventSchemaValidator::class.java.classLoader,
      classpathResource = GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidGoalObservabilityEventSchemaError(
          sourceLabel = GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason =
            "Canonical goal-observability event schema is missing. Expected classpath resource " +
              "'$GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidGoalObservabilityEventSchemaError(
          sourceLabel = GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          goalObservabilityLog,
          "goal-observability event",
          GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          GOAL_OBSERVABILITY_EVENT_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = GoalObservabilityEventSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = GOAL_OBSERVABILITY_EVENT_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidGoalObservabilityEventSchemaError(
          sourceLabel = GOAL_OBSERVABILITY_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "<schema>",
          reason = reason,
        )
      },
    ),
  )

internal fun goalObservabilityDottedFieldPath(instanceLocation: String): String =
  when {
    instanceLocation.isBlank() || instanceLocation == "/" || instanceLocation == "$" -> ""
    instanceLocation.startsWith("$.") -> instanceLocation.removePrefix("$.")
    instanceLocation.startsWith("$") -> instanceLocation.removePrefix("$").trimStart('.')
    else -> instanceLocation.trimStart('/').replace('/', '.')
  }

internal fun extractGoalObservabilityOffendingValue(
  instance: JsonNode,
  instanceLocation: String,
): String {
  val dotted = goalObservabilityDottedFieldPath(instanceLocation)
  if (dotted.isBlank()) return ""
  var node: JsonNode = instance
  dotted.split('.').forEach { rawSegment ->
    if (rawSegment.isBlank()) return@forEach
    val arrayMatch = Regex("^([^\\[]*)\\[(\\d+)]$").matchEntire(rawSegment)
    when {
      arrayMatch != null -> {
        val (keyPart, indexPart) = arrayMatch.destructured
        if (keyPart.isNotBlank()) node = node.path(keyPart)
        node = node.path(indexPart.toInt())
      }
      node.isArray && rawSegment.toIntOrNull() != null -> node = node.path(rawSegment.toInt())
      else -> node = node.path(rawSegment)
    }
  }
  return when {
    node.isMissingNode -> ""
    node.isValueNode -> node.asText()
    else -> ""
  }
}
