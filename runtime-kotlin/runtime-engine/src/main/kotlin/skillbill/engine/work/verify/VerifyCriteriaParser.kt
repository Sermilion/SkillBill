package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.ports.idestatus.model.VerifyCompletenessAudit
import skillbill.ports.idestatus.model.VerifyCriterionGrade
import skillbill.ports.idestatus.model.VerifyGradeStatus
import skillbill.ports.idestatus.model.VerifyRunCriterion

internal object VerifyReceiptLocations {
  private val LOC_RE = Regex("""([A-Za-z0-9_.-]+\.(?:kt|kts|swift|ts|tsx|py|java|xml|pbxproj|gradle)):(\d+)""")

  fun of(vararg texts: String?): List<String> =
    texts.filterNotNull().flatMap { text -> LOC_RE.findAll(text).map { it.value } }.distinct()
}

internal object VerifyCriteriaParser {
  private val CRITERION = Regex("""^\s*(\d+)[.)]\s+(.*)$""")
  private val BULLET = Regex("""^\s*[-*•]\s*""")
  private val GRADE = Regex("""^\s*[-*]?\s*\[(PASS|PARTIAL|FAIL)]\s*#(\d+):?\s*(.*)$""")
  private val EVIDENCE = Regex("""^\s*[-*]?\s*(?:\*\*)?Evidence:(?:\*\*)?\s*(.*)$""")
  private val MISSING = Regex("""^\s*[-*]?\s*(?:\*\*)?Missing(?:\*\*)?\s*[—:-]\s*(?:\*\*)?\s*(.*)$""")
  private val NOT_FOUND = Regex("""^\s*[-*]?\s*(?:\*\*)?Not found(?:\*\*)?\s*[—:-]\s*(?:\*\*)?\s*(.*)$""")

  fun criteria(acceptanceCriteria: String): Pair<List<VerifyRunCriterion>, String?> {
    val items = mutableListOf<VerifyRunCriterion>()
    acceptanceCriteria.lines().forEach { line ->
      val match = CRITERION.find(line)
      val number = match?.groupValues?.get(1)?.toIntOrNull()
      when {
        match != null && number != null -> items += VerifyRunCriterion(number, match.groupValues[2].trim())
        line.isNotBlank() && items.isNotEmpty() ->
          items[items.lastIndex] = items.last().let { it.copy(text = "${it.text} ${line.trim()}".trim()) }
      }
    }
    val raw = acceptanceCriteria.trim().takeIf { items.isEmpty() && it.isNotEmpty() }
    return items to raw
  }

  fun bullets(text: String): List<String> =
    text.lines().filter(String::isNotBlank).map { it.replace(BULLET, "").trim() }.filter(String::isNotEmpty)

  fun completeness(receipt: Any?): VerifyCompletenessAudit? {
    if (receipt == null) return null
    val findings = VerifyWorkflow.strings(receipt, VerifyWorkflow.FINDINGS)
    return VerifyCompletenessAudit(
      VerifyWorkflow.string(receipt, VerifyWorkflow.VERDICT_FIELD),
      findings,
      grades(findings),
    )
  }

  private fun grades(lines: List<String>): List<VerifyCriterionGrade> {
    val grades = mutableListOf<VerifyCriterionGrade>()
    lines.flatMap(String::lines).forEach { line ->
      val grade = GRADE.find(line)?.destructured
      val number = grade?.component2()?.toIntOrNull()
      val current = grades.lastOrNull()
      when {
        grade != null && number != null ->
          grades +=
            VerifyCriterionGrade(
              number,
              VerifyGradeStatus.valueOf(grade.component1()),
              grade.component3().trim(),
              emptyList(),
              null,
              null,
              emptyList(),
            )
        current != null -> grades[grades.lastIndex] = detail(current, line)
      }
    }
    val tagged = grades.map { it.copy(locations = VerifyReceiptLocations.of(it.missing, it.notFound)) }
    return (tagged + VerifyCriteriaTableParser.grades(lines.flatMap(String::lines))).distinctBy { it.number }
  }

  private fun detail(
    grade: VerifyCriterionGrade,
    line: String,
  ): VerifyCriterionGrade {
    EVIDENCE.find(line)?.let { match ->
      val evidence =
        match.groupValues[1]
          .split(',')
          .map { it.replace("`", "").trim().removeSuffix(".") }
          .filter(String::isNotEmpty)
      return grade.copy(evidence = grade.evidence + evidence)
    }
    MISSING.find(line)?.let { return grade.copy(missing = match(grade.missing, it)) }
    NOT_FOUND.find(line)?.let { return grade.copy(notFound = match(grade.notFound, it)) }
    return grade
  }

  private fun match(
    previous: String?,
    found: MatchResult,
  ): String = listOfNotNull(previous, found.groupValues[1].trim()).joinToString(" ")
}
