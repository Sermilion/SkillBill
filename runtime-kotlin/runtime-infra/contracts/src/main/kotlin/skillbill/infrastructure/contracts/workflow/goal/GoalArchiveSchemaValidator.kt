package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import skillbill.contracts.workflow.goal.GOAL_ARCHIVE_CONTRACT_VERSION
import skillbill.error.goal.invalidGoalArchiveSchemaError
import skillbill.error.goal.missingGoalArchiveSchemaResourceError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.GoalArchiveSchemaPaths
import skillbill.infrastructure.contracts.locator.logSchemaLoadFailure
import skillbill.infrastructure.contracts.review.dottedFieldPath
import skillbill.infrastructure.contracts.review.formatValidationReason
import skillbill.infrastructure.contracts.review.violationOrdering
import skillbill.infrastructure.contracts.workflow.issue.inlineIssueKeySchemaRefs
import java.util.logging.Logger

private val goalArchiveLog: Logger =
  Logger.getLogger("skillbill.contracts.workflow.GoalArchiveSchemaValidator")

object GoalArchiveSchemaValidator {
  fun validate(
    envelope: Map<String, Any?>,
    sourceLabel: String,
  ) {
    val instance: JsonNode = ClasspathContractSchemaLoader.valueToTree(envelope)
    val errors = ClasspathContractSchemaLoader.validate(goalArchiveSchema(), instance)
    if (errors.isEmpty()) return
    val sortedErrors = errors.sortedWith(violationOrdering)
    throw invalidGoalArchiveSchemaError(
      sourceLabel = sourceLabel,
      fieldPath = dottedFieldPath(sortedErrors.first().instanceLocation?.toString().orEmpty()),
      reason = formatValidationReason(sortedErrors, instance),
    )
  }
}

private fun goalArchiveSchema(): JsonSchema =
  ClasspathContractSchemaLoader.compiledSchema(
    CompiledSchemaRequest(
      cacheKey = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
      classLoader = GoalArchiveSchemaValidator::class.java.classLoader,
      classpathResource = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
      missingResource = {
        missingGoalArchiveSchemaResourceError(GoalArchiveSchemaPaths.CLASSPATH_RESOURCE)
      },
      processingFailure = { cause ->
        invalidGoalArchiveSchemaError(
          sourceLabel = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
          fieldPath = "",
          reason = cause.message ?: cause::class.simpleName.orEmpty(),
          cause = cause,
        )
      },
      loadFailureLogger = { error ->
        logSchemaLoadFailure(
          goalArchiveLog,
          "goal archive",
          GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
          GoalArchiveSchemaPaths.REPO_RELATIVE_PATH,
          error,
        )
      },
      expectedSchemaId = GoalArchiveSchemaPaths.EXPECTED_SCHEMA_ID,
      expectedContractVersion = GOAL_ARCHIVE_CONTRACT_VERSION,
      prepareSchemaDocument = { yamlNode ->
        yamlNode.inlineIssueKeySchemaRefs()
      },
      identityFailure = { reason ->
        invalidGoalArchiveSchemaError(
          sourceLabel = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
          fieldPath = "<schema>",
          reason = reason,
        )
      },
    ),
  )
