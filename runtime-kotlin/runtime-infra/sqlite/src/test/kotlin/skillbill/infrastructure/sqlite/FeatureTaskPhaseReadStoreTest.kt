package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.workflow.featuretask.FeatureTaskPhaseReadStore
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskPhaseReadStoreTest {
  @Test
  fun `status artifact selection and ledger pages leave growing unrelated artifacts in SQLite`() {
    val path = Files.createTempDirectory("phase-status-pages").resolve("metrics.db")
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER
    val recordsFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS
    val ledger =
      (0..999).map { sequence ->
        FeatureTaskRuntimePhaseLedgerEntry(
          FeatureTaskRuntimePhaseLedgerAction.START,
          sequence,
          Instant.EPOCH.plusSeconds(sequence.toLong()),
          "implement",
          1,
        ).asWorkflowArtifactEntry()
      }
    val artifacts =
      JsonCodec.mapToJsonString(
        mapOf(family.entry(ledger), recordsFamily.entry(emptyMap<String, Any?>())),
      )
    DatabaseRuntime.ensureDatabase(path).use { connection ->
      connection.prepareStatement(
        "INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, workflow_status, artifacts_json) " +
          "VALUES ('child', 'runtime', '1.0.0', 'running', ?)",
      ).use { statement ->
        statement.setString(1, artifacts)
        statement.executeUpdate()
      }
      val store = FeatureTaskPhaseReadStore(connection)
      val selected = assertNotNull(store.artifacts("child", setOf(recordsFamily)))
      assertEquals(setOf(recordsFamily.label()), selected.toMap().keys)
      assertNull(family.value(selected))
      val first = assertNotNull(store.ledgerPage("child", 0, 10))
      assertEquals((0..9).toList(), first.entries.map { it.sequenceNumber })
      assertEquals(10, first.nextOffset)
      val tail = assertNotNull(store.ledgerPage("child", 990, 10))
      assertEquals((990..999).toList(), tail.entries.map { it.sequenceNumber })
      assertNull(tail.nextOffset)
      assertTrue(assertNotNull(store.ledgerPage("child", 1000, 10)).entries.isEmpty())
      assertNull(store.ledgerPage("missing", 0, 10))
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT artifacts_json FROM feature_task_workflows WHERE workflow_id = 'child'").use {
            rows ->
          assertTrue(rows.next())
          assertEquals(artifacts, rows.getString(1))
        }
      }
    }
  }
}
