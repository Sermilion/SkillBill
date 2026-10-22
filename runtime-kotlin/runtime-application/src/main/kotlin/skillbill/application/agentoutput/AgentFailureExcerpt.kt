package skillbill.application.agentoutput

fun agentFailureExcerpt(
  stderr: String,
  stdout: String,
  maxChars: Int,
): String? {
  fun signal(stream: String): String =
    stream.lineSequence()
      .map(String::trim)
      .filter { it.isNotBlank() }
      .filterNot(::isHarnessStatusBanner)
      .joinToString("\n")
  val preferred = signal(stderr).ifBlank { signal(stdout) }
  return headAndTailExcerpt(preferred, maxChars)
}

private fun isHarnessStatusBanner(line: String): Boolean {
  val normalized = line.trim()
  return HARNESS_STATUS_BANNERS.any { banner -> normalized.equals(banner, ignoreCase = true) }
}

fun headAndTailExcerpt(
  text: String,
  maxChars: Int,
): String? {
  val trimmed = text.takeIf(String::isNotBlank) ?: return null
  if (trimmed.length <= maxChars) {
    return trimmed
  }
  val headChars = maxChars / 2
  val tailChars = maxChars - headChars
  val omitted = trimmed.length - headChars - tailChars
  return buildString {
    append(trimmed.take(headChars))
    append("\n…[")
    append(omitted)
    append(" chars omitted]…\n")
    append(trimmed.takeLast(tailChars))
  }
}

private val HARNESS_STATUS_BANNERS: List<String> =
  listOf(
    "Reading prompt from stdin...",
  )
