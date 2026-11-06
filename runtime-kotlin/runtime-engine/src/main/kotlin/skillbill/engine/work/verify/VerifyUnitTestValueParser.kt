package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyPromptSections
import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.ports.idestatus.model.VerifyTestValueRow
import skillbill.ports.idestatus.model.VerifyUnitTestValue

internal object VerifyUnitTestValueParser {
  private const val OVERALL = "**Overall verdict:**"
  private const val KEEP = "**Keep:**"
  private const val REWRITE = "**Rewrite:**"
  private const val DELETE = "**Delete:**"
  private const val MISSING = "**Missing high-value cases:**"
  private const val HEADER_CELL = "Test or Area"
  private const val NONE = "none"
  private const val ROW_CELLS = 6
  private const val FILES_CELL = 3
  private const val WHY_CELL = 4
  private const val ACTION_CELL = 5
  private val LIST_LABELS = listOf(KEEP, REWRITE, DELETE, MISSING)
  private val SEPARATOR_CELL = Regex("""^[\s:-]*$""")
  private val LINK_LABEL = Regex("""\[([^\]]+)]\([^)]*\)""")
  private val BULLET = Regex("""^\s*[-*•]\s+""")
  private val BOLD_LABEL = Regex("""^\s*\*\*[^*]+:\*\*""")

  fun parse(receipt: Any?): VerifyUnitTestValue? {
    if (receipt == null) return null
    val verdict = VerifyWorkflow.string(receipt, VerifyWorkflow.VERDICT_FIELD)
    val findings = VerifyWorkflow.strings(receipt, VerifyWorkflow.FINDINGS)
    if (verdict == VerifyWorkflow.SKIPPED) {
      val reason = findings.firstOrNull()?.trim()?.removePrefix(VerifyPromptSections.SKIPPED_PREFIX)?.trim()
      return VerifyUnitTestValue(
        verdict, findings, reason, null, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
      )
    }
    val lines = findings.flatMap(String::lines)
    return VerifyUnitTestValue(
      verdict = verdict,
      findings = findings,
      skipReason = null,
      overall = lines.firstOrNull { OVERALL in it }?.substringAfter(OVERALL)?.trim(),
      summary = lines.map(String::trim).firstOrNull(::plain),
      rows = lines.mapNotNull(::row),
      keep = listed(lines, KEEP),
      rewrite = listed(lines, REWRITE),
      delete = listed(lines, DELETE),
      missingCases = listed(lines, MISSING),
    )
  }

  private fun plain(line: String): Boolean =
    line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("|") && !BOLD_LABEL.containsMatchIn(line) &&
      !BULLET.containsMatchIn(line)

  private fun row(line: String): VerifyTestValueRow? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return null
    val cells = trimmed.removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    val skipped =
      cells.size < ROW_CELLS || cells[0].equals(HEADER_CELL, ignoreCase = true) || cells.all(SEPARATOR_CELL::matches)
    if (skipped) return null
    val filesCell = cells[FILES_CELL]
    val labels = LINK_LABEL.findAll(filesCell).map { it.groupValues[1].trim() }.toList()
    val files = labels.ifEmpty { filesCell.split(',').map { it.replace("`", "").trim() }.filter(String::isNotEmpty) }
    return VerifyTestValueRow(
      area = cells[0],
      verdict = cells[1],
      confidence = cells[2],
      files = files,
      why = cells[WHY_CELL],
      action = cells[ACTION_CELL],
      locations = VerifyReceiptLocations.of(filesCell, cells[WHY_CELL]),
    )
  }

  private fun listed(
    lines: List<String>,
    label: String,
  ): List<String> {
    val start = lines.indexOfFirst { it.trimStart().startsWith(label) }
    if (start < 0) return emptyList()
    val inline =
      lines[start].substringAfter(label).trim().takeUnless { it.isEmpty() || it.trimEnd('.').equals(NONE, true) }
    val following =
      lines
        .drop(start + 1)
        .takeWhile { line -> !ends(line) }
        .filter(BULLET::containsMatchIn)
        .map { it.replace(BULLET, "").trim() }
    return listOfNotNull(inline) + following
  }

  private fun ends(line: String): Boolean {
    val trimmed = line.trimStart()
    return LIST_LABELS.any(trimmed::startsWith) || BOLD_LABEL.containsMatchIn(trimmed) ||
      trimmed.startsWith("#") || trimmed.startsWith("|")
  }
}
