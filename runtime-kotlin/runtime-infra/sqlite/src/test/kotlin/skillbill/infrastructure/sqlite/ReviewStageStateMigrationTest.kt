package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.core.schema.DatabaseSchema
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewStageStateMigrationTest {
  @Test
  fun `startup ensures create stage tables on a pre-change store and a second pass is a no-op`() {
    val dbPath = Files.createTempDirectory("review-stage-prechange").resolve("metrics.db")
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { connection ->
      connection.createStatement().use { statement ->
        statement.execute(
          """
          CREATE TABLE schema_migrations (
            name TEXT PRIMARY KEY,
            version INTEGER NOT NULL,
            applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
          )
          """.trimIndent(),
        )
        statement.execute(
          """
          CREATE TABLE review_runs (
            review_run_id TEXT PRIMARY KEY,
            raw_text TEXT NOT NULL DEFAULT ''
          )
          """.trimIndent(),
        )
      }
      connection.prepareStatement("INSERT INTO schema_migrations (version, name) VALUES (?, ?)").use { statement ->
        DatabaseMigrations.migrations.filter { it.version < 30 }.forEach { migration ->
          statement.setInt(1, migration.version)
          statement.setString(2, migration.name)
          statement.executeUpdate()
        }
      }
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = sqliteObjects(connection, "table")
      val indexes = sqliteObjects(connection, "index")
      assertTrue("review_run_finding_verdicts" in DatabaseSchema.tableNames)
      assertTrue("review_run_stage_boundaries" in DatabaseSchema.tableNames)
      assertTrue("review_run_spec_projections" in DatabaseSchema.tableNames)
      assertTrue("review_run_pass_claims" in DatabaseSchema.tableNames)
      assertTrue("review_run_finding_verdicts" in tables)
      assertTrue("review_run_stage_boundaries" in tables)
      assertTrue("review_run_spec_projections" in tables)
      assertTrue("review_run_pass_claims" in tables)
      assertTrue("idx_review_run_finding_verdicts_run" in DatabaseSchema.indexNames)
      assertTrue("idx_review_run_stage_boundaries_run" in DatabaseSchema.indexNames)
      assertTrue("idx_review_run_finding_verdicts_run" in indexes)
      assertTrue("idx_review_run_stage_boundaries_run" in indexes)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val tables = sqliteObjects(connection, "table")
      assertEquals(1, tables.count { it == "review_run_finding_verdicts" })
      assertEquals(1, tables.count { it == "review_run_stage_boundaries" })
      assertEquals(1, tables.count { it == "review_run_spec_projections" })
      assertEquals(1, tables.count { it == "review_run_pass_claims" })
    }
  }

  private fun sqliteObjects(
    connection: Connection,
    type: String,
  ): Set<String> =
    connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = ?").use { statement ->
      statement.setString(1, type)
      statement.executeQuery().use { resultSet ->
        buildSet {
          while (resultSet.next()) add(resultSet.getString("name"))
        }
      }
    }
}
