package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.workflow.featuretask.StandalonePhaseStatusStore
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class StandaloneQaChecklistMigrationTest {
  @Test
  fun `migration 97 makes a checklist-only update move the phases signal on a version 96 database`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-qa-checklist").resolve("legacy.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        listOf("insert", "update", "delete").forEach { suffix ->
          statement.execute("DROP TRIGGER desktop_change_standalone_phase_status_$suffix")
        }
        statement.execute("ALTER TABLE standalone_phase_status DROP COLUMN qa_checklist")
        statement.execute(
          "CREATE TRIGGER desktop_change_standalone_phase_status_update AFTER UPDATE ON standalone_phase_status " +
            "WHEN OLD.lifecycle_state IS NOT NEW.lifecycle_state BEGIN " +
            "UPDATE desktop_change_revisions SET revision = revision + 1 WHERE topic = 'phases'; END",
        )
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-standalone-qa-checklist'")
        statement.execute("PRAGMA user_version = 96")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      StandalonePhaseStatusStore(connection, Clock.systemUTC(), SqliteTestDiagnostics, false).register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = "qa-signal", phaseId = "qa", executionId = "execution-qa", lifecycleState = "active",
          currentStep = "qa", startedAt = Instant.EPOCH, leaseOwner = "owner", leaseGeneration = 1,
          leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
      val revisionSql = "SELECT revision FROM desktop_change_revisions WHERE topic = 'phases'"
      val before = scalarInt(connection, revisionSql)
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "DELETE FROM desktop_change_items WHERE work_id = 'qa-signal'",
        )
        statement.executeUpdate("UPDATE standalone_phase_status SET qa_checklist = '{}'")
      }

      assertEquals(before + 1, scalarInt(connection, revisionSql))
      assertEquals(
        1,
        scalarInt(connection, "SELECT count(*) FROM desktop_change_items WHERE work_id = 'qa-signal'"),
      )
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
}
