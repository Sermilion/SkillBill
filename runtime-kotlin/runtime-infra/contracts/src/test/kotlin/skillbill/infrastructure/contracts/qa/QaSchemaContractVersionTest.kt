package skillbill.infrastructure.contracts.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.qa.QA_DEVICE_LEASE_CONTRACT_VERSION
import skillbill.contracts.qa.QA_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.qa.QA_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.qa.QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.qa.QaRepairStatePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.contracts.locator.QaDeviceLeaseSchemaPaths
import skillbill.infrastructure.contracts.locator.QaReceiptSchemaPaths
import skillbill.infrastructure.contracts.locator.QaRepairStateSchemaPaths
import skillbill.infrastructure.contracts.workflow.featuretask.QaRepairStateSchemaValidator
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.toQaRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class QaSchemaContractVersionTest {
  @Test
  fun `single receipt state upgrades without losing findings or repair accounting`() {
    val state =
      QaRepairState(
        subtaskId = 1,
        attempts = listOf(QaRepairAttempt(1, "tree", listOf("F-1"), "repair", QaRepairAttemptOutcome.COMPLETED)),
        openFindings = listOf(QaOpenFinding("F-1", listOf("AC-001"), listOf("/tmp/original/receipt.json"))),
        lastReceiptTreeIdentity = "tree",
      )
    val current = state.asQaRepairStateWireMap()
    val oldFinding =
      mapOf(
        QaRepairStatePayloadKeys.ID to "F-1",
        QaRepairStatePayloadKeys.AFFECTED_CRITERIA to listOf("AC-001"),
        QaRepairStatePayloadKeys.RECEIPT_REF to "/tmp/original/receipt.json",
      )
    val previous =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        current +
          mapOf(
            QaRepairStatePayloadKeys.CONTRACT_VERSION to QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
            QaRepairStatePayloadKeys.OPEN_FINDINGS to listOf(oldFinding),
          ),
      )

    QaRepairStateSchemaValidator.validate(previous)
    val upgraded = previous.toQaRepairState().asQaRepairStateWireMap()
    QaRepairStateSchemaValidator.validate(upgraded)

    assertEquals(state, upgraded.toQaRepairState())
    assertEquals(current, upgraded)
    val invalid = current + (QaRepairStatePayloadKeys.OPEN_FINDINGS to listOf(oldFinding))
    assertFailsWith<SkillBillRuntimeException> { QaRepairStateSchemaValidator.validate(invalid) }
  }

  @Test
  fun `current and previous repair state schemas pin their versions and identity`() {
    for ((resource, version) in listOf(
      QaRepairStateSchemaPaths.CLASSPATH_RESOURCE to QA_REPAIR_STATE_CONTRACT_VERSION,
      QaRepairStateSchemaPaths.PREVIOUS_CLASSPATH_RESOURCE to QA_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
    )) {
      val schema = classpathSchema(resource)
      assertEquals(version, schema.path("properties").path("contract_version").path("const").asText())
      assertEquals(QaRepairStateSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
    }
  }

  @Test
  fun `the receipt schema pins QA_RECEIPT_CONTRACT_VERSION under its expected id`() {
    val schema = classpathSchema(QaReceiptSchemaPaths.CLASSPATH_RESOURCE)

    assertEquals(QA_RECEIPT_CONTRACT_VERSION, schema.path("properties").path("contract_version").path("const").asText())
    assertEquals(QaReceiptSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  @Test
  fun `the device lease schema pins QA_DEVICE_LEASE_CONTRACT_VERSION under its expected id`() {
    val schema = classpathSchema(QaDeviceLeaseSchemaPaths.CLASSPATH_RESOURCE)

    assertEquals(
      QA_DEVICE_LEASE_CONTRACT_VERSION,
      schema.path("properties").path("contract_version").path("const").asText(),
    )
    assertEquals(QaDeviceLeaseSchemaPaths.EXPECTED_SCHEMA_ID, schema.path("\$id").asText())
  }

  private fun classpathSchema(resource: String): JsonNode {
    val stream = QaReceiptSchemaValidator::class.java.classLoader.getResourceAsStream(resource)
    assertNotNull(stream, "The QA schema is missing from the classpath at '$resource'.")
    return YAMLMapper().readTree(stream.use { it.readBytes().toString(Charsets.UTF_8) })
  }
}
