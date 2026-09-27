package skillbill.engine.operation.prreviewfix

import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewThread

/**
 * What an analysis pins for confirm: the pull request, its head sha, and the actionable threads under the
 * runtime-assigned ordinals `T1..Tn` a selection names. Only [PR_NUMBER], [PR_HEAD_SHA], and [UNRESOLVED_THREAD_IDS]
 * are re-measured on confirm; the rest is pinned.
 */
internal data class PrReviewFixAnchors(
  val pullRequest: ReviewPullRequest,
  /** Ordinal to thread id, in ordinal order. */
  val ordinals: Map<String, String>,
) {
  fun toOperationValues(): Map<String, String> =
    measured(pullRequest, ordinals.values) +
      mapOf(
        PR_URL to pullRequest.url,
        PR_OWNER to pullRequest.owner,
        PR_NAME to pullRequest.name,
        PR_HEAD_REF to pullRequest.headRefName,
        PR_BASE_REF to pullRequest.baseRefName,
        THREAD_ORDINALS to ordinals.entries.joinToString(ENTRY_SEPARATOR) { (ordinal, id) -> "$ordinal=$id" },
      )

  companion object {
    /** Actionable threads come from the GraphQL flags alone: neither resolved nor outdated. */
    fun actionable(threads: List<ReviewThread>): List<ReviewThread> =
      threads
        .filter { thread -> !thread.isResolved && !thread.isOutdated }
        .sortedWith(compareBy({ it.path }, { it.line ?: it.originalLine ?: Int.MAX_VALUE }, { it.id }))

    fun of(
      pullRequest: ReviewPullRequest,
      actionable: List<ReviewThread>,
    ): PrReviewFixAnchors =
      PrReviewFixAnchors(
        pullRequest,
        actionable.withIndex().associate { (index, thread) -> "$ORDINAL_PREFIX${index + 1}" to thread.id },
      )

    /** The anchors confirm re-measures; any change since analysis refuses the token. */
    fun measured(
      pullRequest: ReviewPullRequest,
      actionableIds: Collection<String>,
    ): Map<String, String> =
      mapOf(
        PR_NUMBER to pullRequest.number.toString(),
        PR_HEAD_SHA to pullRequest.headOid,
        UNRESOLVED_THREAD_IDS to actionableIds.sorted().joinToString(ENTRY_SEPARATOR),
      )

    fun from(values: Map<String, String>): PrReviewFixAnchors? =
      values
        .takeIf { stored -> REQUIRED_KEYS.all(stored::containsKey) && stored[PR_NUMBER]?.toIntOrNull() != null }
        ?.let { stored ->
          PrReviewFixAnchors(
            ReviewPullRequest(
              number = stored.getValue(PR_NUMBER).toInt(),
              url = stored.getValue(PR_URL),
              owner = stored.getValue(PR_OWNER),
              name = stored.getValue(PR_NAME),
              headRefName = stored.getValue(PR_HEAD_REF),
              baseRefName = stored.getValue(PR_BASE_REF),
              headOid = stored.getValue(PR_HEAD_SHA),
            ),
            stored.getValue(THREAD_ORDINALS)
              .split(ENTRY_SEPARATOR)
              .filter(String::isNotBlank)
              .associate { entry -> entry.substringBefore('=') to entry.substringAfter('=') },
          )
        }

    const val ORDINAL_PREFIX: String = "T"
    const val PR_NUMBER: String = "pr_number"
    const val PR_HEAD_SHA: String = "pr_head_sha"
    const val UNRESOLVED_THREAD_IDS: String = "unresolved_thread_ids"
    private const val PR_URL = "pr_url"
    private const val PR_OWNER = "pr_owner"
    private const val PR_NAME = "pr_name"
    private const val PR_HEAD_REF = "pr_head_ref"
    private const val PR_BASE_REF = "pr_base_ref"
    private const val THREAD_ORDINALS = "thread_ordinals"
    private const val ENTRY_SEPARATOR = ","
    private val REQUIRED_KEYS =
      listOf(PR_NUMBER, PR_URL, PR_OWNER, PR_NAME, PR_HEAD_REF, PR_BASE_REF, PR_HEAD_SHA, THREAD_ORDINALS)
  }
}
