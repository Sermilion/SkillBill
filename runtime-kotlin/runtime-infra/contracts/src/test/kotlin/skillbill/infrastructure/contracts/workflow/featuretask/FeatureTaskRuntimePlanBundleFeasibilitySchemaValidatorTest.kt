package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PlanBundleFeasibilityFailureCode
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePlanBundleFeasibilityPayloadKeys as K

class FeatureTaskRuntimePlanBundleFeasibilitySchemaValidatorTest {
  @Test
  fun `packaged schema identity and version match the contract`() {
    val schema: JsonNode =
      ClasspathContractSchemaLoader.readClasspathYamlNode(
        FeatureTaskRuntimePlanBundleFeasibilitySchemaValidator::class.java.classLoader,
        FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths.CLASSPATH_RESOURCE,
      ) { error("schema missing") }
    assertEquals(FeatureTaskRuntimePlanBundleFeasibilitySchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertEquals(
      FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION,
      schema.path("properties").path(K.CONTRACT_VERSION).path("const").asText(),
    )
  }

  @Test
  fun `unsupported versions and unknown state keys fail with owned codes`() {
    val unsupported =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimePlanBundleFeasibilitySchemaValidator.validate(
          validState() + (K.CONTRACT_VERSION to "0.2"),
          "test",
        )
      }
    assertEquals(PlanBundleFeasibilityFailureCode.UNSUPPORTED_VERSION, unsupported.code)
    val invalid =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimePlanBundleFeasibilitySchemaValidator.validate(validState() + ("unexpected" to true), "test")
      }
    assertEquals(PlanBundleFeasibilityFailureCode.INVALID_STATE, invalid.code)
  }

  private fun validState(): Map<String, Any?> {
    val hash = "a".repeat(64)
    return mapOf(
      K.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY_CONTRACT_VERSION,
      K.REVISION to 0,
      K.MODE to "review",
      K.IDENTITY to
        mapOf(
          K.PARENT_SHA256 to hash,
          K.MANIFEST_SHA256 to hash,
          K.IMMUTABLE_MANIFEST_SHA256 to hash,
          K.SUBTASKS to
            listOf(
              mapOf(
                K.ID to 1,
                K.SPEC_PATH to "spec.md",
                K.SPEC_SHA256 to hash,
                K.CRITERION_REFS to listOf("1.1"),
                K.DEPENDENCIES to emptyList<Any>(),
              ),
            ),
        ),
      K.ACCEPTED_LINES to emptyList<Any>(),
      K.CONFLICTS to emptyList<Any>(),
      K.CHANGED_SUBTASK_IDS to emptyList<Int>(),
      K.REQUIRED_REFS to listOf("1.1"),
      K.CARRIED_REFS to emptyList<String>(),
      K.REVIEW_ROUNDS_STARTED to 0,
      K.REPAIR_LAUNCHES_STARTED to 0,
      K.ACTIVE_LAUNCH to null,
      K.SETTLED_LAUNCHES to emptyList<Any>(),
      K.LAST_REVIEW_VALUE to null,
      K.APPROVAL to null,
      K.LEGACY_ORIGIN to false,
    )
  }
}
