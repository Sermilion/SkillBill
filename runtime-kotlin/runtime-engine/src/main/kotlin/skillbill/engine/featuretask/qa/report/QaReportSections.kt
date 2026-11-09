package skillbill.engine.featuretask.qa.report

import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunScreenshot
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class QaParsedAssertions(
  val assertions: List<QaRunAssertion>,
  val hasUntagged: Boolean,
)

internal data class QaFindingFields(
  val observed: String?,
  val expected: String?,
  val steps: String?,
)

internal data class QaParsedScreenshotInventory(
  val screenshots: List<QaRunScreenshot>,
  val rejected: Int,
)

internal object QaReportSections {
  private const val SCREENSHOT_FIELD_COUNT = 4
  private const val MAX_SCREENSHOT_LABEL_LENGTH = 160
  private val IMAGE_FORMATS = setOf("png", "jpeg", "jpg")
  private const val LABEL_STEPS = "steps"
  private const val LABEL_EXPECTED = "expected"
  private const val LABEL_OBSERVED = "observed"
  private const val TITLE_SEPARATOR = ": "

  private val PATH_TERMINATORS = charArrayOf('.', ':', ';', ',')
  private val BULLET = Regex("^(?:[-*+]|\\d+\\.)(?:\\s+(.*))?$")
  private val ASSERTION_TAG = Regex("^\\[(passed|failed|not_exercised)]\\s*(.*)$", RegexOption.IGNORE_CASE)
  private val EVIDENCE_CLAUSE = Regex("(?:^|(?<=\\s))evidence:", RegexOption.IGNORE_CASE)
  private val FINDING_LABEL =
    Regex(
      "^\\s*(?:[-*]\\s+)?(?:\\*\\*)?(steps|expected|observed|evidence)(?:\\*\\*)?\\s*:(?:\\*\\*)?\\s*(.*)$",
      RegexOption.IGNORE_CASE,
    )
  private val NOT_RESTORED_TAG = Regex("^\\[not restored]\\s*", RegexOption.IGNORE_CASE)
  private val BACKTICKED = Regex("`([^`]+)`")
  private val NO_EDITS = Regex("^none\\.?$", RegexOption.IGNORE_CASE)

  fun assertions(
    lines: List<String>,
    evidencePrefix: String,
  ): QaParsedAssertions {
    val parsed = bulletsOf(lines).map { bullet -> assertionOf(bullet, evidencePrefix) }
    return if (parsed.any { it == null }) {
      QaParsedAssertions(emptyList(), hasUntagged = true)
    } else {
      QaParsedAssertions(parsed.filterNotNull(), hasUntagged = false)
    }
  }

  fun isTaggedAssertionLine(line: String): Boolean = ASSERTION_TAG.matches(withoutListMarker(line.trim()))

  fun findingFields(body: List<String>): QaFindingFields {
    val values = mutableMapOf<String, MutableList<String>>()
    var current: MutableList<String>? = null
    body.filter(String::isNotBlank).forEach { line ->
      val label = FINDING_LABEL.matchEntire(line)
      if (label == null) {
        current?.add(line.trim())
      } else {
        current = values.getOrPut(label.groupValues[1].lowercase()) { mutableListOf() }
        current.add(label.groupValues[2].trim())
      }
    }

    fun value(name: String): String? =
      values[name]?.joinToString(" ")?.trim()?.let(::withoutEvidenceClause)?.takeIf(String::isNotBlank)
    return QaFindingFields(value(LABEL_OBSERVED), value(LABEL_EXPECTED), value(LABEL_STEPS))
  }

  fun temporaryEdits(lines: List<String>): List<QaRunTemporaryEdit> {
    val entries = lines.map { line -> withoutListMarker(line.trim()) }.filter(String::isNotBlank)
    if (entries.singleOrNull()?.let(NO_EDITS::matches) == true) return emptyList()
    return entries.map(::temporaryEditOf)
  }

  fun evidencePathsOf(
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

  fun screenshots(
    lines: List<String>,
    evidencePrefix: String,
  ): QaParsedScreenshotInventory {
    val evidenceRoot =
      absolutePath(evidencePrefix)
        ?: return QaParsedScreenshotInventory(emptyList(), lines.size)
    val screenshots = lines.mapNotNull { raw -> screenshotOf(raw, evidenceRoot) }
    return QaParsedScreenshotInventory(screenshots, lines.size - screenshots.size)
  }

  private fun screenshotOf(
    raw: String,
    evidenceRoot: Path,
  ): QaRunScreenshot? {
    val fields = raw.trim().removePrefix("-").trim().split(" | ", limit = SCREENSHOT_FIELD_COUNT)
    if (fields.size != SCREENSHOT_FIELD_COUNT) return null
    val recordedAt = fields[0]
    val format = fields[1].lowercase()
    val label = fields[2]
    val path = fields.last()
    val imagePath = absolutePath(path) ?: return null
    if (label.isBlank() || label.length > MAX_SCREENSHOT_LABEL_LENGTH ||
      !imagePath.startsWith(
        evidenceRoot,
      )
    ) {
      return null
    }
    val instant = parseInstant(recordedAt)
    val extension = path.substringAfterLast('.', "").lowercase()
    return if (instant != null && format in IMAGE_FORMATS && extension in IMAGE_FORMATS) {
      QaRunScreenshot(label, path, instant.toString(), if (format == "jpg") "jpeg" else format)
    } else {
      null
    }
  }

  private fun absolutePath(path: String): Path? =
    try {
      Path.of(path).normalize().takeIf(Path::isAbsolute)
    } catch (_: InvalidPathException) {
      null
    }

  private fun parseInstant(value: String): Instant? =
    try {
      Instant.parse(value)
    } catch (_: DateTimeParseException) {
      null
    }

  private fun bulletsOf(lines: List<String>): List<String> {
    val bullets = mutableListOf<String>()
    lines.filter(String::isNotBlank).forEach { line ->
      val bullet = BULLET.matchEntire(line.trimEnd())
      when {
        bullet != null -> bullets += bullet.groupValues[1].trim()
        line.first().isWhitespace() && bullets.isNotEmpty() -> bullets[bullets.lastIndex] += " ${line.trim()}"
      }
    }
    return bullets.filter(String::isNotBlank)
  }

  private fun assertionOf(
    bullet: String,
    evidencePrefix: String,
  ): QaRunAssertion? {
    val tag = ASSERTION_TAG.matchEntire(bullet.trim()) ?: return null
    val outcome = QaAssertionOutcome.fromWire(tag.groupValues[1].lowercase()) ?: return null
    val text = tag.groupValues[2].trim()
    val body = withoutEvidenceClause(text)
    val separator = titleSeparatorIndex(body)
    val title = if (separator < 0) body.removeSuffix(":").trimEnd() else body.substring(0, separator).trim()
    if (title.isBlank()) return null
    val detail = if (separator < 0) "" else body.substring(separator + TITLE_SEPARATOR.length).trim()
    return QaRunAssertion(outcome, title, detail, evidencePathsOf(text, evidencePrefix))
  }

  private fun titleSeparatorIndex(text: String): Int {
    var insideBackticks = false
    text.forEachIndexed { index, character ->
      if (character == '`') insideBackticks = !insideBackticks
      if (!insideBackticks && text.startsWith(TITLE_SEPARATOR, index)) return index
    }
    return -1
  }

  private fun withoutEvidenceClause(text: String): String =
    EVIDENCE_CLAUSE.findAll(text).lastOrNull()
      ?.let { clause -> text.substring(0, clause.range.first).trimEnd() }
      ?: text

  private fun withoutListMarker(line: String): String =
    BULLET.matchEntire(line)?.let { bullet -> bullet.groupValues[1].trim() } ?: line

  private fun temporaryEditOf(entry: String): QaRunTemporaryEdit {
    val text = NOT_RESTORED_TAG.replaceFirst(entry, "")
    val backticked = BACKTICKED.find(text)
    val colon = text.indexOf(':')
    val (path, remainder) =
      when {
        backticked != null -> backticked.groupValues[1] to text.removeRange(backticked.range)
        colon >= 0 -> text.substring(0, colon).trim().takeIf(String::isNotBlank) to text.substring(colon + 1)
        else -> null to text
      }
    return QaRunTemporaryEdit(path, remainder.trim().trimStart(':', ' '), NOT_RESTORED_TAG.containsMatchIn(entry))
  }
}
