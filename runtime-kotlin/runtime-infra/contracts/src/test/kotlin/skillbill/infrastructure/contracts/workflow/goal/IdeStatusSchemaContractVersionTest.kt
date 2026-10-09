package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.contracts.workflow.identity.status.IDE_STATUS_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.IdeStatusSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IdeStatusSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches IDE_STATUS_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(IDE_STATUS_CONTRACT_VERSION, contractVersionNode.asText())
  }

  @Test
  fun `agent activity fields bump contract version to 0_2`() {
    val schema = classpathSchema()
    assertEquals("0.3", schema.path("properties").path("contract_version").path("const").asText())
    assertEquals("0.3", IDE_STATUS_CONTRACT_VERSION)
    assertTrue(
      !schema.path("properties").path("last_agent_activity_at").isMissingNode,
      "last_agent_activity_at must be present in the schema this parity test pins.",
    )
    assertTrue(
      !schema.path("properties").path("last_agent_activity_label").isMissingNode,
      "last_agent_activity_label must be present in the schema this parity test pins.",
    )
  }

  @Test
  fun `planning property remains present at contract 0_2`() {
    val schema = classpathSchema()
    assertEquals(IDE_STATUS_CONTRACT_VERSION, schema.path("properties").path("contract_version").path("const").asText())
    assertTrue(
      !schema.path("properties").path("planning").isMissingNode,
      "planning must be present in the schema this parity test pins.",
    )
  }

  @Test
  fun `pause signal properties remain present at contract 0_2`() {
    val schema = classpathSchema()
    assertEquals(IDE_STATUS_CONTRACT_VERSION, schema.path("properties").path("contract_version").path("const").asText())
    assertTrue(!schema.path("properties").path("pause_requested").isMissingNode)
    assertTrue(!schema.path("properties").path("paused_at").isMissingNode)
  }

  @Test
  fun `current_phase_execution remains present at contract 0_2`() {
    val schema = classpathSchema()
    assertEquals(IDE_STATUS_CONTRACT_VERSION, schema.path("properties").path("contract_version").path("const").asText())
    assertTrue(!schema.path("properties").path("current_phase_execution").isMissingNode)
    assertTrue(!schema.path("properties").path("planning").isMissingNode)
  }

  @Test
  fun `planning wave maxItems mirrors GOAL_PLANNING_WAVE_CAP`() {
    val maxItems =
      classpathSchema()
        .path("properties").path("planning")
        .path("properties").path("planning_wave_subtask_ids")
        .path("maxItems")

    assertTrue(maxItems.isInt, "planning_wave_subtask_ids.maxItems must be an integer; found: $maxItems")
    assertEquals(GOAL_PLANNING_WAVE_CAP, maxItems.asInt())
  }

  @Test
  fun `schema id matches IdeStatusSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    assertEquals(IdeStatusSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      IdeStatusSchemaValidator::class.java.classLoader
        .getResourceAsStream(IdeStatusSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical IDE status schema is missing from the classpath at " +
        "'${IdeStatusSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
