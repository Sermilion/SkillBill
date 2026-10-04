package skillbill.mcp.telemetry

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class TelemetryEventSchemaCleanupTest {
  @Test
  fun `telemetry-event schema classpath shadow with mismatched id loud-fails`() {
    val mismatchedIdYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "https://malicious.example/shadow-telemetry-event.yaml"
      type: object
      properties:
        contract_version:
          const: "$TELEMETRY_EVENT_CONTRACT_VERSION"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.assertIdentity(YAMLMapper().readTree(mismatchedIdYaml))
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    val reason = error.message.orEmpty()
    assertContains(reason, "https://malicious.example/shadow-telemetry-event.yaml")
    assertContains(reason, TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID)
  }

  @Test
  fun `telemetry-event schema classpath shadow with mismatched contract_version const loud-fails`() {
    val mismatchedConstYaml =
      """
      ${'$'}schema: "https://json-schema.org/draft/2020-12/schema"
      ${'$'}id: "${TelemetryEventSchemaPaths.EXPECTED_SCHEMA_ID}"
      type: object
      properties:
        contract_version:
          const: "9.99"
      """.trimIndent()

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        TelemetryEventSchemaValidator.assertIdentity(YAMLMapper().readTree(mismatchedConstYaml))
      }.also { assertEquals(InstallFailureCode.INVALID_TELEMETRY_EVENT_SCHEMA, it.code) }
    val reason = error.message.orEmpty()
    assertContains(reason, "9.99")
    assertContains(reason, TELEMETRY_EVENT_CONTRACT_VERSION)
  }

  @Test
  fun `telemetry-event canonical schema on disk passes identity assertion`() {
    val schemaPath: Path =
      repoRootFromTest()
        .resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    val yamlText = Files.readString(schemaPath)
    val node = YAMLMapper().readTree(yamlText)

    TelemetryEventSchemaValidator.assertIdentity(node)
  }

  @Test
  fun `telemetry schema omits retired review accounting projections`() {
    val schemaPath = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    val schema = YAMLMapper().readTree(Files.readString(schemaPath))
    val defs = schema.path("\$defs")
    val reviewFinished = defs.path("skillbillReviewFinishedEvent").path("properties")

    assertFalse(defs.has("reviewAccountingUsage"))
    assertFalse(defs.has("boundedReviewAccounting"))
    assertFalse(reviewFinished.has("review_context_accounting"))
    assertFalse(defs.has("featureTaskAuditSettleEvent"))
  }
}
