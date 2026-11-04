package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.RuntimeReads
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.signal.SignalSnapshot
import dev.skillbill.desktop.core.domain.model.ChangeBaseline
import dev.skillbill.desktop.core.domain.model.Comment
import dev.skillbill.desktop.core.domain.model.CommentAuthor
import dev.skillbill.desktop.core.domain.model.CommentOutcome
import dev.skillbill.desktop.core.domain.model.CommentRejection
import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.CommentState
import dev.skillbill.desktop.core.domain.model.CommentThread
import dev.skillbill.desktop.core.domain.model.DiffCommentsState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import skillbill.application.review.diffcomment.model.DiffCommentDeleteResult
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.di.core.RuntimeComponent
import skillbill.review.diffcomment.model.DiffCommentAuthor as RuntimeAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome as RuntimeOutcome
import skillbill.review.diffcomment.model.DiffCommentSide as RuntimeSide
import skillbill.review.diffcomment.model.DiffCommentThreadState as RuntimeState
import java.nio.file.Path
import kotlin.time.toKotlinInstant

@OptIn(ExperimentalCoroutinesApi::class)
class DiffCommentObservations internal constructor(
  private val signals: StateFlow<SignalSnapshot>,
  private val reads: RuntimeReads,
  private val runtime: RuntimeComponent,
  private val diagnostics: Diagnostics,
) {
  fun observe(baseline: ChangeBaseline): Flow<DiffCommentsState> = revisionChanges(DesktopReadSchema.DIFF_COMMENTS_TOPIC)
    .onStart { emit(Unit) }
    .conflate()
    .mapLatest { list(baseline) }
    .onStart { emit(DiffCommentsState.Loading) }
    .distinctUntilChanged()

  private suspend fun list(baseline: ChangeBaseline): DiffCommentsState {
    val branchRange = baseline as? ChangeBaseline.CommittedBranchRange
    val result = readOrProblem(runtime, diagnostics) {
      reads.read("diff_comments_list") { component ->
        val root = Path.of(baseline.worktreeRoot)
        val current = component.diffCommentService.list(root)
        when (current) {
          is DiffCommentListResult.Unavailable -> unavailable(current.reason)
          is DiffCommentListResult.Listed -> {
            val selected = if (branchRange != null && current.branch != branchRange.headBranch) {
              component.diffCommentService.list(root, branchRange.headBranch)
            } else {
              current
            }
            when (selected) {
              is DiffCommentListResult.Unavailable -> unavailable(selected.reason)
              is DiffCommentListResult.Listed -> selected.toDomain(current.branch)
            }
          }
        }
      }
    }
    return when (result) {
      is Outcome.Failure -> unavailable((result.error as? ReadProblem.Runtime)?.message ?: "The database is unavailable.")
      is Outcome.Success -> result.value
    }
  }

  suspend fun reply(threadId: String, body: String): Outcome<Unit, CommentRejection> = write(
    "reply",
    { it.diffCommentService.reply(threadId, RuntimeAuthor.USER, agentId = null, body = body) },
    ::threadWriteOutcome,
  )

  suspend fun setState(threadId: String, state: CommentState): Outcome<Unit, CommentRejection> = write(
    "state",
    { it.diffCommentService.setState(threadId, state.toRuntime()) },
    ::threadWriteOutcome,
  )

  suspend fun deleteComment(commentId: String): Outcome<Unit, CommentRejection> = write(
    "delete",
    { it.diffCommentService.deleteComment(commentId) },
    ::deleteWriteOutcome,
  )

  private suspend fun <T> write(
    operation: String,
    block: (RuntimeComponent) -> T,
    map: (T) -> Outcome<Unit, CommentRejection>,
  ): Outcome<Unit, CommentRejection> {
    val outcome = readOrProblem(runtime, diagnostics) { reads.write("diff_comment_$operation") { block(it) } }
    return when (outcome) {
      is Outcome.Failure -> rejected(readFailure(outcome.error))
      is Outcome.Success -> map(outcome.value)
    }
  }

  private fun threadWriteOutcome(result: DiffCommentThreadResult): Outcome<Unit, CommentRejection> = when (result) {
    is DiffCommentThreadResult.Saved -> Outcome.Success(Unit)
    is DiffCommentThreadResult.Rejected -> rejected(result.rejection.wireValue)
    is DiffCommentThreadResult.Unavailable -> rejected(result.reason)
    DiffCommentThreadResult.NotFound -> rejected("The comment thread no longer exists.")
  }

  private fun deleteWriteOutcome(result: DiffCommentDeleteResult): Outcome<Unit, CommentRejection> = when (result) {
    is DiffCommentDeleteResult.Deleted -> Outcome.Success(Unit)
    DiffCommentDeleteResult.NotFound -> rejected("The comment no longer exists.")
  }

  private fun readFailure(problem: ReadProblem): String = when (problem) {
    is ReadProblem.Runtime ->
      if (problem.message == "The database session closed.") "The database session closed. Try again." else problem.message
    is ReadProblem.Storage -> "The database session is unavailable."
  }

  private fun unavailable(reason: String): DiffCommentsState.Unavailable {
    diagnostics.record("desktop_diff_comments_unavailable")
    return DiffCommentsState.Unavailable(reason)
  }

  private fun rejected(reason: String): Outcome<Unit, CommentRejection> {
    diagnostics.record("desktop_diff_comment_write_rejected")
    return Outcome.Failure(CommentRejection(reason))
  }

  private fun revisionChanges(topic: String): Flow<Unit> =
    signals.map { it.revisions[topic] to it.resyncEpoch }.distinctUntilChanged().map { }
}

private fun DiffCommentListResult.Listed.toDomain(currentBranch: String): DiffCommentsState.Available = DiffCommentsState.Available(
  branch = branch,
  threads = threads.map { thread ->
    CommentThread(
      id = thread.id,
      path = thread.path,
      side = thread.side?.toDomain(),
      line = thread.line,
      anchorLineText = thread.anchorLineText,
      state = thread.state.toDomain(),
      outdated = thread.outdated,
      sourceLabel = thread.sourceLabel,
      severity = thread.severity,
      findingRef = thread.findingRef,
      comments = thread.comments.map { comment ->
        Comment(comment.id, comment.author.toDomain(), comment.agentId, comment.body, comment.outcome?.toDomain(), comment.createdAt.toKotlinInstant(), comment.editedAt?.toKotlinInstant())
      },
    )
  },
  canAuthor = currentBranch == branch,
)

private fun RuntimeState.toDomain() = when (this) {
  RuntimeState.PENDING -> CommentState.Pending
  RuntimeState.OPEN -> CommentState.Open
  RuntimeState.RESOLVED -> CommentState.Resolved
  RuntimeState.DECLINED -> CommentState.Declined
}

private fun RuntimeAuthor.toDomain() = when (this) {
  RuntimeAuthor.USER -> CommentAuthor.User
  RuntimeAuthor.AGENT -> CommentAuthor.Agent
}

private fun RuntimeSide.toDomain() = when (this) {
  RuntimeSide.OLD -> CommentSide.Old
  RuntimeSide.NEW -> CommentSide.New
}

private fun RuntimeOutcome.toDomain() = when (this) {
  RuntimeOutcome.FIXED -> CommentOutcome.Fixed
  RuntimeOutcome.REFUTED -> CommentOutcome.Refuted
  RuntimeOutcome.SKIPPED -> CommentOutcome.Skipped
  RuntimeOutcome.FAILED -> CommentOutcome.Failed
}

private fun CommentState.toRuntime() = when (this) {
  CommentState.Pending -> RuntimeState.PENDING
  CommentState.Open -> RuntimeState.OPEN
  CommentState.Resolved -> RuntimeState.RESOLVED
  CommentState.Declined -> RuntimeState.DECLINED
}
