package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.repository.DiffCommentRepository
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeDiffCommentRepository(
  private val sessions: DatabaseSessionManager,
  private val diagnostics: Diagnostics,
) : DiffCommentRepository {
  override fun observe(baseline: ChangeBaseline): Flow<DiffCommentsState> = sessions.sessionFlow(
    opening = DiffCommentsState.Loading,
    failed = {
      diagnostics.record("desktop_diff_comments_unavailable")
      DiffCommentsState.Unavailable("The database is unavailable.")
    },
  ) { it.diffComments.observe(baseline) }

  override suspend fun reply(threadId: String, body: String): Outcome<Unit, CommentRejection> =
    sessions.sessions.value?.diffComments?.reply(threadId, body) ?: noSession()

  override suspend fun setState(threadId: String, state: CommentState): Outcome<Unit, CommentRejection> =
    sessions.sessions.value?.diffComments?.setState(threadId, state) ?: noSession()

  override suspend fun deleteComment(commentId: String): Outcome<Unit, CommentRejection> =
    sessions.sessions.value?.diffComments?.deleteComment(commentId) ?: noSession()

  private fun noSession(): Outcome<Unit, CommentRejection> {
    diagnostics.record("desktop_diff_comment_write_rejected")
    return Outcome.Failure(CommentRejection("No database session is open."))
  }
}
