package skillbill.ports.review.diffcomment

import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.time.Instant

/** Persists diff comment threads and their ordered comments within the caller's unit of work. */
interface DiffCommentStore : DiffCommentReader {
  /** Inserts a thread with its first comment, or returns the existing source-ref thread. */
  fun insertThread(thread: DiffCommentThread): DiffCommentThread

  fun insertComment(comment: DiffComment)

  fun updateCommentBody(
    commentId: String,
    body: String,
    editedAt: Instant,
  ): Boolean

  fun deleteComment(commentId: String): Boolean

  fun deleteThread(threadId: String): Boolean

  fun updateThreadState(
    threadId: String,
    state: DiffCommentThreadState,
    updatedAt: Instant,
  ): Boolean

  fun openPendingThreads(
    repositoryIdentity: String,
    headBranch: String,
    updatedAt: Instant,
  ): Int

  fun deletePendingThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): Int
}

/** Reads diff comment threads with their comments ordered by position. */
interface DiffCommentReader {
  fun findThread(threadId: String): DiffCommentThread?

  fun findThreadBySourceRef(
    repositoryIdentity: String,
    headBranch: String,
    sourceRef: String,
  ): DiffCommentThread?

  fun findThreadByCommentId(commentId: String): DiffCommentThread?

  fun listThreads(
    repositoryIdentity: String,
    headBranch: String,
  ): List<DiffCommentThread>
}
