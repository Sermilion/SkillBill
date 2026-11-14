package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import skillbill.infrastructure.sqlite.workflow.featuretask.AgentActivityStampStore
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectCheckConfigMigrationTest {
  @Test
  fun `upgrade adds project config and keeps existing data and populated rows on repeat`() {
    val dbPath = Files.createTempDirectory("project-check-config-migration").resolve("runtime.db")
    val stamp =
      AgentActivityStamp(
        recordedAt = Instant.parse("2026-11-08T10:00:00Z"),
        label = AgentActivityLabel.entries.first(),
      )
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      AgentActivityStampStore(connection).record("wf-preserved", stamp)
      connection.createStatement().use { statement ->
        statement.execute("DROP TABLE project_config")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'add-project-check-config'")
        statement.execute("PRAGMA user_version = ${DesktopReadSchema.VERSION - 1}")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      assertEquals(stamp, AgentActivityStampStore(connection).read("wf-preserved"))
      assertTrue(tableExists(connection, "project_config"))
      connection.prepareStatement(
        "INSERT INTO project_config " +
          "(project_id, contract_version, build_commands) VALUES (?, '0.1', ?) ",
      ).use { statement ->
        statement.setString(1, "p-preserved")
        statement.setString(2, "{\"commands\":[]}")
        statement.executeUpdate()
      }
    }
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT build_commands FROM project_config WHERE project_id = 'p-preserved'")
          .use { rows ->
            assertTrue(rows.next())
            assertEquals("{\"commands\":[]}", rows.getString(1))
          }
      }
    }
  }

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

  private fun tableExists(
    connection: Connection,
    table: String,
  ): Boolean =
    connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { statement ->
      statement.setString(1, table)
      statement.executeQuery().use { rows -> rows.next() }
    }
}
