package skillbill.engine.featuretask.phaserun.qa

import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.ports.idestatus.model.StandaloneQaOutcome

public fun QaReport.qaOutcome(): StandaloneQaOutcome =
  when (verdict) {
    QaVerdict.PASSED -> StandaloneQaOutcome.PASSED
    QaVerdict.FINDINGS_OPEN -> StandaloneQaOutcome.FINDINGS
    QaVerdict.SKIPPED -> StandaloneQaOutcome.SKIPPED
    QaVerdict.INCOMPLETE -> StandaloneQaOutcome.INCOMPLETE
  }

public fun QaReport.summary(): String {
  val verdictLine = "QA verdict: ${verdict.wireValue}${reason?.let { ": $it" }.orEmpty()}"
  if (coverage.isEmpty()) return verdictLine
  val targets =
    coverage.joinToString(", ") { line ->
      "${line.kind.wireValue} ${line.verdict.wireValue}${line.reason?.let { " ($it)" }.orEmpty()}" +
        line.backend?.let { " backend ${it.wireValue}" }.orEmpty()
    }
  return "$verdictLine; coverage: $targets"
}

public fun QaReport.targetReport(): List<String> =
  warnings.map { "Warning: $it" } +
    listOfNotNull("Verdict: ${verdict.wireValue}${reason?.let { ": $it" }.orEmpty()}", text, cleanup)
