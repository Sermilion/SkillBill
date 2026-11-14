package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.core.migration.area.addReviewFindingActions
import skillbill.infrastructure.sqlite.idestatus.VerifyCriterionResolutionStore
import skillbill.ports.idestatus.model.VerifyCriterionResolution
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class VerifyCriterionResolutionsMigrationTest {
  @Test
  fun `migration 131 upgrades a version 130 database and signals the verify run on resolutions and finding fixes`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-verify-resolutions").resolve("legacy.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.createStatement().use { statement ->
        listOf("insert", "update", "delete").forEach { suffix ->
          statement.execute("DROP TRIGGER IF EXISTS desktop_change_verify_criterion_resolutions_$suffix")
        }
        statement.execute("DROP TABLE verify_criterion_resolutions")
        statement.executeUpdate("DELETE FROM schema_migrations WHERE name = 'record-verify-criterion-resolutions'")
        statement.execute("PRAGMA user_version = 130")
        statement.executeUpdate(
          "INSERT INTO feature_verify_workflows (workflow_id, session_id) VALUES ('$WORKFLOW', '$SESSION')",
        )
        statement.executeUpdate(
          "INSERT INTO review_runs (review_run_id, review_session_id, raw_text) " +
            "VALUES ('$REVIEW_RUN', '$SESSION', 'x')",
        )
        statement.executeUpdate(
          "INSERT INTO findings (review_run_id, finding_id, severity, confidence, location, description, " +
            "finding_text) VALUES ('$REVIEW_RUN', 'F-001', 'Minor', 'Medium', 'A.kt:5', 'd', 'd')",
        )
      }
      addReviewFindingActions(connection)
    }

    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalarInt(connection, "PRAGMA user_version"))
      val store = VerifyCriterionResolutionStore(connection)
      store.record(WORKFLOW, VerifyCriterionResolution(7, "first", Instant.parse("2026-11-13T15:00:00Z")))
      val replaced = VerifyCriterionResolution(7, "device run passed", Instant.parse("2026-11-13T16:00:00Z"))
      store.record(WORKFLOW, replaced)
      assertEquals(listOf(replaced), store.resolutions(WORKFLOW))
      val afterResolution = latestPhaseChange(connection)
      assertEquals(WORKFLOW, afterResolution.second)

      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO feedback_events (review_run_id, finding_id, event_type) " +
            "VALUES ('$REVIEW_RUN', 'F-001', 'fix_applied')",
        )
      }
      val afterFeedback = latestPhaseChange(connection)
      assertEquals(WORKFLOW, afterFeedback.second)
      assertEquals(true, afterFeedback.first > afterResolution.first)
    }
  }

  private fun latestPhaseChange(connection: Connection): Pair<Int, String> =
    connection.createStatement().use { statement ->
      statement.executeQuery(
        "SELECT revision, work_id FROM ${DesktopReadSchema.CHANGE_ITEMS_TABLE} " +
          "WHERE topic = '${DesktopReadSchema.PHASES_TOPIC}' ORDER BY revision DESC LIMIT 1",
      ).use { rows ->
        rows.next()
        rows.getInt(1) to rows.getString(2)
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

  private companion object {
    const val WORKFLOW = "wfv-verify-resolutions"
    const val SESSION = "fvr-verify-resolutions"
    const val REVIEW_RUN = "rvw-verify-resolutions"
  }
}
