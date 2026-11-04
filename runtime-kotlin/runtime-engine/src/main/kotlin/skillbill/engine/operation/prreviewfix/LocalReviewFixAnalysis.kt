package skillbill.engine.operation.prreviewfix

import skillbill.engine.directive.directiveResource
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThread

internal fun localAnalysisDirective(
  branch: String,
  headSha: String,
  anchors: LocalReviewFixAnchors,
  threads: List<DiffCommentThread>,
): String {
  val byId = threads.associateBy(DiffCommentThread::id)
  val digest =
    anchors.ordinals.entries.joinToString("\n\n") { (ordinal, id) ->
      val thread = byId.getValue(id)
      val metadata =
        buildList {
          add("Side: ${thread.side?.wireValue ?: "—"}")
          add("Outdated: ${if (thread.outdated) "yes" else "no"}")
          thread.severity?.let { add("Severity: $it") }
          thread.findingRef?.let { add("Finding: $it") }
        }.joinToString("\n")
      val comments = thread.comments.joinToString("\n\n") { "${it.author.wireValue}: ${it.body}" }
      "### $ordinal — ${localLocation(thread)} (thread ${thread.id})\n$metadata\n\n$comments"
    }
  return directiveResource(LOCAL_ANALYSIS_DIRECTIVE)
    .replace("{{branch}}", branch)
    .replace("{{head_sha}}", headSha)
    .replace("{{thread_digest}}", digest)
}

internal fun localThreadDirective(
  selected: PrReviewFixSelectedThread,
  thread: DiffCommentThread,
): String =
  directiveResource(LOCAL_THREAD_DIRECTIVE)
    .replace("{{ordinal}}", selected.ordinal)
    .replace("{{thread_id}}", selected.threadId)
    .replace("{{location}}", localLocation(thread))
    .replace("{{option}}", selected.option)
    .replace(
      "{{option_note}}",
      if (selected.option == FIX_AS_ASKED_OPTION) {
        "The operator chose to fix every open local thread: make the change the reviewer asked for, " +
          "whatever the matrix recommended."
      } else {
        "Apply option ${selected.option} of thread ${selected.ordinal} exactly as the matrix describes it."
      },
    )
    .replace("{{comments}}", thread.comments.joinToString("\n\n") { "${it.author.wireValue}: ${it.body}" })

internal fun parseLocalReplyOutcome(
  reply: String,
  changedPaths: List<String>,
  diagnostics: RuntimeDiagnostics,
): Pair<DiffCommentOutcome, String> {
  val lines = reply.lines()
  val firstContentLineIndex = lines.indexOfFirst { it.isNotBlank() && !it.trimStart().startsWith(CODE_FENCE) }
  val outcomeLine = lines.getOrNull(firstContentLineIndex)?.trim { it.isWhitespace() || it in MARKDOWN_DECORATION }
  val match = outcomeLine?.let(OUTCOME_LINE::find)
  val trailing = match?.groupValues?.get(2).orEmpty().trimStart()
  val copiedOutcomeAlternativesTemplate = trailing.startsWith('|')
  val declared =
    if (copiedOutcomeAlternativesTemplate) {
      null
    } else {
      match?.groupValues?.get(1)?.lowercase()?.let(DiffCommentOutcome::fromWire)
    }
  val outcome =
    declared ?: if (changedPaths.isNotEmpty()) DiffCommentOutcome.FIXED else DiffCommentOutcome.SKIPPED
  if (declared == null) {
    val cause = if (match == null) "missing_outcome" else "unknown_outcome"
    diagnostics.warning(
      "seam=local_review_reply_outcome value_expected=declared_outcome value_used=${outcome.wireValue} cause=$cause",
    )
  }
  val body =
    if (match == null) {
      reply.trim()
    } else {
      val prose =
        if (copiedOutcomeAlternativesTemplate) {
          ""
        } else {
          trailing.trimStart { it.isWhitespace() || it in LEADING_SEPARATORS }
        }
      (listOf(prose) + lines.drop(firstContentLineIndex + 1)).joinToString("\n").trim()
    }
  return outcome to body
}

private fun localLocation(thread: DiffCommentThread): String =
  when {
    thread.path == null -> "general"
    thread.line == null -> "${thread.path} (file)"
    else -> "${thread.path}:${thread.line}"
  }

private const val LOCAL_ANALYSIS_DIRECTIVE = "/skillbill/engine/operation/prreviewfix/local-analysis-directive.md"
private const val LOCAL_THREAD_DIRECTIVE = "/skillbill/engine/operation/prreviewfix/local-thread-directive.md"
private const val CODE_FENCE = "```"
private const val MARKDOWN_DECORATION = "`*_>#-"
private const val LEADING_SEPARATORS = ".,;:—–-*_`)"
private val OUTCOME_LINE = Regex("""(?i)^outcome[*_`\s]*:[*_`\s]*([a-z]+)\b(.*)$""")
