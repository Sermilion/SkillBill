package skillbill.workflow.taskruntime.validation

private const val GATE_STDOUT_EXCERPT_MAX_CHARS: Int = 3_000

fun unparseableGateFailureMessage(
  gateLabel: String,
  outcome: String,
  exitCode: Int,
  stdout: String,
): String {
  val lead =
    "$gateLabel reported outcome=$outcome exit=$exitCode without parseable findings; " +
      "repair the underlying failure the gate detected."
  val excerpt = gateStdoutExcerpt(stdout) ?: return "$lead Gate stdout was empty."
  return "$lead Gate stdout (head+tail):\n$excerpt"
}

fun gateStdoutExcerpt(
  stdout: String,
  maxChars: Int = GATE_STDOUT_EXCERPT_MAX_CHARS,
): String? {
  val trimmed = stdout.trim().takeIf { it.isNotEmpty() } ?: return null
  if (trimmed.length <= maxChars) return trimmed
  val keptChars = maxChars - omittedMarker(trimmed.length).length
  if (keptChars <= 0) return trimmed.take(maxChars)
  val headChars = keptChars / 2
  val tailChars = keptChars - headChars
  val omitted = trimmed.length - headChars - tailChars
  return trimmed.take(headChars) + omittedMarker(omitted) + trimmed.takeLast(tailChars)
}

private fun omittedMarker(omitted: Int): String = "\n…[$omitted chars omitted]…\n"
