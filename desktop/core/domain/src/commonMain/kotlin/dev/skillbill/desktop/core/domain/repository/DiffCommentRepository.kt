package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import kotlinx.coroutines.flow.Flow

/** Reads and updates the local review threads for a change baseline. */
interface DiffCommentRepository {
  fun observe(baseline: ChangeBaseline): Flow<DiffCommentsState>
  suspend fun reply(threadId: String, body: String): Outcome<Unit, CommentRejection>
  suspend fun setState(threadId: String, state: CommentState): Outcome<Unit, CommentRejection>
  suspend fun deleteComment(commentId: String): Outcome<Unit, CommentRejection>
}
