package skillbill.infrastructure.contracts.workflow.featuretask

import skillbill.contracts.JsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimePhaseOutputMigrationTest {
  @Test
  fun `converts source-valid output and preserves its evidence`() {
    val source = output(version = "0.6", status = "completed", producedOutputs = mapOf("value" to "plan text"))

    val migrated =
      assertIs<FeatureTaskRuntimePhaseOutputMigration.Migrated>(
        FeatureTaskRuntimePhaseOutputMigrator.migrate(JsonCodec.valueToJsonString(source)),
      )

    assertEquals("0.6", migrated.sourceVersion)
    assertEquals("0.7", migrated.targetVersion)
    val payload =
      requireNotNull(JsonCodec.parseObjectOrNull(migrated.payload))
        .let(JsonCodec::jsonElementToValue).let(JsonCodec::anyToStringAnyMap)!!
    assertEquals("plan text", (payload["produced_outputs"] as Map<*, *>)["value"])
    assertEquals("0.7", payload["contract_version"])
  }

  @Test
  fun `refuses source-valid output that lacks target failure evidence`() {
    val source = output(version = "0.6", status = "blocked", producedOutputs = mapOf("value" to "blocked"))

    assertIs<FeatureTaskRuntimePhaseOutputMigration.NonConvertible>(
      FeatureTaskRuntimePhaseOutputMigrator.migrate(JsonCodec.valueToJsonString(source)),
    )
  }

  private fun output(
    version: String,
    status: String,
    producedOutputs: Map<String, Any?>,
  ): Map<String, Any?> =
    mapOf(
      "contract_version" to version,
      "phase_id" to "preplan",
      "status" to status,
      "summary" to "planning result",
      "produced_outputs" to producedOutputs,
    )
}
