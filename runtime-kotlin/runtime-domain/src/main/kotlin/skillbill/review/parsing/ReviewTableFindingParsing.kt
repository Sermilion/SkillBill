package skillbill.review.parsing

import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.review.attribution.normalizeReviewIssueCategory
import skillbill.review.model.ImportedFinding

internal fun isTableHeaderLine(line: String): Boolean {
  val cells = line.split("|").map { it.trim().lowercase() }
  return cells.any { it == "severity" || it == "sev" } &&
    cells.any { it == "#" || it == "id" || it == "no" || it == "no." }
}

internal fun buildColumnMap(headerCells: List<String>): Map<String, Int> =
  buildMap {
    headerCells.forEachIndexed { index, name ->
      when (name.lowercase()) {
        "#", "id", "no", "no." -> put("number", index)
        "severity", "sev" -> put("severity", index)
        "confidence", "conf" -> put("confidence", index)
        "category", "issue category", "issue_category" -> put(ReviewFindingPayloadKeys.ISSUE_CATEGORY, index)
        "file", "filename" -> put("file", index)
        "line", "lines", "line(s)" -> put("lines", index)
        "finding", "description", "issue" -> put("description", index)
      }
    }
  }

internal fun parseTableBody(
  lines: List<String>,
  columnMap: Map<String, Int>,
): List<ImportedFinding> {
  val findings = mutableListOf<ImportedFinding>()
  for (line in lines) {
    val stripped = line.trim()
    if (shouldStopParsingTable(stripped, findings.isNotEmpty())) {
      break
    }
    parseTableFindingLine(stripped, columnMap)?.let(findings::add)
  }
  return findings
}

private fun shouldStopParsingTable(
  stripped: String,
  hasFindings: Boolean,
): Boolean = hasFindings && stripped.isNotEmpty() && !stripped.startsWith("|")

private fun parseTableFindingLine(
  stripped: String,
  columnMap: Map<String, Int>,
): ImportedFinding? {
  if (stripped.isEmpty() || !stripped.startsWith("|")) return null
  val cells = stripped.split("|").map { it.trim() }
  val number =
    if (isTableSeparator(cells)) {
      null
    } else {
      tableCell(cells, columnMap.getValue("number")).toIntOrNull()
    }
  val description = tableCell(cells, columnMap.getValue("description"))
  return if (number == null || description.isEmpty()) {
    null
  } else {
    val confidenceValue = tableCell(cells, columnMap["confidence"])
    ImportedFinding(
      findingId = "F-%03d".format(number),
      severity = normalizeSeverity(tableCell(cells, columnMap.getValue("severity"))),
      confidence = normalizeConfidence(confidenceValue),
      issueCategory =
        normalizeReviewIssueCategory(
          tableCell(cells, columnMap[ReviewFindingPayloadKeys.ISSUE_CATEGORY]),
        ),
      location = buildLocation(tableCell(cells, columnMap["file"]), tableCell(cells, columnMap["lines"])),
      description = description,
      findingText = stripped,
    )
  }
}

private fun isTableSeparator(cells: List<String>): Boolean =
  cells.filter(String::isNotEmpty).all { cell -> cell.all { it == '-' || it == ':' } }

private fun tableCell(
  cells: List<String>,
  index: Int?,
): String =
  if (index == null || index >= cells.size) {
    ""
  } else {
    cells[index]
  }

private fun buildLocation(
  fileValue: String,
  linesValue: String,
): String =
  when {
    fileValue.isNotEmpty() && linesValue.isNotEmpty() && linesValue != "—" -> "$fileValue:$linesValue"
    fileValue.isNotEmpty() -> fileValue
    else -> ""
  }

internal fun normalizeSeverity(rawValue: String): String = severityAliases[rawValue.trim().lowercase()] ?: "Minor"

internal fun normalizeConfidence(confidenceValue: String): String =
  if (confidenceValue.trim().lowercase() in setOf("high", "medium", "low")) {
    confidenceValue.trim().lowercase().replaceFirstChar(Char::uppercaseChar)
  } else {
    "Medium"
  }
