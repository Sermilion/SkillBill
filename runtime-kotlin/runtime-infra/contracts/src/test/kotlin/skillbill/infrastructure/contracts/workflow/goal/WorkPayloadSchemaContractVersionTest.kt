package skillbill.infrastructure.contracts.workflow.goal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.identity.status.WORK_BOARD_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.status.WORK_DETAIL_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.WorkBoardSchemaPaths
import skillbill.infrastructure.contracts.locator.WorkDetailSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkPayloadSchemaContractVersionTest {
  @Test
  fun `board version id and required envelope fields match Kotlin contract`() {
    val schema = classpathSchema(WorkBoardSchemaPaths.CLASSPATH_RESOURCE)
    assertEquals(WORK_BOARD_CONTRACT_VERSION, schema.path("properties").path("contract_version").path("const").asText())
    assertEquals(WorkBoardSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertTrue(
      schema.path("required").map { it.asText() }
        .containsAll(listOf("contract_version", "items", "observed_at", "finished_within", "diagnostics")),
    )
  }

  @Test
  fun `detail version id and required envelope fields match Kotlin contract`() {
    val schema = classpathSchema(WorkDetailSchemaPaths.CLASSPATH_RESOURCE)
    assertEquals(
      WORK_DETAIL_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(WorkDetailSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertTrue(
      schema.path("required").map { it.asText() }.containsAll(listOf("contract_version", "observed_at", "item")),
    )
  }

  private fun classpathSchema(resource: String): JsonNode {
    val stream = javaClass.classLoader.getResourceAsStream(resource)
    assertNotNull(stream, "Missing packaged schema '$resource'.")
    return stream.use { YAMLMapper().readTree(it) }
  }
}
