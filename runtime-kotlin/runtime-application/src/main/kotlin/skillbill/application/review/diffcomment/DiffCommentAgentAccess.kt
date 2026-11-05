package skillbill.application.review.diffcomment

import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadInput
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentRejection
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.nio.file.Path

object DiffCommentAgentAccess {
  val states: List<String> = DiffCommentThreadState.entries.map { it.wireValue }
  val editableStates: List<String> =
    DiffCommentThreadState.entries.filter { it != DiffCommentThreadState.PENDING }.map { it.wireValue }
  val sides: List<String> = DiffCommentSide.entries.map { it.wireValue }
  val outcomes: List<String> = DiffCommentOutcome.entries.map { it.wireValue }
}

fun DiffCommentService.addAgentThread(
  repoRoot: Path,
  input: DiffCommentAgentThreadInput,
): DiffCommentThreadResult {
  val parsedSide = input.side?.let(DiffCommentSide::fromWire)
  if (input.side != null && parsedSide == null) {
    return DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_PATH)
  }
  return addAgentThread(
    repoRoot,
    DiffCommentAgentThreadRequest(
      path = input.path,
      side = if (input.line != null) parsedSide ?: DiffCommentSide.NEW else parsedSide,
      line = input.line,
      body = input.body,
      agentId = input.agentId,
    ),
  )
}

fun DiffCommentService.reply(
  threadId: String,
  agentId: String?,
  body: String,
  outcome: String?,
): DiffCommentThreadResult {
  val parsedOutcome = outcome?.let(DiffCommentOutcome::fromWire)
  if (outcome != null && parsedOutcome == null) {
    return DiffCommentThreadResult.Rejected(DiffCommentRejection.OUTCOME_NOT_ALLOWED)
  }
  return reply(threadId, DiffCommentAuthor.AGENT, agentId, body, parsedOutcome)
}

fun DiffCommentService.setState(
  threadId: String,
  state: String,
): DiffCommentThreadResult {
  val parsedState = DiffCommentThreadState.fromWire(state)
  if (parsedState == null || parsedState == DiffCommentThreadState.PENDING) {
    return DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_TRANSITION)
  }
  return setState(threadId, parsedState)
}
