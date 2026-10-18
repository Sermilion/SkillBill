package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.AcceptanceCriterionRepairStateSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AcceptanceCriterionRepairStateSchemaContractVersionTest {
  @Test
  fun `packaged schema pins the owning version id and strict top level shape`() {
    val schemaStream =
      assertNotNull(
        AcceptanceCriterionRepairStateSchemaValidator::class.java.classLoader
          .getResourceAsStream(AcceptanceCriterionRepairStateSchemaPaths.CLASSPATH_RESOURCE),
        "Canonical repair-state schema must be packaged for validation.",
      )
    val schema = YAMLMapper().readTree(schemaStream.use { it.readBytes().toString(Charsets.UTF_8) })

    assertEquals(
      ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(AcceptanceCriterionRepairStateSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    assertTrue(!schema.path("additionalProperties").asBoolean(true))
  }
}
