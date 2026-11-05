package skillbill.application.review.diffcomment

import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.contracts.review.DiffCommentContract
import skillbill.contracts.review.DiffCommentFailureContract
import skillbill.contracts.review.DiffCommentFailureStatus
import skillbill.contracts.review.DiffCommentThreadContract
import skillbill.contracts.review.DiffCommentThreadListContract
import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentThread

private fun DiffComment.toDiffCommentContract(): DiffCommentContract =
  DiffCommentContract(
    commentId = id,
    author = author.wireValue,
    agentId = agentId,
    body = body,
    outcome = outcome?.wireValue,
    createdAt = createdAt.toString(),
  )

fun DiffCommentThread.toDiffCommentThreadContract(): DiffCommentThreadContract =
  DiffCommentThreadContract(
    threadId = id,
    state = state.wireValue,
    outdated = outdated,
    path = path,
    side = side?.wireValue,
    line = line,
    sourceLabel = sourceLabel,
    severity = severity,
    findingRef = findingRef,
    comments = comments.map(DiffComment::toDiffCommentContract),
  )

fun toDiffCommentThreadListContract(
  headBranch: String,
  threads: List<DiffCommentThread>,
): DiffCommentThreadListContract =
  DiffCommentThreadListContract(
    headBranch = headBranch,
    threads = threads.map(DiffCommentThread::toDiffCommentThreadContract),
  )

fun DiffCommentListResult.toFailureContract(): DiffCommentFailureContract? =
  when (this) {
    is DiffCommentListResult.Unavailable ->
      DiffCommentFailureContract(DiffCommentFailureStatus.UNAVAILABLE, reason)
    is DiffCommentListResult.Listed -> null
  }

fun DiffCommentThreadResult.toFailureContract(contextThreadId: String): DiffCommentFailureContract? =
  when (this) {
    is DiffCommentThreadResult.Unavailable ->
      DiffCommentFailureContract(DiffCommentFailureStatus.UNAVAILABLE, reason)
    DiffCommentThreadResult.NotFound ->
      DiffCommentFailureContract(DiffCommentFailureStatus.NOT_FOUND, contextThreadId)
    is DiffCommentThreadResult.Rejected ->
      DiffCommentFailureContract(DiffCommentFailureStatus.REJECTED, rejection.wireValue)
    is DiffCommentThreadResult.Saved -> null
  }
