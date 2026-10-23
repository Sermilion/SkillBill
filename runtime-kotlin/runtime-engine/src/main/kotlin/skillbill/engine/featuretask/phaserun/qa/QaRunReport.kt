package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.ports.idestatus.model.StandaloneQaOutcome

internal fun QaReport.qaOutcome(): StandaloneQaOutcome =
  when (verdict) {
    QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
    QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
    QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
    QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
  }

internal fun QaReport.summary(): String = "QA verdict: ${verdict.wireValue}${reason?.let { ": $it" }.orEmpty()}"

internal fun QaReport.targetReport(): List<String> =
  warnings.map { "Warning: $it" } +
    listOfNotNull("Verdict: ${verdict.wireValue}${reason?.let { ": $it" }.orEmpty()}", text, cleanup)
