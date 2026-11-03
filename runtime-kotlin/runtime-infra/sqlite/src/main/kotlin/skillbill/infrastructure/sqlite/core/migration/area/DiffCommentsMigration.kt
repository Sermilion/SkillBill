package skillbill.infrastructure.sqlite.core.migration.area

import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.core.schema.installLaterDesktopChangeTriggers
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.sql.Connection

internal fun recordDiffComments(connection: Connection) {
  val outcomes = DiffCommentOutcome.entries.joinToString { "'${it.wireValue}'" }
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS diff_comment_threads (
        thread_id TEXT PRIMARY KEY,
        repository_identity TEXT NOT NULL,
        head_branch TEXT NOT NULL,
        path TEXT,
        side TEXT CHECK (side IS NULL OR side IN (${DiffCommentSide.entries.joinToString { "'${it.wireValue}'" }})),
        line INTEGER CHECK (line IS NULL OR line >= 1),
        anchor_commit_sha TEXT NOT NULL,
        anchor_line_text TEXT,
        state TEXT NOT NULL CHECK (state IN (${DiffCommentThreadState.entries.joinToString { "'${it.wireValue}'" }})),
        source_ref TEXT,
        source_label TEXT,
        severity TEXT,
        finding_ref TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS diff_comments (
        comment_id TEXT PRIMARY KEY,
        thread_id TEXT NOT NULL REFERENCES diff_comment_threads(thread_id) ON DELETE CASCADE,
        position INTEGER NOT NULL,
        author TEXT NOT NULL CHECK (author IN (${DiffCommentAuthor.entries.joinToString { "'${it.wireValue}'" }})),
        agent_id TEXT,
        body TEXT NOT NULL,
        outcome TEXT CHECK (outcome IS NULL OR outcome IN ($outcomes)),
        created_at TEXT NOT NULL,
        edited_at TEXT
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE INDEX IF NOT EXISTS idx_diff_comment_threads_branch ON diff_comment_threads
      (repository_identity, head_branch)
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE UNIQUE INDEX IF NOT EXISTS idx_diff_comment_threads_source_ref ON diff_comment_threads
      (repository_identity, head_branch, source_ref) WHERE source_ref IS NOT NULL
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE INDEX IF NOT EXISTS idx_diff_comments_thread_position ON diff_comments (thread_id,
      position)
      """.trimIndent(),
    )
    statement.execute(
      """
      INSERT OR IGNORE INTO ${DesktopReadSchema.REVISIONS_TABLE} (topic, revision) VALUES
      ('${DesktopReadSchema.DIFF_COMMENTS_TOPIC}', 0)
      """.trimIndent(),
    )
  }
  installLaterDesktopChangeTriggers(connection, setOf("diff_comment_threads", "diff_comments"))
}
