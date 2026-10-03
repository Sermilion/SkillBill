package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_HISTORICAL_PHASE_OUTPUT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_SCHEMA_ID
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalPlanningPreparationSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.dottedFieldPath
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.infrastructure.contracts.workflow.issue.inlineIssueKeySchemaRefs
import java.util.logging.Level
import java.util.logging.Logger

private val goalPlanningPreparationLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalPlanningPreparationSchemaValidator")

object GoalPlanningPreparationSchemaValidator {
  fun validate(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) {
    validate(envelope, sourceLabel, goalPlanningPreparationSchema())
  }

  fun validateHistoricalPhaseOutput06(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) {
    validateHistoricalPins(envelope, sourceLabel)
    validate(
      envelope,
      sourceLabel,
      goalPlanningPreparationSchema(
        GoalPlanningPreparationSchemaPaths.HISTORICAL_0_2_PHASE_OUTPUT_0_6_CLASSPATH_RESOURCE,
        GoalPlanningPreparationSchemaPaths.HISTORICAL_0_2_PHASE_OUTPUT_0_6_REPO_RELATIVE_PATH,
        GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      ),
    )
  }

  private fun validateHistoricalPins(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val provenance = envelope[GoalPlanningPreparationPayloadKeys.PROVENANCE] as? Map<*, *>
    val pins =
      listOf(
        HistoricalPin(
          SharedPayloadKeys.CONTRACT_VERSION,
          GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
        ),
        HistoricalPin(
          GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_ID,
          GOAL_PLANNING_PREPARATION_SCHEMA_ID,
          GoalPlanningPreparationPayloadKeys.PROVENANCE,
        ),
        HistoricalPin(
          GoalPlanningPreparationPayloadKeys.PLANNING_CONTRACT_VERSION,
          GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
          GoalPlanningPreparationPayloadKeys.PROVENANCE,
        ),
        HistoricalPin(
          GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_ID,
          FEATURE_TASK_RUNTIME_PHASE_OUTPUT_SCHEMA_ID,
          GoalPlanningPreparationPayloadKeys.PROVENANCE,
        ),
        HistoricalPin(
          GoalPlanningPreparationPayloadKeys.PHASE_OUTPUT_CONTRACT_VERSION,
          GOAL_PLANNING_PREPARATION_HISTORICAL_PHASE_OUTPUT_VERSION,
          GoalPlanningPreparationPayloadKeys.PROVENANCE,
        ),
      )
    val mismatch =
      pins.firstOrNull { pin ->
        val actual = if (pin.parentKey == null) envelope[pin.key] else provenance?.get(pin.key)
        actual != pin.value
      }
    if (mismatch != null) {
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = mismatch.parentKey?.let { "$it.${mismatch.key}" } ?: mismatch.key,
        reason = "historical preparation pin is missing or does not match the declared source contract",
      )
    }
  }

  private data class HistoricalPin(
    val key: String,
    val value: String,
    val parentKey: String? = null,
  )

  private fun validate(
    envelope: Map<String, Any?>,
    sourceLabel: String,
    schema: JsonSchema,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(envelope)
    val errors: Set<ValidationMessage> = ClasspathContractSchemaLoader.validate(schema, instance)
    if (errors.isNotEmpty()) {
      val sorted = errors.sortedWith(violationOrdering)
      val fieldPath = dottedFieldPath(sorted.first().instanceLocation?.toString().orEmpty())
      val validationReason = "preparation contract validation failed at $fieldPath"
      goalPlanningPreparationLog.log(Level.WARNING, "Planning preparation contract failed: violations=${sorted.size}")
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = fieldPath,
        reason = validationReason,
      )
    }
  }
}

internal const val GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE: String =
  GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE

internal const val GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH: String =
  GoalPlanningPreparationSchemaPaths.REPO_RELATIVE_PATH

private fun goalPlanningPreparationSchema(
  resource: String = GOAL_PLANNING_PREPARATION_SCHEMA_CLASSPATH_RESOURCE,
  repoRelativePath: String = GOAL_PLANNING_PREPARATION_SCHEMA_REPO_RELATIVE_PATH,
  version: String = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = resource,
      classLoader = GoalPlanningPreparationSchemaValidator::class.java.classLoader,
      classpathResource = resource,
      missingResource = {
        InvalidGoalPlanningPreparationSchemaError(
          sourceLabel = resource,
          fieldPath = "",
          reason =
            "Canonical goal planning preparation schema is missing. Expected classpath resource " +
              "'$resource'.",
        )
      },
      processingFailure = { cause ->
        InvalidGoalPlanningPreparationSchemaError(
          sourceLabel = resource,
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          goalPlanningPreparationLog,
          "goal planning preparation",
          resource,
          repoRelativePath,
          error,
        )
      },
      expectedSchemaId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = version,
      identityFailure = { reason ->
        InvalidGoalPlanningPreparationSchemaError(
          sourceLabel = resource,
          fieldPath = "<schema>",
          reason = reason,
        )
      },
      prepareSchemaDocument = { yamlNode ->
        yamlNode.inlineIssueKeySchemaRefs()
      },
    ),
  )
