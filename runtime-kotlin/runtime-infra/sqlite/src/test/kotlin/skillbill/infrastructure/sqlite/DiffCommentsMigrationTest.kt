package skillbill.infrastructure.sqlite

import skillbill.contracts.persistence.DesktopReadSchema
import java.nio.file.Files
import java.sql.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffCommentsMigrationTest {
  @Test
  fun `migration creates comment tables and signals thread inserts`() {
    val dbPath = Files.createTempDirectory("diff-comments-migration").resolve("db.sqlite")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      assertEquals(DesktopReadSchema.VERSION, scalar(connection, "PRAGMA user_version"))
      assertEquals(
        2,
        scalar(
          connection,
          """
          SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN ('diff_comment_threads',
          'diff_comments')
          """.trimIndent(),
        ),
      )
      assertEquals(
        1,
        scalar(
          connection,
          """
          SELECT count(*) FROM sqlite_master WHERE type = 'index' AND name =
          'idx_diff_comment_threads_source_ref'
          """.trimIndent(),
        ),
      )
      val before = scalar(connection, "SELECT revision FROM desktop_change_revisions WHERE topic = 'diff_comments'")
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          """
          INSERT INTO diff_comment_threads (thread_id, repository_identity, head_branch,
          anchor_commit_sha, state, created_at, updated_at) VALUES ('t1', 'repo', 'main', 'sha', 'open',
          '2026-11-03T00:00:00Z', '2026-11-03T00:00:00Z')
          """.trimIndent(),
        )
      }
      assertEquals(
        before + 1,
        scalar(
          connection,
          """
          SELECT revision FROM desktop_change_revisions WHERE topic = 'diff_comments'
          """.trimIndent(),
        ),
      )
    }
  }

  private fun scalar(
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
