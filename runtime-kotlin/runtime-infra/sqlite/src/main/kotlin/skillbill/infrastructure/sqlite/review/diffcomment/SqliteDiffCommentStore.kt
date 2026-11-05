package skillbill.infrastructure.sqlite.review.diffcomment

import skillbill.contracts.review.ReviewFinishedTelemetryPayloadKeys
import skillbill.infrastructure.sqlite.core.ops.bindAll
import skillbill.ports.review.diffcomment.DiffCommentStore
import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

internal class SqliteDiffCommentStore(private val connection: Connection) : DiffCommentStore {
  override fun insertThread(thread: DiffCommentThread): DiffCommentThread {
    val sourceRef = thread.sourceRef
    if (sourceRef != null) {
      val existing = findThreadBySourceRef(thread.repositoryIdentity, thread.headBranch, sourceRef)
      if (existing != null) return existing
    }
    require(thread.comments.size == 1)
    connection.prepareStatement(
      """
      INSERT INTO diff_comment_threads (
        thread_id, repository_identity, head_branch, path, side, line, anchor_commit_sha, anchor_line_text,
        state, source_ref, source_label, severity, finding_ref, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bindAll(
        thread.id, thread.repositoryIdentity, thread.headBranch, thread.path, thread.side?.wireValue, thread.line,
        thread.anchorCommitSha, thread.anchorLineText, thread.state.wireValue, thread.sourceRef, thread.sourceLabel,
        thread.severity, thread.findingRef, thread.createdAt.toString(), thread.updatedAt.toString(),
      )
      statement.executeUpdate()
    }
    insertComment(thread.comments.single())
    return thread
  }

  override fun findThread(threadId: String): DiffCommentThread? =
    one("SELECT * FROM diff_comment_threads WHERE thread_id = ?", threadId)

  override fun findThreadBySourceRef(
    repositoryIdentity: String,
    headBranch: String,
    sourceRef: String,
  ): DiffCommentThread? =
    one(
      "SELECT * FROM diff_comment_threads WHERE repository_identity = ? AND head_branch = ? AND source_ref = ?",
      repositoryIdentity,
      headBranch,
      sourceRef,
    )

  override fun findThreadByCommentId(commentId: String): DiffCommentThread? =
    one(
      "SELECT t.* FROM diff_comment_threads t JOIN diff_comments c ON c.thread_id = t.thread_id WHERE c.comment_id = ?",
      commentId,
    )

  override fun listThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): List<DiffCommentThread> =
    query(
      "SELECT * FROM diff_comment_threads WHERE repository_identity = ? AND head_branch = ? ORDER BY created_at",
      repositoryIdentity,
      headBranch,
    )

  override fun insertComment(comment: DiffComment) {
    connection.prepareStatement("INSERT INTO diff_comments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
      statement.bindAll(
        comment.id, comment.threadId, comment.position, comment.author.wireValue, comment.agentId, comment.body,
        comment.outcome?.wireValue, comment.createdAt.toString(), comment.editedAt?.toString(),
      )
      statement.executeUpdate()
    }
  }

  override fun updateCommentBody(
    commentId: String,
    body: String,
    editedAt: Instant,
  ): Boolean =
    update(
      "UPDATE diff_comments SET body = ?, edited_at = ? WHERE comment_id = ?",
      body,
      editedAt.toString(),
      commentId,
    )

  override fun deleteComment(commentId: String): Boolean =
    update("DELETE FROM diff_comments WHERE comment_id = ?", commentId)

  override fun deleteThread(threadId: String): Boolean {
    connection.prepareStatement("DELETE FROM diff_comments WHERE thread_id = ?").use { statement ->
      statement.bindAll(threadId)
      statement.executeUpdate()
    }
    return update("DELETE FROM diff_comment_threads WHERE thread_id = ?", threadId)
  }

  override fun updateThreadState(
    threadId: String,
    state: DiffCommentThreadState,
    updatedAt: Instant,
  ): Boolean =
    update(
      "UPDATE diff_comment_threads SET state = ?, updated_at = ? WHERE thread_id = ?",
      state.wireValue,
      updatedAt.toString(),
      threadId,
    )

  override fun openPendingThreads(
    repositoryIdentity: String,
    headBranch: String,
    updatedAt: Instant,
  ): Int =
    updateCount(
      "UPDATE diff_comment_threads SET state = ?, updated_at = ? " +
        "WHERE repository_identity = ? AND head_branch = ? AND state = ?",
      DiffCommentThreadState.OPEN.wireValue,
      updatedAt.toString(),
      repositoryIdentity,
      headBranch,
      DiffCommentThreadState.PENDING.wireValue,
    )

  override fun deletePendingThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): Int {
    connection.prepareStatement(
      "DELETE FROM diff_comments WHERE thread_id IN (SELECT thread_id FROM diff_comment_threads " +
        "WHERE repository_identity = ? AND head_branch = ? AND state = ?)",
    ).use { statement ->
      statement.bindAll(repositoryIdentity, headBranch, DiffCommentThreadState.PENDING.wireValue)
      statement.executeUpdate()
    }
    return updateCount(
      "DELETE FROM diff_comment_threads WHERE repository_identity = ? AND head_branch = ? AND state = ?",
      repositoryIdentity,
      headBranch,
      DiffCommentThreadState.PENDING.wireValue,
    )
  }

  private fun one(
    sql: String,
    vararg args: Any?,
  ): DiffCommentThread? = query(sql, *args).firstOrNull()

  private fun query(
    sql: String,
    vararg args: Any?,
  ): List<DiffCommentThread> {
    val threads =
      connection.prepareStatement(sql).use { statement ->
        statement.bindAll(*args)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.thread()) } }
      }
    if (threads.isEmpty()) return threads
    val ids = threads.map { it.id }
    val comments =
      connection.prepareStatement(
        "SELECT * FROM diff_comments WHERE thread_id IN (${ids.joinToString { "?" }}) ORDER BY thread_id, position",
      ).use { statement ->
        ids.forEachIndexed { index, id -> statement.setString(index + 1, id) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.comment()) } }
      }.groupBy { it.threadId }
    return threads.map { it.copy(comments = comments[it.id].orEmpty()) }
  }

  private fun ResultSet.comment(): DiffComment =
    DiffComment(
      id = getString("comment_id"),
      threadId = getString("thread_id"),
      position = getInt("position"),
      author = checkNotNull(DiffCommentAuthor.fromWire(getString("author"))),
      agentId = getString("agent_id"),
      body = getString("body"),
      outcome = getString("outcome")?.let { checkNotNull(DiffCommentOutcome.fromWire(it)) },
      createdAt = Instant.parse(getString("created_at")),
      editedAt = getString("edited_at")?.let(Instant::parse),
    )

  private fun ResultSet.thread(): DiffCommentThread =
    DiffCommentThread(
      id = getString("thread_id"),
      repositoryIdentity = getString("repository_identity"),
      headBranch = getString("head_branch"),
      path = getString("path"),
      side = getString("side")?.let { checkNotNull(DiffCommentSide.fromWire(it)) },
      line = getInt("line").let { if (wasNull()) null else it },
      anchorCommitSha = getString("anchor_commit_sha"),
      anchorLineText = getString("anchor_line_text"),
      state = checkNotNull(DiffCommentThreadState.fromWire(getString("state"))),
      sourceRef = getString("source_ref"),
      sourceLabel = getString("source_label"),
      severity = getString(ReviewFinishedTelemetryPayloadKeys.SEVERITY),
      findingRef = getString("finding_ref"),
      createdAt = Instant.parse(getString("created_at")),
      updatedAt = Instant.parse(getString("updated_at")),
      comments = emptyList(),
      outdated = false,
    )

  private fun update(
    sql: String,
    vararg args: Any?,
  ): Boolean = updateCount(sql, *args) > 0

  private fun updateCount(
    sql: String,
    vararg args: Any?,
  ): Int =
    connection.prepareStatement(sql).use { statement ->
      statement.bindAll(*args)
      statement.executeUpdate()
    }
}
