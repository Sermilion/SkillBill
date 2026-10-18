package skillbill.infrastructure.contracts.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.qa.QA_DEVICE_LEASE_CONTRACT_VERSION
import skillbill.contracts.qa.QA_RECEIPT_CONTRACT_VERSION
import skillbill.infrastructure.contracts.locator.QaDeviceLeaseSchemaPaths
import skillbill.infrastructure.contracts.locator.QaReceiptSchemaPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class QaSchemaContractVersionTest {
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
