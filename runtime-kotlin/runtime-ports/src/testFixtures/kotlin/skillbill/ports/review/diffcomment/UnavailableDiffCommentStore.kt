package skillbill.ports.review.diffcomment

import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.time.Instant

object UnavailableDiffCommentStore : DiffCommentStore {
  private fun unavailable(): Nothing = error("Diff comment store is unavailable in this fixture.")

  override fun insertThread(thread: DiffCommentThread): DiffCommentThread = unavailable()

  override fun findThread(threadId: String): DiffCommentThread? = unavailable()

  override fun findThreadBySourceRef(
    repositoryIdentity: String,
    headBranch: String,
    sourceRef: String,
  ): DiffCommentThread? = unavailable()

  override fun findThreadByCommentId(commentId: String): DiffCommentThread? = unavailable()

  override fun listThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): List<DiffCommentThread> = unavailable()

  override fun insertComment(comment: DiffComment): Unit = unavailable()

  override fun updateCommentBody(
    commentId: String,
    body: String,
    editedAt: Instant,
  ): Boolean = unavailable()

  override fun deleteComment(commentId: String): Boolean = unavailable()

  override fun deleteThread(threadId: String): Boolean = unavailable()

  override fun updateThreadState(
    threadId: String,
    state: DiffCommentThreadState,
    updatedAt: Instant,
  ): Boolean = unavailable()

  override fun openPendingThreads(
    repositoryIdentity: String,
    headBranch: String,
    updatedAt: Instant,
  ): Int = unavailable()

  override fun deletePendingThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): Int = unavailable()
}
