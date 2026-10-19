package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.receipt.QaFinding
import skillbill.engine.featuretask.qa.receipt.QaFlow
import skillbill.engine.featuretask.qa.receipt.QaFlowStatus
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.ports.idestatus.model.StandaloneQaOutcome

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
    skipReason?.let { "Skip reason: ${it.wireValue}${reasonSuffix()}" },
    incompleteReason?.let { "Incomplete reason: ${it.wireValue}${reasonSuffix()}" },
    "Cleanup: ${cleanup.outcome.wireValue}${cleanupSuffix()}",
  )
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
