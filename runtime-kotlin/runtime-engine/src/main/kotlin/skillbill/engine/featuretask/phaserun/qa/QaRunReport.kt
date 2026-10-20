package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.receipt.QaAssertion
import skillbill.engine.featuretask.qa.receipt.QaAssertionOutcome
import skillbill.engine.featuretask.qa.receipt.QaFinding
import skillbill.engine.featuretask.qa.receipt.QaFlow
import skillbill.engine.featuretask.qa.receipt.QaFlowStatus
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.ports.idestatus.model.StandaloneQaCheck
import skillbill.ports.idestatus.model.StandaloneQaCriterion
import skillbill.ports.idestatus.model.StandaloneQaCriterionFinding
import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport

internal fun QaReceipt.qaOutcome(): StandaloneQaOutcome =
  when (verdict) {
    QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
    QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
    QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
    QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
  }

internal fun QaReceipt.targetReport(): List<String> {
  val exercised = flows.filter { it.status == QaFlowStatus.EXERCISED }
  val untested = flows.filter { it.status == QaFlowStatus.UNTESTED }
  return listOfNotNull(
    "Verdict: ${verdict.wireValue}",
    target?.let { "Target: ${it.platform.wireValue} ${it.kind.wireValue} (${it.selection.wireValue})" },
    "Exercised flows: ${exercised.joinToString(", ", transform = QaFlow::name).ifEmpty { NONE }}",
    "Untested flows: ${untested.joinToString(", ", transform = ::untestedLine).ifEmpty { NONE }}",
    "Findings: ${findings.joinToString(", ", transform = QaFinding::id).ifEmpty { NONE }}",
  ) + criteriaLines(qaReport()) +
    listOfNotNull(
      skipReason?.let { "Skip reason: ${it.wireValue}${reasonSuffix()}" },
      incompleteReason?.let { "Incomplete reason: ${it.wireValue}${reasonSuffix()}" },
      "Cleanup: ${cleanup.outcome.wireValue}${cleanupSuffix()}",
    )
}

internal fun QaReceipt.qaReport(): StandaloneQaReport? {
  val ids =
    (assertions.flatMap(QaAssertion::criteria) + findings.flatMap(QaFinding::affectedCriteria))
      .distinct()
      .sortedWith(CRITERION_ORDER)
  val unmapped = assertions.any { it.criteria.isEmpty() } || findings.any { it.affectedCriteria.isEmpty() }
  val criteria = ids.map { criterion(it) } + listOfNotNull(criterion(null).takeIf { unmapped })
  return criteria.takeIf(List<*>::isNotEmpty)?.let(::StandaloneQaReport)
}

private fun QaReceipt.criterion(id: String?): StandaloneQaCriterion {
  val checks = assertions.filter { it.criteria.coversCriterion(id) }
  val failing = findings.filter { it.affectedCriteria.coversCriterion(id) }
  val passed = failing.isEmpty() && checks.all { it.outcome == QaAssertionOutcome.PASS }
  return StandaloneQaCriterion(
    id = id,
    status = if (passed) StandaloneQaCriterionStatus.PASSED else StandaloneQaCriterionStatus.FAILED,
    checks = checks.map { StandaloneQaCheck(it.description, it.outcome == QaAssertionOutcome.PASS) },
    findings = failing.map { StandaloneQaCriterionFinding(it.id, it.steps, it.expected, it.observed) },
  )
}

private fun List<String>.coversCriterion(id: String?): Boolean = if (id == null) isEmpty() else id in this

private fun criteriaLines(report: StandaloneQaReport?): List<String> {
  if (report == null) return emptyList()
  return listOf("Criteria: ${report.passed.size} passed, ${report.failed.size} failed") +
    report.criteria.flatMap { criterion ->
      val checks = if (criterion.checks.size == 1) "1 check" else "${criterion.checks.size} checks"
      listOf("  ${criterion.id ?: UNMAPPED}: ${criterion.status.wireValue} ($checks)") +
        criterion.findings.flatMap { finding ->
          listOf(
            "    ${finding.id}",
            "      Expected: ${finding.expected}",
            "      Observed: ${finding.observed}",
          )
        }
    }
}

private fun untestedLine(flow: QaFlow): String = flow.untestedReason?.let { "${flow.name} ($it)" } ?: flow.name

private fun QaReceipt.reasonSuffix(): String = reasonDetail?.let { " - $it" }.orEmpty()

private fun QaReceipt.cleanupSuffix(): String =
  cleanup.retainedResources
    .takeIf(List<*>::isNotEmpty)
    ?.joinToString(", ", prefix = ", retained ") { "${it.kind} ${it.identity}" }
    ?.plus(cleanup.guidance?.let { ". $it" }.orEmpty())
    .orEmpty()

private const val NONE = "none"

private val CRITERION_ORDER: Comparator<String> =
  compareBy({ it.trimEnd(Char::isDigit) }, { it.takeLastWhile(Char::isDigit).toIntOrNull() ?: -1 }, { it })

private const val UNMAPPED = "Not linked to a criterion"
