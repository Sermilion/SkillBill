package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.goal.GOAL_ARCHIVE_CONTRACT_VERSION
import skillbill.error.goal.invalidGoalArchiveSchemaError
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.SchemaIdentityRequest
import skillbill.infrastructure.contracts.locator.GoalArchiveSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoalArchiveSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches GOAL_ARCHIVE_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      GOAL_ARCHIVE_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal GOAL_ARCHIVE_CONTRACT_VERSION ($GOAL_ARCHIVE_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches GoalArchiveSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      GoalArchiveSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal GoalArchiveSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `validator asserts identity of the classpath schema`() {
    val yamlNode = classpathSchema()
    ClasspathContractSchemaLoader.validateSchemaIdentity(
      SchemaIdentityRequest(
        yamlNode = yamlNode,
        classpathResource = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
        expectedSchemaId = GoalArchiveSchemaPaths.EXPECTED_SCHEMA_ID,
        expectedContractVersion = GOAL_ARCHIVE_CONTRACT_VERSION,
        identityFailure = { reason ->
          invalidGoalArchiveSchemaError(
            sourceLabel = GoalArchiveSchemaPaths.CLASSPATH_RESOURCE,
            fieldPath = "<schema>",
            reason = reason,
          )
        },
      ),
    )
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      GoalArchiveSchemaValidator::class.java.classLoader
        .getResourceAsStream(GoalArchiveSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical goal archive schema is missing from the classpath at " +
        "'${GoalArchiveSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyGoalArchiveSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
