package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.goal.GOAL_PROGRESS_EVENT_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalProgressEventSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalProgressEventSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.offendingValue
import skillbill.infrastructure.contracts.review.violationOrdering
import java.util.logging.Level
import java.util.logging.Logger

private val goalProgressLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalProgressEventSchemaValidator")

object GoalProgressEventSchemaValidator {
  fun validate(
    event: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(event)
    val errors = ClasspathContractSchemaLoader.validate(goalProgressEventSchema(), instance)
    if (errors.isEmpty()) return
    goalProgressLog.log(Level.WARNING, buildSchemaDriftLog(sourceLabel, errors, instance))
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw invalidGoalProgressEventSchemaError(
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
    return "Goal progress event failed schema validation: source='$sourceLabel' " +
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

internal const val GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE: String =
  GoalProgressEventSchemaPaths.CLASSPATH_RESOURCE

internal const val GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH: String =
  GoalProgressEventSchemaPaths.REPO_RELATIVE_PATH

private fun goalProgressEventSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
      classLoader = GoalProgressEventSchemaValidator::class.java.classLoader,
      classpathResource = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
      missingResource = {
        invalidGoalProgressEventSchemaError(
          sourceLabel = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason =
            "Canonical goal progress event schema is missing. Expected classpath resource " +
              "'$GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE'.",
        )
      },
      processingFailure = { cause ->
        invalidGoalProgressEventSchemaError(
          sourceLabel = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          goalProgressLog,
          "goal progress event",
          GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          GOAL_PROGRESS_EVENT_SCHEMA_REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = GoalProgressEventSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = GOAL_PROGRESS_EVENT_CONTRACT_VERSION,
      identityFailure = { reason ->
        invalidGoalProgressEventSchemaError(
          sourceLabel = GOAL_PROGRESS_EVENT_SCHEMA_CLASSPATH_RESOURCE,
          fieldPath = "<schema>",
          reason = reason,
        )
      },
    ),
  )
