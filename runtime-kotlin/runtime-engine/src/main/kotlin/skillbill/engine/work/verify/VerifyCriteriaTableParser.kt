package skillbill.engine.work.verify

import skillbill.ports.idestatus.model.VerifyCriterionGrade
import skillbill.ports.idestatus.model.VerifyGradeStatus

internal object VerifyCriteriaTableParser {
  private val NUMBER = Regex("""^#?(\d+)[.)]?(?:\s+(.+))?$""")
  private val CELL_SEPARATOR = Regex("""(?<!\\)\|""")

  fun grades(lines: List<String>): List<VerifyCriterionGrade> {
    val grades = mutableListOf<VerifyCriterionGrade>()
    var columns: Columns? = null
    lines.forEach { line ->
      if (!line.trim().startsWith('|')) {
        columns = null
      } else {
        val cells = line.trim().removePrefix("|").removeSuffix("|").split(CELL_SEPARATOR).map(String::trim)
        val header = tableColumns(cells)
        if (header != null) {
          columns = header
        } else {
          columns?.let { row(cells, it) }?.let(grades::add)
        }
      }
    }
    return grades
  }

  private fun tableColumns(cells: List<String>): Columns? {
    val headings = cells.map { it.replace("**", "").lowercase() }
    val status = headings.indexOf("status").takeIf { it >= 0 } ?: return null
    val criterion = headings.indexOf("criterion").takeIf { it >= 0 }
    val number =
      headings.indexOfFirst { it == "#" || it == "ac" || it == "id" }.takeIf { it >= 0 }
        ?: criterion ?: return null
    return Columns(
      number,
      criterion,
      status,
      headings.indexOfFirst { it.startsWith("evidence") }.takeIf { it >= 0 },
      headings.indexOf("missing").takeIf { it >= 0 },
      headings.indexOf("not found").takeIf { it >= 0 },
    )
  }

  private fun row(
    cells: List<String>,
    columns: Columns,
  ): VerifyCriterionGrade? {
    val numbered = NUMBER.matchEntire(cells.getOrNull(columns.number).orEmpty().replace("**", "")) ?: return null
    val number = numbered.groupValues[1].toIntOrNull() ?: return null
    val token = cells.getOrNull(columns.status)?.replace("**", "")?.trim()?.removeSurrounding("[", "]")
    val status = VerifyGradeStatus.entries.firstOrNull { it.name.equals(token, ignoreCase = true) } ?: return null

    fun cell(index: Int?): String? = index?.let(cells::getOrNull)?.takeIf(String::isNotBlank)
    val evidence = cell(columns.evidence)
    val missing = cell(columns.missing) ?: evidence?.takeIf { status == VerifyGradeStatus.PARTIAL }
    val notFound = cell(columns.notFound) ?: evidence?.takeIf { status == VerifyGradeStatus.FAIL }
    return VerifyCriterionGrade(
      number,
      status,
      cell(columns.criterion)?.takeUnless { columns.criterion == columns.number } ?: numbered.groupValues[2],
      listOfNotNull(evidence),
      missing,
      notFound,
      VerifyReceiptLocations.of(evidence, missing, notFound),
    )
  }

  private data class Columns(
    val number: Int,
    val criterion: Int?,
    val status: Int,
    val evidence: Int?,
    val missing: Int?,
    val notFound: Int?,
  )
}
