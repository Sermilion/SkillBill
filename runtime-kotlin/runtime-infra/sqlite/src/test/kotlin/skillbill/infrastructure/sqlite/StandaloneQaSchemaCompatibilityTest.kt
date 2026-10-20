package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.migration.DatabaseMigrations
import skillbill.infrastructure.sqlite.core.schema.DatabaseRuntime
import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandaloneQaSchemaCompatibilityTest {
  @Test
  fun `schema 59 migrates to the desktop readable schema without losing standalone work`() {
    val dbPath = schema59WithStandaloneWork()

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).use { database ->
      database.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT invocation_id, qa_outcome FROM standalone_phase_status").use { rows ->
          assertTrue(rows.next())
          assertEquals("retained-invocation", rows.getString("invocation_id"))
          assertNull(rows.getString("qa_outcome"))
        }
        statement.executeQuery("PRAGMA user_version").use { rows ->
          assertTrue(rows.next())
          assertEquals(62, rows.getInt(1))
        }
      }
    }
  }

  @Test
  fun `an already upgraded QA database stays readable and retains its recorded outcome`() {
    val dbPath = schema59WithStandaloneWork()
    DatabaseRuntime.establishSchemaReadiness(dbPath, SqliteTestDiagnostics)
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.executeUpdate("UPDATE standalone_phase_status SET qa_outcome = 'findings'")
      }
    }

    DatabaseRuntime.migrateExistingDatabase(dbPath, SqliteTestDiagnostics)

    DatabaseRuntime.openExistingReadDbAt(dbPath, SqliteTestDiagnostics).use { database ->
      database.connection.createStatement().use { statement ->
        statement.executeQuery("SELECT invocation_id, qa_outcome FROM standalone_phase_status").use { rows ->
          assertTrue(rows.next())
          assertEquals("retained-invocation", rows.getString("invocation_id"))
          assertEquals("findings", rows.getString("qa_outcome"))
        }
      }
    }
  }

  private fun schema59WithStandaloneWork(): Path {
    val dbPath = Files.createTempDirectory("skillbill-schema-60-compatibility").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      DatabaseSchema.createBaseSchema(connection)
      DatabaseMigrations.apply(
        connection,
        SqliteTestDiagnostics,
        migrationSet = DatabaseMigrations.migrations.filter { it.version <= 59 },
      )
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO standalone_phase_status (execution_id, repository_identity, branch_correlation, " +
            "invocation_id, phase_id, status_store_id, run_sequence, status_revision, lifecycle_state, " +
            "current_step, started_at, updated_at, lease_owner, lease_generation, lease_expires_at) VALUES " +
            "('retained-execution', 'repo', 'main', 'retained-invocation', 'validation', 'store', '4', '3', " +
            "'active', 'validate', '2026-10-18T00:00:00Z', '2026-10-18T00:00:00Z', 'owner', 2, " +
            "'2026-10-19T00:00:00Z')",
        )
      }
    }
    return dbPath
  }
}
