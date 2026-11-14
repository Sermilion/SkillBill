package skillbill.infrastructure.skills.nativeagent

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.NativeAgentLinkInventorySchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeAgentLinkInventorySchemaContractVersionTest {
  @Test
  fun `inventory schema version and id match runtime constants`() {
    val stream =
      javaClass.classLoader.getResourceAsStream(
        NativeAgentLinkInventorySchemaPaths.CLASSPATH_RESOURCE.removePrefix("/"),
      )
    assertNotNull(stream)
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(
      NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(NativeAgentLinkInventorySchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertTrue(
      schema.path("properties").path("entries").path("items").path("properties")
        .path("provider").path("enum").any { provider -> provider.asText() == "opencode" },
    )
    assertTrue(
      schema.path("properties").path("entries").path("items").path("properties")
        .path("provider").path("enum").any { provider -> provider.asText() == "devin" },
    )
  }
}
