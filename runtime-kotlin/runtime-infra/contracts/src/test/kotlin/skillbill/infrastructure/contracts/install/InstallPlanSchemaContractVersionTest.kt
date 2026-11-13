package skillbill.infrastructure.contracts.install

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.install.INSTALL_PLAN_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.InstallPlanSchemaPaths
import skillbill.install.model.SupportedAgent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallPlanSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches INSTALL_PLAN_CONTRACT_VERSION`() {
    val resourceStream =
      InstallPlanSchemaValidator::class.java.classLoader
        .getResourceAsStream(InstallPlanSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical install-plan schema is missing from the classpath at " +
        "'${InstallPlanSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyInstallPlanSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")
    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      INSTALL_PLAN_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal INSTALL_PLAN_CONTRACT_VERSION " +
        "($INSTALL_PLAN_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema agentId enum matches SupportedAgent supportedIds in order`() {
    val schema = readSchema()
    val agentIds = schema.path("\$defs").path("agentId").path("enum").map(JsonNode::asText)

    assertEquals(
      SupportedAgent.supportedIds,
      agentIds,
      "Schema `\$defs.agentId.enum` must list exactly the SupportedAgent ids, in the same order.",
    )
    assertContains(agentIds, "cursor")
    assertContains(agentIds, "opencode")
  }

  @Test
  fun `schema id matches InstallPlanSchemaPaths EXPECTED_SCHEMA_ID`() {
    val resourceStream =
      InstallPlanSchemaValidator::class.java.classLoader
        .getResourceAsStream(InstallPlanSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical install-plan schema is missing from the classpath at " +
        "'${InstallPlanSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    val schema: JsonNode = YAMLMapper().readTree(yamlText)
    val idNode = schema.path("\$id")
    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal InstallPlanSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  private fun readSchema(): JsonNode {
    val resourceStream =
      InstallPlanSchemaValidator::class.java.classLoader
        .getResourceAsStream(InstallPlanSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical install-plan schema is missing from the classpath at " +
        "'${InstallPlanSchemaPaths.CLASSPATH_RESOURCE}'.",
    )
    return YAMLMapper().readTree(resourceStream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}
