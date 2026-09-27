package skillbill.engine.operation.prreviewfix

import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewThread

/**
 * The PR reference a caller put first in the operator text, split from the instructions that follow it. A bare
 * number is a reference only when it is the whole text, so instructions like "3 things to check" stay instructions.
 */
internal data class PrReviewFixTarget(
  val reference: String?,
  val instructions: String?,
)

internal fun prReviewFixTarget(instructions: String?): PrReviewFixTarget {
  val text = instructions?.trim().orEmpty()
  val first = text.substringBefore(' ')
  val reference = PULL_REQUEST_REFERENCE.matches(first) || (first == text && BARE_PULL_REQUEST_NUMBER.matches(first))
  if (!reference) return PrReviewFixTarget(null, text.ifBlank { null })
  return PrReviewFixTarget(first.removePrefix("#"), text.substringAfter(' ', "").trim().ifBlank { null })
}

internal fun ReviewPullRequest.describe(): String = "PR #$number ($url)"

internal fun analysisDirective(
  pullRequest: ReviewPullRequest,
  anchors: PrReviewFixAnchors,
  threads: List<ReviewThread>,
): String {
  val byId = threads.associateBy(ReviewThread::id)
  val actionable =
    anchors.ordinals.entries.joinToString("\n\n") { (ordinal, id) ->
      val thread = byId.getValue(id)
      "### $ordinal — ${thread.location()} (thread $id)\n\n${thread.renderComments()}"
    }
  val handled =
    threads
      .filter { thread -> thread.id !in anchors.ordinals.values }
      .joinToString("\n") { thread -> "- ${thread.id} — ${thread.location()} (${thread.handledReason()})" }
      .ifBlank { "(none)" }
  val digest =
    "${pullRequest.describe()}: head `${pullRequest.headRefName}` at ${pullRequest.headOid}, base " +
      "`${pullRequest.baseRefName}`.\n\n## Actionable\n\n$actionable\n\n## Already handled\n\n$handled"
  return directive(ANALYSIS_DIRECTIVE)
    .replace("{{pull_request}}", pullRequest.describe())
    .replace("{{thread_digest}}", digest)
}

internal fun threadDirective(
  pullRequest: ReviewPullRequest,
  selected: PrReviewFixSelectedThread,
  thread: ReviewThread,
): String =
  directive(THREAD_DIRECTIVE)
    .replace("{{pull_request}}", pullRequest.describe())
    .replace("{{ordinal}}", selected.ordinal)
    .replace("{{thread_id}}", selected.threadId)
    .replace("{{location}}", thread.location())
    .replace("{{option}}", selected.option)
    .replace(
      "{{option_note}}",
      if (selected.option == FIX_AS_ASKED_OPTION) {
        "The operator chose to fix every unresolved thread: make the change the reviewer asked for, whatever the " +
          "matrix recommended."
      } else {
        "Apply option ${selected.option} of thread ${selected.ordinal} exactly as the matrix describes it."
      },
    )
    .replace("{{comments}}", thread.renderComments())

internal fun ReviewThread.location(): String = "$path:${line ?: originalLine ?: "?"}"

private fun ReviewThread.handledReason(): String =
  listOfNotNull("resolved".takeIf { isResolved }, "outdated".takeIf { isOutdated }).joinToString(", ")

private fun ReviewThread.renderComments(): String =
  comments.joinToString("\n\n") { comment ->
    "@${comment.author} (${comment.createdAt}):\n" + comment.body.trimEnd().lines().joinToString("\n") { "> $it" }
  }.ifBlank { "(no comments)" }

private fun directive(resource: String): String =
  requireNotNull(PrReviewFixTarget::class.java.getResourceAsStream(resource)) {
    "Missing pr-review-fix directive resource $resource."
  }.use { stream -> stream.readBytes().decodeToString() }

private val PULL_REQUEST_REFERENCE = Regex("""#\d+|https?://\S+/pull/\d+\S*""")
private val BARE_PULL_REQUEST_NUMBER = Regex("""\d+""")
private const val ANALYSIS_DIRECTIVE = "/skillbill/engine/operation/prreviewfix/analysis-directive.md"
private const val THREAD_DIRECTIVE = "/skillbill/engine/operation/prreviewfix/thread-directive.md"
