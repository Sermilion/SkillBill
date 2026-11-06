package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.workflow.featuretask.StandalonePhaseStatusStore
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValidationCheckEventsMigrationTest {
  @Test
  fun `migration 103 keeps old rows unset, enforces its enums and signals phases for each event`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-validation-checks").resolve("legacy.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      store(connection).register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = RUN, phaseId = "validation", executionId = "execution-validation",
          lifecycleState = "active", currentStep = "validation", startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
      connection.createStatement().use { statement ->
        listOf("insert", "update", "delete", "update_activity").forEach { suffix ->
          statement.execute("DROP TRIGGER IF EXISTS desktop_change_standalone_phase_status_$suffix")
          statement.execute("DROP TRIGGER IF EXISTS desktop_change_validation_check_events_$suffix")
        }
        statement.execute("DROP TABLE validation_check_events")
        statement.execute("ALTER TABLE standalone_phase_status DROP COLUMN live_checks")
        statement.execute("ALTER TABLE standalone_phase_status DROP COLUMN validation_outcome")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-validation-check-events'")
        statement.execute("PRAGMA user_version = 102")
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      val legacy = checkNotNull(store(connection).readByInvocation(RUN))
      assertNull(legacy.liveChecks)
      assertNull(legacy.validationOutcome)
      connection.createStatement().use { statement ->
        assertFailsWith<SQLException> { statement.executeUpdate(insertEvent(1, "bogus")) }
        assertFailsWith<SQLException> {
          statement.executeUpdate("UPDATE standalone_phase_status SET live_checks = 'bogus'")
        }
        statement.executeUpdate("DELETE FROM desktop_change_items WHERE work_id = '$RUN'")
        statement.executeUpdate(insertEvent(1, "planned"))
      }

      assertEquals(
        1,
        scalarInt(connection, "SELECT count(*) FROM desktop_change_items WHERE work_id = '$RUN'"),
      )
    }
  }

  private fun store(connection: Connection) =
    StandalonePhaseStatusStore(connection, Clock.systemUTC(), SqliteTestDiagnostics, false)

  private fun insertEvent(
    sequence: Int,
    event: String,
  ): String =
    "INSERT INTO validation_check_events (invocation_id, sequence, check_id, event, name, command, working_dir, " +
      "attempt, recorded_at) VALUES ('$RUN', $sequence, 'unit-tests', '$event', 'Unit tests', './gradlew test', " +
      "'.', 0, '2026-11-06T10:00:00Z')"

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
    const val RUN = "validation-signal"
  }
}
