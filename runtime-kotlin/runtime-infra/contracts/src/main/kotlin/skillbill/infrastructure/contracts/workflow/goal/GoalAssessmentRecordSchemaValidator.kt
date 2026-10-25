package skillbill.infrastructure.contracts.workflow.goal

import com.networknt.schema.JsonSchema
import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalAssessmentRecord
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalAssessmentRecordSchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.workflow.taskruntime.artifact.toGoalAssessmentRecord
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

object GoalAssessmentRecordSchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val errors: Set<ValidationMessage> =
      ClasspathContractSchemaLoader.validate(schema(), ClasspathContractSchemaLoader.valueToTree(payload))
    if (errors.isNotEmpty()) {
      throw invalidGoalAssessmentRecord(sourceLabel, formatReason(errors))
    }
    FeatureTaskRuntimeWorkflowArtifactMap.from(payload).toGoalAssessmentRecord(sourceLabel)
  }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") { error ->
        "${error.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${error.message.orEmpty()}"
      } + if (errors.size > MAX_REPORTED_VIOLATIONS) " (+${errors.size - MAX_REPORTED_VIOLATIONS} more)" else ""

  private fun schema(): JsonSchema =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE,
        classLoader = GoalAssessmentRecordSchemaValidator::class.java.classLoader,
        classpathResource = GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE,
        missingResource = {
          invalidGoalAssessmentRecord(
            GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE,
            "Canonical goal-assessment record schema resource is missing.",
          )
        },
        processingFailure = { cause ->
          invalidGoalAssessmentRecord(
            GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE,
            cause.message ?: cause::class.simpleName.orEmpty(),
            cause,
          )
        },
        loadFailureLogger = {},
        expectedSchemaId = GoalAssessmentRecordSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = GOAL_ASSESSMENT_RECORD_CONTRACT_VERSION,
        identityFailure = { reason ->
          invalidGoalAssessmentRecord(GoalAssessmentRecordSchemaPaths.CLASSPATH_RESOURCE, reason)
        },
      ),
    )
}
