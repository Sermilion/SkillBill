package skillbill.contracts.review

import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys

enum class DiffCommentFailureStatus(val wireValue: String) {
  UNAVAILABLE("unavailable"),
  NOT_FOUND("not_found"),
  REJECTED("rejected"),
}

data class DiffCommentContract(
  val commentId: String,
  val author: String,
  val agentId: String?,
  val body: String,
  val outcome: String?,
  val createdAt: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      DiffCommentPayloadKeys.COMMENT_ID to commentId,
      DiffCommentPayloadKeys.AUTHOR to author,
      DiffCommentPayloadKeys.AGENT_ID to agentId,
      DiffCommentPayloadKeys.BODY to body,
      DiffCommentPayloadKeys.OUTCOME to outcome,
      DiffCommentPayloadKeys.CREATED_AT to createdAt,
    )
}

data class DiffCommentThreadContract(
  val threadId: String,
  val state: String,
  val outdated: Boolean,
  val path: String?,
  val side: String?,
  val line: Int?,
  val sourceLabel: String?,
  val severity: String?,
  val findingRef: String?,
  val comments: List<DiffCommentContract>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      DiffCommentPayloadKeys.THREAD_ID to threadId,
      DiffCommentPayloadKeys.STATE to state,
      DiffCommentPayloadKeys.OUTDATED to outdated,
      DiffCommentPayloadKeys.PATH to path,
      DiffCommentPayloadKeys.SIDE to side,
      DiffCommentPayloadKeys.LINE to line,
      DiffCommentPayloadKeys.SOURCE_LABEL to sourceLabel,
      DiffCommentPayloadKeys.SEVERITY to severity,
      DiffCommentPayloadKeys.FINDING_REF to findingRef,
      DiffCommentPayloadKeys.COMMENTS to comments.map(DiffCommentContract::toPayload),
    )
}

data class DiffCommentThreadListContract(
  val headBranch: String,
  val threads: List<DiffCommentThreadContract>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      DiffCommentPayloadKeys.HEAD_BRANCH to headBranch,
      DiffCommentPayloadKeys.THREADS to threads.map(DiffCommentThreadContract::toPayload),
    )
}

data class DiffCommentFailureContract(
  val status: DiffCommentFailureStatus,
  val reason: String,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to status.wireValue,
      UpdateCheckPayloadKeys.REASON to reason,
    )
}
