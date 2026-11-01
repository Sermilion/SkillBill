package skillbill.infrastructure.sqlite

import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewFindingActionSignalTest {
  @Test
  fun `review actions and feedback refresh the exact finished review detail`() {
    establishTemporarySchemaReadiness("review-action-signals").use { fixture ->
      DatabaseRuntime.ensureDatabase(fixture.dbPath).use { connection ->
        seedReview(connection)
        val before = revision(connection)
        connection.createStatement().use { statement ->
          statement.executeUpdate(
            "INSERT INTO review_finding_actions (review_run_id, kind, started_at) " +
              "VALUES ('review-run', 'verify', '2026-11-01T09:00:00Z')",
          )
          assertEquals(before + 1, revision(connection))
          statement.executeUpdate(
            "UPDATE review_finding_actions SET finished_at = '2026-11-01T09:01:00Z', completed = 1, open = 1",
          )
          assertEquals(before + 2, revision(connection))
          statement.executeUpdate(
            "INSERT INTO feedback_events (review_run_id, finding_id, event_type) " +
              "VALUES ('review-run', 'F-1', 'fix_applied')",
          )
          assertEquals(before + 3, revision(connection))
          statement.executeQuery(
            "SELECT work_id FROM desktop_change_items WHERE topic = 'phases' AND revision > $before",
          ).use { rows ->
            var count = 0
            while (rows.next()) {
              assertEquals("review-invocation", rows.getString(1))
              count++
            }
            assertTrue(count == 3)
          }
        }
      }
    }
  }

  private fun revision(connection: Connection): Int =
    connection.createStatement().use { statement ->
      statement.executeQuery("SELECT revision FROM desktop_change_revisions WHERE topic = 'phases'").use { rows ->
        rows.next()
        rows.getInt(1)
      }
    }

  private fun seedReview(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.executeUpdate("INSERT INTO review_runs (review_run_id, raw_text) VALUES ('review-run', 'review')")
      statement.executeUpdate(
        "INSERT INTO findings (review_run_id, finding_id, severity, confidence, location, description, finding_text) " +
          "VALUES ('review-run', 'F-1', 'Major', 'high', 'a.kt:1', 'broken', 'broken')",
      )
      statement.executeUpdate(
        """
        INSERT INTO standalone_phase_status (
          execution_id, repository_identity, branch_correlation, invocation_id, phase_id, status_store_id,
          run_sequence, status_revision, lifecycle_state, current_step, started_at, updated_at,
          lease_owner, lease_generation, lease_expires_at, review_run_id
        ) VALUES ('execution', 'repo', 'main', 'review-invocation', 'review', 'store', '1', '1',
          'terminal', 'present_findings', '2026-11-01', '2026-11-01', 'owner', 1, '2026-11-01', 'review-run')
        """.trimIndent(),
      )
    }
  }
}
