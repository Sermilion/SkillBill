package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaExpectedIdentity

internal const val QA_TEST_HEAD = "1111111111111111111111111111111111111111"
internal const val QA_TEST_TREE = "2222222222222222222222222222222222222222222222222222222222222222"

internal const val QA_TEST_QA_ROOT = "/home/qa/.skill-bill/qa/app-0123456789abcdef"

internal fun qaTestEvidencePrefix(invocationId: String): String = "$QA_TEST_QA_ROOT/$invocationId/"

internal fun qaTestExpected(invocationId: String = "phr-qa-test"): QaExpectedIdentity =
  QaExpectedIdentity(QA_TEST_HEAD, QA_TEST_TREE, qaTestEvidencePrefix(invocationId))

internal fun qaTestReport(
  verdict: QaVerdict,
  expected: QaExpectedIdentity = qaTestExpected(),
  findingIds: List<String> = if (verdict == QaVerdict.FINDINGS_OPEN) listOf("F-1") else emptyList(),
): String =
  buildString {
    appendLine("Verdict: ${verdict.wireValue}")
    appendLine()
    appendLine("## Summary")
    appendLine(
      when (verdict) {
        QaVerdict.PASSED -> "The login flow works on the connected device."
        QaVerdict.FINDINGS_OPEN -> "The login flow shows defects."
        QaVerdict.SKIPPED -> "The only attached device is offline."
        QaVerdict.INCOMPLETE -> "The device was lost mid-run."
      },
    )
    findingIds.forEach { id ->
      appendLine()
      appendLine("### $id Home screen stays blank")
      appendLine("Steps: open the app, log in.")
      appendLine("Expected: the home screen shows the user name.")
      appendLine("Observed: the home screen stays blank.")
      appendLine("Evidence: ${expected.evidencePrefix}home-$id.png")
    }
  }
