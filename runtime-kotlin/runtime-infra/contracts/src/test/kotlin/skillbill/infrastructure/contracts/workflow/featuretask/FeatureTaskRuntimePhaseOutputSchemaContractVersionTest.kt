package skillbill.infrastructure.contracts.workflow.featuretask

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.FeatureTaskRuntimePhaseOutputSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePhaseOutputSchemaContractVersionTest {
  @Test
  fun `schema contract_version const matches FEATURE_TASK_RUNTIME_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val contractVersionNode = schema.path("properties").path("contract_version").path("const")

    assertTrue(
      !contractVersionNode.isMissingNode && contractVersionNode.isTextual,
      "Schema must pin properties.contract_version.const as a string; found: $contractVersionNode",
    )
    assertEquals(
      FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
      contractVersionNode.asText(),
      "Schema contract_version.const must equal FEATURE_TASK_RUNTIME_CONTRACT_VERSION " +
        "($FEATURE_TASK_RUNTIME_CONTRACT_VERSION).",
    )
  }

  @Test
  fun `schema id matches FeatureTaskRuntimePhaseOutputSchemaPaths EXPECTED_SCHEMA_ID`() {
    val schema = classpathSchema()
    val idNode = schema.path("\$id")

    assertTrue(!idNode.isMissingNode && idNode.isTextual, "Schema must declare a textual `\$id`.")
    assertEquals(
      FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID,
      idNode.asText(),
      "Schema `\$id` must equal FeatureTaskRuntimePhaseOutputSchemaPaths.EXPECTED_SCHEMA_ID.",
    )
  }

  @Test
  fun `repair receipt nested contract_version const matches FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION`() {
    val schema = classpathSchema()
    val receiptVersion =
      schema.path("\$defs").path("repairReceipt").path("properties")
        .path("contract_version").path("const")
    assertTrue(receiptVersion.isTextual, "repairReceipt must pin contract_version const.")
    assertEquals(FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION, receiptVersion.asText())
  }

  private fun classpathSchema(): JsonNode {
    val resourceStream =
      FeatureTaskRuntimePhaseOutputWireSchema::class.java.classLoader
        .getResourceAsStream(FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE)
    assertNotNull(
      resourceStream,
      "Canonical feature-task-runtime phase output schema is missing from the classpath at " +
        "'${FeatureTaskRuntimePhaseOutputSchemaPaths.CLASSPATH_RESOURCE}'. " +
        "Ensure `copyFeatureTaskRuntimePhaseOutputSchema` ran before this test.",
    )
    val yamlText = resourceStream.use { it.readBytes().toString(Charsets.UTF_8) }
    return YAMLMapper().readTree(yamlText)
  }
}
