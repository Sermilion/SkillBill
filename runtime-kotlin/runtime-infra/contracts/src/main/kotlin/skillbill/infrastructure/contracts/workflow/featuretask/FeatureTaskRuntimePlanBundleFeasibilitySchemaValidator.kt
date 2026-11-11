package skillbill.infrastructure.contracts.workflow.featuretask

import com.networknt.schema.ValidationMessage
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PlanBundleFeasibilityFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths
import skillbill.infrastructure.contracts.review.MAX_REPORTED_VIOLATIONS
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePlanBundleFeasibilityPayloadKeys as Keys

object FeatureTaskRuntimePlanBundleFeasibilitySchemaValidator {
  fun validate(
    payload: Map<String, Any?>,
    sourceLabel: String,
  ) {
    if (payload[Keys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION) {
      throw SkillBillRuntimeException(
        PlanBundleFeasibilityFailureCode.UNSUPPORTED_VERSION,
        "Plan bundle feasibility artifact '$sourceLabel' has an unsupported contract version.",
      )
    }
    val errors = ClasspathContractSchemaLoader.validate(schema(), ClasspathContractSchemaLoader.valueToTree(payload))
    if (errors.isNotEmpty()) {
      throw SkillBillRuntimeException(
        PlanBundleFeasibilityFailureCode.INVALID_STATE,
        "Plan bundle feasibility artifact '$sourceLabel' fails schema validation: ${formatReason(errors)}. " +
          "Preserve the original artifact and recover with a compatible runtime or restore the source workflow record.",
      )
    }
  }

  private fun formatReason(errors: Set<ValidationMessage>): String =
    errors.sortedBy { it.instanceLocation?.toString().orEmpty() }
      .take(MAX_REPORTED_VIOLATIONS)
      .joinToString(" | ") {
        "${it.instanceLocation?.toString()?.ifBlank { "<root>" } ?: "<root>"}: ${it.message.orEmpty()}"
      }

  private fun schema() =
    ClasspathContractSchemaLoader.compiledSchema(
      CompiledSchemaRequest(
        cacheKey = FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths.CLASSPATH_RESOURCE,
        classLoader = FeatureTaskRuntimePlanBundleFeasibilitySchemaValidator::class.java.classLoader,
        classpathResource = FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths.CLASSPATH_RESOURCE,
        missingResource = { failure("Canonical plan bundle feasibility schema is missing.") },
        processingFailure = { failure("Could not load plan bundle feasibility schema.", it) },
        loadFailureLogger = {},
        expectedSchemaId = FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION,
        identityFailure = { failure(it) },
      ),
    )

  private fun failure(
    message: String,
    cause: Throwable? = null,
  ) = SkillBillRuntimeException(PlanBundleFeasibilityFailureCode.INVALID_STATE, message, cause)
}
