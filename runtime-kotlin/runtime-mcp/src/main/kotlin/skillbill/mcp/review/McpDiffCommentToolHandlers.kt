package skillbill.mcp.review

import skillbill.application.review.diffcomment.addAgentThread
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadInput
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentRejection
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.application.review.diffcomment.reply
import skillbill.application.review.diffcomment.setState
import skillbill.application.review.diffcomment.toDiffCommentThreadContract
import skillbill.application.review.diffcomment.toDiffCommentThreadListContract
import skillbill.application.review.diffcomment.toFailureContract
import skillbill.contracts.review.DiffCommentFailureContract
import skillbill.contracts.review.DiffCommentFailureStatus
import skillbill.contracts.review.DiffCommentPayloadKeys
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import java.nio.file.Path

internal fun diffCommentList(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val repoRoot = arguments.repoRoot()
  val branch = arguments.optionalString(DiffCommentPayloadKeys.BRANCH)
  val stateFilter = arguments.optionalString(DiffCommentPayloadKeys.STATE)
  return when (val result = component.diffCommentService.list(repoRoot, branch)) {
    is DiffCommentListResult.Unavailable -> result.toFailureContract()!!.toErrorPayload()
    is DiffCommentListResult.Listed -> {
      val threads = result.threads.filter { stateFilter == null || it.state.wireValue == stateFilter }
      toDiffCommentThreadListContract(result.branch, threads).toPayload()
    }
  }
}

internal fun diffCommentAdd(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val repoRoot = arguments.repoRoot()
  val path = arguments.optionalString(DiffCommentPayloadKeys.PATH)
  val line = arguments.optionalInt(DiffCommentPayloadKeys.LINE)
  val sideToken = arguments.optionalString(DiffCommentPayloadKeys.SIDE)
  val lineWithoutPath = line != null && path == null
  val sideWithoutLine = sideToken != null && line == null
  if (lineWithoutPath || sideWithoutLine) {
    return DiffCommentFailureContract(
      DiffCommentFailureStatus.REJECTED,
      DiffCommentRejection.INVALID_PATH.wireValue,
    ).toErrorPayload()
  }
  return component.diffCommentService
    .addAgentThread(
      repoRoot,
      DiffCommentAgentThreadInput(
        path = path,
        side = sideToken,
        line = line,
        body = arguments.string(DiffCommentPayloadKeys.BODY),
        agentId = arguments.optionalString(DiffCommentPayloadKeys.AGENT_ID),
      ),
    )
    .toThreadPayload(contextThreadId = "")
}

internal fun diffCommentReply(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val threadId = arguments.string(DiffCommentPayloadKeys.THREAD_ID)
  val outcome = arguments.optionalString(DiffCommentPayloadKeys.OUTCOME)
  return component.diffCommentService
    .reply(
      threadId,
      arguments.optionalString(DiffCommentPayloadKeys.AGENT_ID),
      arguments.string(DiffCommentPayloadKeys.BODY),
      outcome,
    )
    .toThreadPayload(contextThreadId = threadId)
}

internal fun diffCommentSetState(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val threadId = arguments.string(DiffCommentPayloadKeys.THREAD_ID)
  val state = arguments.string(DiffCommentPayloadKeys.STATE)
  return component.diffCommentService.setState(threadId, state).toThreadPayload(contextThreadId = threadId)
}

private fun McpToolArguments.repoRoot(): Path =
  Path.of(string(DiffCommentPayloadKeys.REPO_ROOT)).toAbsolutePath().normalize()

private fun DiffCommentThreadResult.toThreadPayload(contextThreadId: String): Map<String, Any?> {
  val failure = toFailureContract(contextThreadId)
  if (failure != null) {
    return failure.toErrorPayload()
  }
  val saved = this as DiffCommentThreadResult.Saved
  return saved.thread.toDiffCommentThreadContract().toPayload()
}

private fun DiffCommentFailureContract.toErrorPayload(): Map<String, Any?> =
  toPayload() + (McpToolPayloadKeys.IS_ERROR to true)
