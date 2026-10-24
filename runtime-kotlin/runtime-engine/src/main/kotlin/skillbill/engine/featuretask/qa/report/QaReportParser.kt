package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.scaffold.model.QaTargetKind

internal object QaReportParser {
  private const val FINDING_HEADING_PREFIX = "### "
  private const val WEB_KIND = "web"

  private val VERDICT_WORD =
    Regex(
      "(?<![A-Za-z0-9_])(findings_open|findings|passed|incomplete|skipped)(?![A-Za-z0-9_])",
      RegexOption.IGNORE_CASE,
    )
  private val SECTION_BREAK = Regex("^#{1,3}\\s")
  private val DIGIT = Regex("\\d")
  private val PATH_TERMINATORS = charArrayOf('.', ':', ';', ',')
  private val COVERAGE_LINE =
    Regex("^[-*]\\s*([a-z][a-z0-9-]{0,31})\\s*:\\s*(passed|findings_open|findings|skipped|incomplete)\\b")
  private val REASON_TOKEN = Regex("(?<![A-Za-z0-9_])reason:\\s*([a-z_]+)", RegexOption.IGNORE_CASE)
  private val BACKEND_TOKEN = Regex("backend:\\s*(real|mocked|none)")

  fun parse(
    text: String,
    evidencePrefix: String,
    reference: String? = null,
    declaredKinds: Set<QaTargetKind> = emptySet(),
  ): QaReport {
    val verdict = verdictOf(text)
    val findings = findingsOf(text, evidencePrefix)
    val coverage = coverageOf(sectionLines(text, "coverage"))
    val assertionsReported = sectionLines(text, "assertions").any(String::isNotBlank)
    val isolationReported = sectionLines(text, "isolation").any(String::isNotBlank)
    val findingsWarnings =
      if (verdict == QaVerdict.PASSED && findings.isNotEmpty()) {
        listOf(
          "QA report states passed but lists findings ${findings.joinToString { it.id }}; no repair opened",
        )
      } else {
        emptyList()
      }
    val coverageWarnings =
      buildList {
        if (verdict == QaVerdict.PASSED && !assertionsReported) {
          add("QA report states passed but lists no assertions; the pass is unverified")
        }
        if (verdict == QaVerdict.PASSED && !isolationReported) {
          add("QA report states passed without isolation evidence")
        }
        declaredKinds.sortedBy { it.wireValue }.filter { kind -> coverage.none { it.kind == kind } }
          .forEach { kind -> add("QA report has no coverage for target ${kind.wireValue}") }
        addAll(partialCoverageWarnings(verdict, coverage))
      }
    return QaReport(
      text = text,
      verdict = verdict,
      reason = reasonOf(text, verdict),
      findings = findings,
      reference = reference,
      warnings = findingsWarnings + coverageWarnings,
      coverage = coverage,
      assertionsReported = assertionsReported,
      coverageWarnings = coverageWarnings,
    )
  }

  private fun partialCoverageWarnings(
    verdict: QaVerdict,
    coverage: List<QaTargetCoverage>,
  ): List<String> {
    val overallRan = verdict == QaVerdict.PASSED || verdict == QaVerdict.FINDINGS_OPEN
    val partial =
      coverage
        .filter { line -> overallRan && (line.verdict == QaVerdict.SKIPPED || line.verdict == QaVerdict.INCOMPLETE) }
        .map { line ->
          "QA covered target ${line.kind.wireValue} only partially: " +
            line.verdict.wireValue + line.reason?.let { " ($it)" }.orEmpty()
        }
    val mockOnly =
      coverage
        .filter { line ->
          line.kind.wireValue == WEB_KIND &&
            line.backend == QaBackendAttribution.MOCKED &&
            line.verdict == QaVerdict.PASSED
        }
        .map { "Target web passed against a mocked backend; backend integration is untested" }
    return partial + mockOnly
  }

  private fun reasonOf(
    text: String,
    verdict: QaVerdict,
  ): String? {
    if (verdict != QaVerdict.SKIPPED && verdict != QaVerdict.INCOMPLETE) return null
    val lines = text.lines()
    val verdictAt = lines.indexOfFirst { line -> VERDICT_WORD.containsMatchIn(line) }
    if (verdictAt < 0) return null
    val token =
      listOfNotNull(lines[verdictAt], lines.drop(verdictAt + 1).firstOrNull(String::isNotBlank))
        .firstNotNullOfOrNull { line -> REASON_TOKEN.find(line)?.groupValues?.get(1) }
    return QaSkipReason.fromWire(token?.lowercase())?.wireValue
  }

  private fun sectionLines(
    text: String,
    heading: String,
  ): List<String> {
    val headingLine = Regex("^#{1,3}\\s*$heading\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    return text.lines()
      .dropWhile { line -> !headingLine.matches(line.trim()) }
      .drop(1)
      .takeWhile { line -> !SECTION_BREAK.containsMatchIn(line) }
  }

  private fun coverageOf(lines: List<String>): List<QaTargetCoverage> =
    lines.mapNotNull { raw ->
      val line = raw.trim().lowercase()
      val match = COVERAGE_LINE.find(line) ?: return@mapNotNull null
      val rest = line.substring(match.range.last + 1)
      QaTargetCoverage(
        kind = QaTargetKind(match.groupValues[1]),
        verdict = verdictOfWord(match.groupValues[2]),
        reason = REASON_TOKEN.find(rest)?.groupValues?.get(1),
        backend = QaBackendAttribution.fromWire(BACKEND_TOKEN.find(rest)?.groupValues?.get(1)),
      )
    }

  private fun verdictOfWord(word: String): QaVerdict =
    when (word) {
      "passed" -> QaVerdict.PASSED
      "skipped" -> QaVerdict.SKIPPED
      "incomplete" -> QaVerdict.INCOMPLETE
      else -> QaVerdict.FINDINGS_OPEN
    }

  private fun verdictOf(text: String): QaVerdict {
    val word =
      text.lineSequence().firstNotNullOfOrNull { line -> VERDICT_WORD.find(line)?.value?.lowercase() }
        ?: return QaVerdict.INCOMPLETE
    return verdictOfWord(word)
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
