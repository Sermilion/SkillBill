package skillbill.infrastructure.contracts.workflow.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FeatureTaskRuntimePhaseOutputMigrationTest {
  @Test
  fun `converts source-valid output and preserves its evidence`() {
    val source = output(version = "0.6", status = "completed", producedOutputs = mapOf("value" to "plan text"))

    val migrated =
      assertIs<FeatureTaskRuntimePhaseOutputMigration.Migrated>(
        FeatureTaskRuntimePhaseOutputMigrator.migrate(source),
      )

    assertEquals("0.6", migrated.sourceVersion)
    assertEquals("0.7", migrated.targetVersion)
    assertEquals("plan text", (migrated.payload["produced_outputs"] as Map<*, *>)["value"])
    assertEquals("0.7", migrated.payload["contract_version"])
  }

  @Test
  fun `refuses source-valid output that lacks target failure evidence`() {
    val source = output(version = "0.6", status = "blocked", producedOutputs = mapOf("value" to "blocked"))

    assertIs<FeatureTaskRuntimePhaseOutputMigration.NonConvertible>(
      FeatureTaskRuntimePhaseOutputMigrator.migrate(source),
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
