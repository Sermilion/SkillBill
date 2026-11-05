package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import kotlinx.coroutines.flow.Flow

/** Reads and updates the local review threads for a change baseline. */
interface DiffCommentRepository {
  fun observe(baseline: ChangeBaseline): Flow<DiffCommentsState>
  suspend fun reply(threadId: String, body: String): Outcome<Unit, CommentRejection>
  suspend fun setState(threadId: String, state: CommentState): Outcome<Unit, CommentRejection>
  suspend fun deleteComment(commentId: String): Outcome<Unit, CommentRejection>
  /** Adds a pending draft to the displayed branch. */
  suspend fun addPending(baseline: ChangeBaseline, branch: String, path: String, side: CommentSide?, line: Int?, body: String): Outcome<Unit, CommentRejection>
  /** Updates a user comment in a pending thread. */
  suspend fun editComment(commentId: String, body: String): Outcome<Unit, CommentRejection>
  /** Publishes every pending draft for the displayed branch. */
  suspend fun submitReview(baseline: ChangeBaseline, branch: String): Outcome<Int, CommentRejection>
  /** Deletes every pending draft for the displayed branch. */
  suspend fun discardPending(baseline: ChangeBaseline, branch: String): Outcome<Int, CommentRejection>
}
