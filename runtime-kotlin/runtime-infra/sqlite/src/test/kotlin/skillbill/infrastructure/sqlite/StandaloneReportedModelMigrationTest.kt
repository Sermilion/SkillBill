package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.core.migration.area.idestatus.recordStandaloneReportedModel
import skillbill.infrastructure.sqlite.workflow.featuretask.StandalonePhaseStatusStore
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StandaloneReportedModelMigrationTest {
  @Test
  fun `previous records survive interrupted migration and repeated installation without inventing a model`() {
    val root = Files.createTempDirectory("reported-model-migration")
    val path = root.resolve("runtime.db")
    val clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    try {
      DatabaseRuntime.ensureDatabase(path).use { connection ->
        StandalonePhaseStatusStore(connection, clock, SqliteTestDiagnostics, false).register(
          StandalonePhaseStatusRegistration(
            "repo", "main", null, null, "monitor-run", "monitor", "monitor-execution", "active", "monitor",
            Instant.EPOCH, "owner", 1, Instant.EPOCH.plusSeconds(3600),
          ),
        )
        connection.createStatement().use { statement ->
          listOf("insert", "update", "delete", "update_activity").forEach { suffix ->
            statement.execute("DROP TRIGGER IF EXISTS desktop_change_standalone_phase_status_$suffix")
          }
          statement.execute("ALTER TABLE standalone_phase_status DROP COLUMN reported_model")
          statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-standalone-reported-model'")
          statement.execute("PRAGMA user_version = 119")
        }
        connection.autoCommit = false
        recordStandaloneReportedModel(connection)
        connection.rollback()
        connection.autoCommit = true
      }
      DatabaseRuntime.ensureDatabase(path).use { connection ->
        val store = StandalonePhaseStatusStore(connection, clock, SqliteTestDiagnostics, false)
        val old = assertNotNull(store.readByInvocation("monitor-run"))
        assertEquals("monitor-execution", old.executionId)
        assertEquals("active", old.lifecycleState)
        assertNull(old.reportedModel)
        store.update(
          StandalonePhaseStatusUpdate(
            executionId = old.executionId, expectedRevision = old.statusRevision,
            leaseOwner = old.leaseOwner, leaseGeneration = old.leaseGeneration,
            lifecycleState = "active", currentStep = "monitor_fix", currentActivity = null, updatedAt = Instant.EPOCH,
            reportedModel = "provider-model", replaceReportedModel = true,
          ),
        )
        recordStandaloneReportedModel(connection)
      }
      repeat(2) {
        DatabaseRuntime.ensureDatabase(path).use { connection ->
          val store = StandalonePhaseStatusStore(connection, clock, SqliteTestDiagnostics, false)
          val current = assertNotNull(store.readByInvocation("monitor-run"))
          assertEquals("provider-model", current.reportedModel)
          assertEquals("monitor-execution", current.executionId)
          connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
              rows.next()
              assertEquals(DesktopReadSchema.VERSION, rows.getInt(1))
            }
          }
        }
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
