package dev.skillbill.desktop.feature.detail

internal data class ValidationReportPart(
  val title: String,
  val text: String,
  val flagged: Boolean,
  val openByDefault: Boolean,
  val listItemCount: Int,
  val preview: String,
)

internal data class ValidationReportShape(
  val summary: String,
  val parts: List<ValidationReportPart>,
) {
  val headline: String get() = firstSentence(summary).first
  val summaryRest: String? get() = firstSentence(summary).second
}

private val partStart = Regex("^\\*\\*(.+?)\\.?\\*\\*|^(Warnings|Warning|Risk|Notes|Note):")
private val flaggedTitle = Regex("warn|risk|couldn.t run|skipped|later phases|concurrent|blocked", RegexOption.IGNORE_CASE)
private val openTitle = Regex("check|result", RegexOption.IGNORE_CASE)
private val listItem = Regex("^(?:[-*•] |[0-9]+\\. )")
private val markdownEmphasis = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|\\*(.+?)\\*|_(.+?)_|`([^`]+)`")

internal fun shapeValidationReport(text: String?): ValidationReportShape? {
  if (text.isNullOrBlank()) return null
  val lines = text.trim().lines()
  val firstContent = lines.indexOfFirst(String::isNotBlank)
  if (firstContent < 0) return null
  var inFence = false
  val headings = lines.map { line ->
    val fence = line.trimStart().startsWith("```")
    val heading = if (inFence || fence) null else findHeading(line)
    if (fence) inFence = !inFence
    heading
  }
  val firstParagraphEnd = (firstContent until lines.size).firstOrNull { lines[it].isBlank() }?.minus(1) ?: lines.lastIndex
  val initialHeading = headings[firstContent]
  val headingAfterSummary = (firstContent + 1..firstParagraphEnd).firstOrNull { headings[it] != null }
  val summaryEnd = when {
    initialHeading != null -> firstContent - 1
    headingAfterSummary != null -> headingAfterSummary - 1
    else -> firstParagraphEnd
  }
  val summary = if (summaryEnd < firstContent) "" else lines.subList(firstContent, summaryEnd + 1).joinToString("\n").trim()
  val blocks = mutableListOf<Pair<String, String>>()
  var activeTitle: String? = null
  val activeText = mutableListOf<String>()
  fun flush() {
    val title = activeTitle
    val body = activeText.joinToString("\n").trim()
    if (title != null) blocks += title to body
    activeText.clear()
  }

  fun visitLine(index: Int) {
    val line = lines[index]
    val heading = headings[index]
    if (heading != null) {
      flush()
      activeTitle = partTitle(heading)
      line.substring(heading.range.last + 1).trimStart().takeIf(String::isNotBlank)?.let(activeText::add)
    } else if (activeTitle != null) {
      activeText += line
    }
  }

  if (initialHeading != null) {
    activeTitle = partTitle(initialHeading)
    lines[firstContent].substring(initialHeading.range.last + 1).trimStart().takeIf(String::isNotBlank)?.let(activeText::add)
  }
  val tailStart = if (initialHeading != null) firstContent + 1 else summaryEnd + 1
  val details = mutableListOf<String>()
  var foundPart = initialHeading != null
  (tailStart until lines.size).forEach { index ->
    val heading = headings[index]
    if (!foundPart && heading != null) {
      val detailText = details.joinToString("\n").trim()
      if (detailText.isNotEmpty()) blocks += "Details" to detailText
      foundPart = true
    }
    if (foundPart) visitLine(index) else details += lines[index]
  }
  if (!foundPart) {
    val detailText = details.joinToString("\n").trim()
    if (detailText.isNotEmpty()) blocks += "Details" to detailText
  }
  flush()
  val parts = blocks.map { (title, body) ->
    val preview = body.lineSequence().firstOrNull(String::isNotBlank).orEmpty().replace(markdownEmphasis) { match ->
      match.groupValues.drop(1).first(String::isNotEmpty)
    }
    ValidationReportPart(
      title, body, flaggedTitle.containsMatchIn(title), openTitle.containsMatchIn(title),
      body.lineSequence().count { line -> line.firstOrNull()?.isWhitespace() != true && listItem.containsMatchIn(line) }, preview,
    )
  }
  return ValidationReportShape(summary, parts)
}

private fun findHeading(line: String): MatchResult? = partStart.find(line)

private fun partTitle(match: MatchResult): String = match.groups[1]?.value?.removeSuffix(".") ?: match.groups[2]!!.value

internal fun firstSentence(text: String): Pair<String, String?> {
  val sentenceEnd = Regex("[.!?](?:\\*+|_+|`+)*(?=\\s|$)").find(text)
  if (sentenceEnd == null) return text.trim() to null
  val end = sentenceEnd.range.last + 1
  return text.take(end).trim() to text.drop(end).trim().ifBlank { null }
}
