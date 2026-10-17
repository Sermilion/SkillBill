package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.identity.task.STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.StandaloneMonitorOwnershipSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StandaloneMonitorOwnershipSchemaContractVersionTest {
  @Test
  fun `standalone monitor ownership schema version and id match runtime constants`() {
    val stream =
      assertNotNull(
        javaClass.classLoader.getResourceAsStream(StandaloneMonitorOwnershipSchemaPaths.CLASSPATH_RESOURCE),
      )
    val schema = stream.use { YAMLMapper().readTree(it) }
    assertEquals(
      STANDALONE_MONITOR_OWNERSHIP_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(StandaloneMonitorOwnershipSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }
}
