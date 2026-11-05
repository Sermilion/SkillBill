package skillbill.workflow.taskruntime.validation

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal

object FeatureTaskRuntimeProviderLimitDetector {
  const val INSPECTED_TAIL_CHARS: Int = 2000
  private const val EVIDENCE_MAX_CHARS = 200

  private val SIGNATURES: List<Regex> =
    listOf(
      """hit your [a-z0-9 -]{0,24}limit""",
      """reached your [a-z0-9 -]{0,24}limit""",
      """usage limit reached""",
      """rate[ _-]?limit(?:_error| exceeded|ed)""",
      """too many requests""",
      """(?:status|code|http)\D{0,10}429""",
      """quota exceeded""",
      """insufficient_quota""",
      """usage_limit_exceeded""",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

  private val RESET_HINT =
    Regex(
      """(?:reset[s]?(?:\s+(?:at|on|in))?\s+|try again at\s+)([^\n]{1,60})""",
      RegexOption.IGNORE_CASE,
    )

  fun detect(vararg outputs: String): FeatureTaskRuntimeProviderLimitSignal? =
    outputs.firstNotNullOfOrNull(::detectInTail)

  private fun detectInTail(output: String): FeatureTaskRuntimeProviderLimitSignal? {
    val tail = output.takeLast(INSPECTED_TAIL_CHARS)
    val line =
      tail.lineSequence()
        .map(String::trim)
        .firstOrNull { candidate -> SIGNATURES.any { it.containsMatchIn(candidate) } }
        ?: return null
    return FeatureTaskRuntimeProviderLimitSignal(
      evidence = line.take(EVIDENCE_MAX_CHARS),
      resetHint = extractResetHint(tail),
    )
  }

  private fun extractResetHint(tail: String): String? =
    tail.lineSequence()
      .map(String::trim)
      .firstNotNullOfOrNull(::resetHintInLine)

  private fun resetHintInLine(line: String): String? =
    RESET_HINT.find(line)
      ?.groupValues
      ?.get(1)
      ?.trim()
      ?.trimEnd('.', ',')
      ?.takeIf(String::isNotBlank)
}
