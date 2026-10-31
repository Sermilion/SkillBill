package skillbill.engine.work

import skillbill.ports.idestatus.model.StandaloneReviewReport
import skillbill.ports.idestatus.model.StandaloneReviewReportFinding

internal object StandaloneReviewReportReader {
  private val findingLine =
    Regex("""^(?:\[(F-\d+)]\s+)?(blocker|critical|major|minor|nit)\s*\|\s*(.+)$""", RegexOption.IGNORE_CASE)
  private val bulletPrefix = Regex("""^(?:[-*•]\s+)+""")
  private val verdictLine = Regex("""^verdict:\s*(\S+)\s*$""", RegexOption.IGNORE_CASE)
  private val findingsHeading = Regex("""^[#*_\s]*findings[:*_\s]*$""", RegexOption.IGNORE_CASE)
  private val excessBlankLines = Regex("""\n{3,}""")
  private const val NO_FINDINGS = "NO_FINDINGS"
  private const val REF_WIDTH = 3
  private const val ID_GROUP = 1
  private const val SEVERITY_GROUP = 2
  private const val FIELDS_GROUP = 3

  fun read(text: String?): StandaloneReviewReport? {
    if (text.isNullOrBlank()) return null
    var verdict: String? = null
    val findings = mutableListOf<StandaloneReviewReportFinding>()
    val summary = mutableListOf<String>()
    text.lines().forEach { raw ->
      val line = raw.trim().replace(bulletPrefix, "")
      val verdictMatch = verdictLine.matchEntire(line)
      val finding = parseFinding(line, findings.size)
      when {
        verdictMatch != null -> verdict = verdictMatch.groupValues[1].lowercase()
        finding != null -> findings += finding
        line == NO_FINDINGS || findingsHeading.matches(line) -> Unit
        else -> summary += raw.trimEnd()
      }
    }
    val summaryText = summary.joinToString("\n").replace(excessBlankLines, "\n\n").trim().takeIf(String::isNotBlank)
    return StandaloneReviewReport(verdict, summaryText, findings)
  }

  private fun parseFinding(
    line: String,
    index: Int,
  ): StandaloneReviewReportFinding? {
    val match = findingLine.matchEntire(line) ?: return null
    val id = match.groups[ID_GROUP]?.value
    val fields = match.groupValues[FIELDS_GROUP].split('|').map(String::trim)
    val meta = FindingMetaFields()
    val descriptionStart =
      fields.indices.firstOrNull { position -> position == fields.lastIndex || !meta.accept(fields[position]) }
        ?: fields.size
    val description = fields.drop(descriptionStart).joinToString(" | ").trim()
    if (description.isBlank() || (descriptionStart == 0 && id == null)) return null
    return StandaloneReviewReportFinding(
      ref = id ?: "F-${(index + 1).toString().padStart(REF_WIDTH, '0')}",
      severity = match.groupValues[SEVERITY_GROUP].lowercase().replaceFirstChar(Char::uppercase),
      confidence = meta.confidence,
      specialist = meta.specialist,
      location = meta.location,
      description = description,
    )
  }
}

private class FindingMetaFields {
  var confidence: String? = null
  var specialist: String? = null
  private var legacyLocation: String? = null
  private var path: String? = null
  private var line: String? = null

  val location: String?
    get() = legacyLocation ?: path?.let { listOfNotNull(it, line).joinToString(":") }

  fun accept(field: String): Boolean {
    when {
      confidence == null && CONFIDENCE.matches(field) -> confidence = field.lowercase()
      field.startsWith(SPECIALIST) -> specialist = field.removePrefix(SPECIALIST).replace(SPECIALIST_ANNOTATION, "")
      field.startsWith(COMMITS) -> Unit
      field.startsWith(PATH) -> path = field.removePrefix(PATH).trim('"')
      field.startsWith(LINE) -> line = field.removePrefix(LINE)
      location == null && LEGACY_LOCATION.matches(field) -> legacyLocation = field
      specialist == null && location == null && LANE_NAME.matches(field) -> specialist = field
      else -> return false
    }
    return true
  }

  private companion object {
    val CONFIDENCE = Regex("""^(high|medium|low)$""", RegexOption.IGNORE_CASE)
    val LEGACY_LOCATION = Regex("""^\S+:\d+(?:-\d+)?$""")
    val LANE_NAME = Regex("""^[a-z0-9]+(?:-[a-z0-9]+)+$""")
    val SPECIALIST_ANNOTATION = Regex("""\[[^\]]*]$""")
    const val SPECIALIST = "specialist="
    const val COMMITS = "commits="
    const val PATH = "path="
    const val LINE = "line="
  }
}
