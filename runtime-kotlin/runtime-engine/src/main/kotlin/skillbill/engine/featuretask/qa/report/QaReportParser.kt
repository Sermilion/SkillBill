package skillbill.engine.featuretask.qa.report

internal object QaReportParser {
  private const val FINDING_HEADING_PREFIX = "### "

  private val VERDICT_WORD =
    Regex(
      "(?<![A-Za-z0-9_])(findings_open|findings|passed|incomplete|skipped)(?![A-Za-z0-9_])",
      RegexOption.IGNORE_CASE,
    )
  private val SECTION_BREAK = Regex("^#{1,3}\\s")
  private val DIGIT = Regex("\\d")
  private val PATH_TERMINATORS = charArrayOf('.', ':', ';', ',')

  fun parse(
    text: String,
    evidencePrefix: String,
    reference: String? = null,
  ): QaReport {
    val verdict = verdictOf(text)
    val findings = findingsOf(text, evidencePrefix)
    val warnings =
      if (verdict == QaVerdict.PASSED && findings.isNotEmpty()) {
        listOf(
          "QA report states passed but lists findings ${findings.joinToString { it.id }}; no repair opened",
        )
      } else {
        emptyList()
      }
    return QaReport(text, verdict, null, findings, reference, warnings)
  }

  private fun verdictOf(text: String): QaVerdict {
    val word =
      text.lineSequence().firstNotNullOfOrNull { line -> VERDICT_WORD.find(line)?.value?.lowercase() }
        ?: return QaVerdict.INCOMPLETE
    return when (word) {
      "passed" -> QaVerdict.PASSED
      "skipped" -> QaVerdict.SKIPPED
      "incomplete" -> QaVerdict.INCOMPLETE
      else -> QaVerdict.FINDINGS_OPEN
    }
  }

  private fun findingsOf(
    text: String,
    evidencePrefix: String,
  ): List<QaReportFinding> {
    val lines = lineSpans(text)
    val findings = mutableListOf<QaReportFinding>()
    var index = 0
    while (index < lines.size) {
      val heading = text.substring(lines[index].start, lines[index].contentEnd)
      var end = index + 1
      while (end < lines.size && !SECTION_BREAK.containsMatchIn(lines[end].content(text))) end++
      if (heading.startsWith(FINDING_HEADING_PREFIX)) {
        val sectionEnd = lines.getOrNull(end)?.start ?: text.length
        findingOf(
          heading,
          lines.subList(index + 1, end).map { line -> line.content(text) },
          text.substring(lines[index].start, sectionEnd),
          evidencePrefix,
        )?.let(findings::add)
      }
      index = end
    }
    return findings
  }

  private fun findingOf(
    heading: String,
    body: List<String>,
    section: String,
    evidencePrefix: String,
  ): QaReportFinding? {
    val headingText = heading.removePrefix(FINDING_HEADING_PREFIX).trim()
    val token = headingText.substringBefore(' ').trimEnd(':', '.')
    if (token.isEmpty() || !DIGIT.containsMatchIn(token)) return null
    val title =
      headingText.substringAfter(' ', "").trim()
        .ifEmpty { body.firstOrNull(String::isNotBlank)?.trim().orEmpty() }
    return QaReportFinding(token, title, section, evidencePathsOf(section, evidencePrefix))
  }

  private fun lineSpans(text: String): List<LineSpan> {
    if (text.isEmpty()) return emptyList()
    val lines = mutableListOf<LineSpan>()
    var start = 0
    while (start < text.length) {
      val lineBreak = text.indexOf('\n', start)
      if (lineBreak < 0) {
        lines += LineSpan(start, text.length)
        break
      }
      val contentEnd = if (lineBreak > start && text[lineBreak - 1] == '\r') lineBreak - 1 else lineBreak
      lines += LineSpan(start, contentEnd)
      start = lineBreak + 1
    }
    return lines
  }

  private data class LineSpan(
    val start: Int,
    val contentEnd: Int,
  ) {
    fun content(text: String): String = text.substring(start, contentEnd)
  }

  private fun evidencePathsOf(
    section: String,
    evidencePrefix: String,
  ): List<String> {
    if (evidencePrefix.isBlank()) return emptyList()
    val pattern = Regex(Regex.escape(evidencePrefix) + "[^\\s`'\"<>()\\[\\]{}|]+")
    return pattern.findAll(section)
      .map { it.value.trimEnd { character -> character in PATH_TERMINATORS } }
      .filter { path -> path.length > evidencePrefix.length && path.split('/').none { segment -> segment == ".." } }
      .distinct()
      .toList()
  }
}
