package skillbill.engine.featuretask.qa.report

import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.artifact.asQaRepairStateWireMap
import skillbill.workflow.taskruntime.artifact.toQaRepairState
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaFindingSettlementTest {
  private val workspaces = InMemoryQaWorkspaces()
  private val warnings = mutableListOf<String>()
  private val diagnostics =
    object : RuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }

      override fun error(
        message: String,
        error: Throwable?,
      ) = Unit
    }
  private val settlement = QaReportSettlement(workspaces, diagnostics)
  private val original = workspaces.open(Path.of("/tmp/qa-findings"), "original")
  private val current = workspaces.open(Path.of("/tmp/qa-findings"), "current")
  private val qa =
    QaExecutionContext(
      current,
      qaTestExpected("current").copy(treeIdentity = "4".repeat(64)),
      QaExecutionTarget.Skipped(QaSkipReason.UNSUPPORTED_TARGET, "unused target guidance"),
    )
  private val previous =
    QaRepairState(
      subtaskId = 1,
      attempts = listOf(QaRepairAttempt(1, QA_TEST_TREE, listOf("F-1"), "repair-1", QaRepairAttemptOutcome.COMPLETED)),
      openFindings = listOf(QaOpenFinding("F-1", emptyList(), listOf(original.reportPath.toString()))),
      lastReceiptTreeIdentity = QA_TEST_TREE,
    )

  @Test
  fun `a passed report closes every open finding and records the tested tree`() {
    val report = parse(QaVerdict.PASSED)

    val result = settlement.observe(previous, report, qa)

    assertTrue(result.openFindings.isEmpty())
    assertEquals(qa.expected.treeIdentity, result.lastReceiptTreeIdentity)
    assertEquals(previous.attempts, result.attempts)
  }

  @Test
  fun `a findings report drops findings it no longer lists and opens the new ones against its report path`() {
    val report = parse(QaVerdict.FINDINGS_OPEN, findingIds = listOf("F-2"))

    val result = settlement.observe(previous, report, qa)

    assertEquals(listOf(QaOpenFinding("F-2", emptyList(), listOf(current.reportPath.toString()))), result.openFindings)
    assertEquals(previous.attempts, result.attempts)
  }

  @Test
  fun `a reproduced finding keeps its earlier report reference and survives persistence`() {
    val report = parse(QaVerdict.FINDINGS_OPEN, findingIds = listOf("F-1"))

    val result = settlement.observe(previous, report, qa)
    val resumed = result.asQaRepairStateWireMap().toQaRepairState()

    assertEquals(
      listOf(original.reportPath.toString(), current.reportPath.toString()),
      resumed.openFindings.single().receiptRefs,
    )
  }

  @Test
  fun `skipped and incomplete reports keep unresolved findings open`() {
    for (verdict in listOf(QaVerdict.SKIPPED, QaVerdict.INCOMPLETE)) {
      val result = settlement.observe(previous, parse(verdict), qa)

      assertEquals(previous.openFindings, result.openFindings, verdict.name)
      assertEquals(previous.attempts, result.attempts, verdict.name)
    }
  }

  @Test
  fun `a passed report that lists no assertions keeps unresolved findings open`() {
    val unverified = QaReportParser.parse("Verdict: passed\n\nThe app looked fine.", qa.expected.evidencePrefix)

    val result = settlement.observe(previous, unverified, qa)

    assertEquals(previous.openFindings, result.openFindings)
  }

  @Test
  fun `a report that never landed keeps unresolved findings open`() {
    val result = settlement.observe(previous, QaReports.missing("QA agent wrote no report.md"), qa)

    assertEquals(previous.openFindings, result.openFindings)
  }

  @Test
  fun `a passed report that still lists a finding heading closes the repair and records the contradiction`() {
    val report = parse(QaVerdict.PASSED, findingIds = listOf("F-1"))

    val result = settlement.observe(previous, report, qa)
    settlement.recordDiagnostics(report)

    assertTrue(result.openFindings.isEmpty())
    assertTrue(warnings.single().contains("seam=qa_report"), warnings.toString())
  }

  @Test
  fun `a missing report settles incomplete without text`() {
    val report = settlement.settle(qa)

    assertEquals(QaVerdict.INCOMPLETE, report.verdict)
    assertEquals(null, report.text)
    assertEquals(null, report.reason)
    assertEquals(listOf("QA agent wrote no report.md"), report.warnings)
  }

  @Test
  fun `a landed report settles from its text with the workspace path as reference`() {
    workspaces.report = qaTestReport(QaVerdict.FINDINGS_OPEN, qa.expected)

    val report = settlement.settle(qa)

    assertEquals(QaVerdict.FINDINGS_OPEN, report.verdict)
    assertEquals(listOf("F-1"), report.openFindingIds)
    assertEquals(current.reportPath.toString(), report.reference)
  }

  private fun parse(
    verdict: QaVerdict,
    findingIds: List<String> = emptyList(),
  ): QaReport =
    QaReportParser.parse(
      qaTestReport(verdict, qa.expected, findingIds),
      qa.expected.evidencePrefix,
      current.reportPath.toString(),
    )
}
