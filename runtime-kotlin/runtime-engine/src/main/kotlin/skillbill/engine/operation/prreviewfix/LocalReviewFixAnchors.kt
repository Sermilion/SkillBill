package skillbill.engine.operation.prreviewfix

import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState

internal data class LocalReviewFixAnchors(
  val repositoryIdentity: String,
  val headBranch: String,
  val headSha: String,
  val ordinals: Map<String, String>,
) {
  fun toOperationValues(): Map<String, String> =
    measured(headSha, ordinals.values) +
      mapOf(
        MODE to PrReviewFixMode.LOCAL.wireValue,
        REPOSITORY_IDENTITY to repositoryIdentity,
        HEAD_BRANCH to headBranch,
        THREAD_ORDINALS to ordinals.entries.joinToString(ENTRY_SEPARATOR) { (ordinal, id) -> "$ordinal=$id" },
      )

  companion object {
    fun actionable(threads: List<DiffCommentThread>): List<DiffCommentThread> =
      threads.filter { it.state == DiffCommentThreadState.OPEN }
        .sortedWith(compareBy<DiffCommentThread>({ it.path != null }, { it.path }, { it.line }, { it.id }))

    fun of(
      repositoryIdentity: String,
      headBranch: String,
      headSha: String,
      threads: List<DiffCommentThread>,
    ): LocalReviewFixAnchors =
      LocalReviewFixAnchors(
        repositoryIdentity,
        headBranch,
        headSha,
        threads.withIndex().associate { (index, thread) ->
          "${PrReviewFixAnchors.ORDINAL_PREFIX}${index + 1}" to thread.id
        },
      )

    fun measured(
      headSha: String,
      ids: Collection<String>,
    ): Map<String, String> =
      mapOf(
        MODE to PrReviewFixMode.LOCAL.wireValue,
        HEAD_SHA to headSha,
        OPEN_THREAD_IDS to ids.sorted().joinToString(ENTRY_SEPARATOR),
      )

    fun from(values: Map<String, String>): LocalReviewFixAnchors? =
      values.takeIf { stored ->
        stored[MODE] == PrReviewFixMode.LOCAL.wireValue && REQUIRED_KEYS.all(stored::containsKey)
      }
        ?.let { stored ->
          LocalReviewFixAnchors(
            stored.getValue(REPOSITORY_IDENTITY),
            stored.getValue(HEAD_BRANCH),
            stored.getValue(HEAD_SHA),
            stored.getValue(THREAD_ORDINALS)
              .split(ENTRY_SEPARATOR)
              .filter(String::isNotBlank)
              .associate { it.substringBefore('=') to it.substringAfter('=') },
          )
        }

    const val HEAD_SHA: String = "head_sha"
    const val OPEN_THREAD_IDS: String = "open_thread_ids"
    private const val MODE = "mode"
    private const val REPOSITORY_IDENTITY = "repository_identity"
    private const val HEAD_BRANCH = "head_branch"
    private const val THREAD_ORDINALS = "thread_ordinals"
    private const val ENTRY_SEPARATOR = ","
    private val REQUIRED_KEYS =
      listOf(REPOSITORY_IDENTITY, HEAD_BRANCH, HEAD_SHA, OPEN_THREAD_IDS, THREAD_ORDINALS)
  }
}
