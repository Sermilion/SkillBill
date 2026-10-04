package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.SchemaIdentityRequest
import skillbill.infrastructure.contracts.locator.GoalPlanningPreparationSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalPlanningPreparationSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches GOAL_PLANNING_PREPARATION_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal GOAL_PLANNING_PREPARATION_CONTRACT_VERSION " +
        "($GOAL_PLANNING_PREPARATION_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches GoalPlanningPreparationSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `validator asserts identity of the classpath schema`() {
    val yamlNode = classpathSchema()
    ClasspathContractSchemaLoader.validateSchemaIdentity(
      SchemaIdentityRequest(
        yamlNode = yamlNode,
        classpathResource = GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE,
        expectedSchemaId = GoalPlanningPreparationSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
        identityFailure = { reason ->
          invalidGoalPlanningPreparationSchemaError(
            sourceLabel = GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE,
            fieldPath = "<schema>",
            reason = reason,
          )
        },
      ),
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      GoalPlanningPreparationSchemaValidator::class.java.classLoader
        .getResourceAsStream(GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical goal planning preparation schema is missing from the classpath at " +
        "'${GoalPlanningPreparationSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyGoalPlanningPreparationSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
