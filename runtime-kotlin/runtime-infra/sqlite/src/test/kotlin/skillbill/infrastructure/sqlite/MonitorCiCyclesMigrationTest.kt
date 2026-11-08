package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.infrastructure.sqlite.core.migration.area.recordMonitorCiCycles
import skillbill.infrastructure.sqlite.workflow.MonitorCiCycleStore
import skillbill.infrastructure.sqlite.workflow.featuretask.AgentActivityStampStore
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MonitorCiCyclesMigrationTest {
  @Test
  fun `migration 116 adds the cycle table to a version 115 database idempotently and signals phases`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-monitor-ci-cycles").resolve("legacy.db")
    val stamp =
      AgentActivityStamp(recordedAt = Instant.parse("2026-11-08T10:00:00Z"), label = AgentActivityLabel.entries.first())
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      AgentActivityStampStore(connection).record(WORKFLOW, stamp)
      connection.createStatement().use { statement ->
        listOf("insert", "update", "delete").forEach { suffix ->
          statement.execute("DROP TRIGGER IF EXISTS desktop_change_monitor_ci_cycles_$suffix")
        }
        statement.execute("DROP TABLE monitor_ci_cycles")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-monitor-ci-cycles'")
        statement.execute("PRAGMA user_version = 115")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertEquals(stamp, AgentActivityStampStore(connection).read(WORKFLOW))
      recordMonitorCiCycles(connection)
      val before = phasesRevision(connection)
      val at = Instant.parse("2026-11-08T10:05:00Z")
      MonitorCiCycleStore(connection).upsert(
        WORKFLOW,
        MonitorCiCycleRecord(cycle = 1, phaseId = "monitor", startedAt = at, observedAt = at),
      )
      assertTrue(phasesRevision(connection) > before)
      assertEquals(1, scalarInt(connection, "SELECT count(*) FROM monitor_ci_cycles"))
    }
  }

  private fun phasesRevision(connection: Connection): Int =
    scalarInt(
      connection,
      "SELECT revision FROM ${DesktopReadSchema.REVISIONS_TABLE} WHERE topic = '${DesktopReadSchema.PHASES_TOPIC}'",
    )

  private fun scalarInt(
    connection: Connection,
    sql: String,
  ): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery(sql).use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  private companion object {
    const val WORKFLOW = "wftr-monitor-cycles"
  }
}
