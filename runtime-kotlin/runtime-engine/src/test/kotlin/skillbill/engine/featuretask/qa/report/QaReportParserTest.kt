package skillbill.engine.featuretask.qa.report

import skillbill.scaffold.model.QaTargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaReportParserTest {
  private val prefix = qaTestEvidencePrefix("parser")

  @Test
  fun `an oversized report with a cleanup heading settles with its full text and no finding`() {
    val text =
      buildString {
        appendLine("Verdict: passed")
        appendLine()
        appendLine("## Assertions")
        appendLine("- login: home shows the user name: passed")
        appendLine("## Isolation")
        appendLine("Disposable emulator profile.")
        appendLine()
        appendLine("### Cleanup")
        appendLine("x".repeat(OVERSIZED_REPORT_LENGTH))
      }

    val report = QaReportParser.parse(text, prefix)

    assertEquals(QaVerdict.PASSED, report.verdict)
    assertEquals(text, report.text)
    assertTrue(report.findings.isEmpty())
    assertTrue(report.warnings.isEmpty())
  }

  @Test
  fun `a report that names no verdict is incomplete and keeps its text`() {
    val text = "The agent ran out of budget before concluding anything."

    val report = QaReportParser.parse(text, prefix)

    assertEquals(QaVerdict.INCOMPLETE, report.verdict)
    assertEquals(text, report.text)
    assertTrue(report.openFindingIds.isEmpty())
  }

  @Test
  fun `a passed report that lists a finding heading keeps passed and warns without opening a repair`() {
    val text = qaTestReport(QaVerdict.PASSED, findingIds = listOf("F-1"))

    val report = QaReportParser.parse(text, prefix)

    assertEquals(QaVerdict.PASSED, report.verdict)
    assertEquals(listOf("F-1"), report.findings.map { it.id })
    assertTrue(report.openFindingIds.isEmpty())
    assertEquals(
      listOf("QA report states passed but lists findings F-1; no repair opened"),
      report.warnings,
    )
  }

  @Test
  fun `the first line naming a verdict decides it regardless of case`() {
    val text = "# QA\nRESULT: Findings_Open on the login flow\n\nThe summary later says passed."

    val report = QaReportParser.parse(text, prefix)

    assertEquals(QaVerdict.FINDINGS_OPEN, report.verdict)
  }

  @Test
  fun `a verdict word inside a longer identifier does not count`() {
    val text = "Status: not_passed_yet\nVerdict: skipped"

    val report = QaReportParser.parse(text, prefix)

    assertEquals(QaVerdict.SKIPPED, report.verdict)
  }

  @Test
  fun `finding sections are kept verbatim up to the next heading and carry owned evidence paths only`() {
    val text =
      """
      Verdict: findings_open

      ### F-1 Home screen stays blank
      Observed: blank.
      Evidence: ${prefix}home.png, /tmp/elsewhere/other.png and $prefix../escape.png.

      ### F-2: Crash on logout
      Evidence: ${prefix}logout.png

      ## Cleanup
      Released the device.
      """.trimIndent()

    val report = QaReportParser.parse(text, prefix, "/qa/report.md")

    assertEquals(listOf("F-1", "F-2"), report.findings.map { it.id })
    assertEquals(listOf("Home screen stays blank", "Crash on logout"), report.findings.map { it.title })
    assertEquals(listOf("${prefix}home.png"), report.findings[0].evidencePaths)
    assertEquals(listOf("${prefix}logout.png"), report.findings[1].evidencePaths)
    assertEquals(
      "### F-2: Crash on logout\nEvidence: ${prefix}logout.png\n\n",
      report.findings[1].section,
    )
    assertEquals("/qa/report.md", report.reference)
    assertNull(report.reason)
  }

  @Test
  fun `a heading whose first token has no digit is a section and not a finding`() {
    val text = "Verdict: passed\n\n### Notes on flakiness\nNothing to fix."

    val report = QaReportParser.parse(text, prefix)

    assertTrue(report.findings.isEmpty())
  }

  @Test
  fun `a skipped report carries only a recognized reason token`() {
    val recognized = QaReportParser.parse("Verdict: skipped\nReason: docker_unavailable\n\nNo Docker.", prefix)
    val freeText = QaReportParser.parse("Verdict: skipped\nReason: the laptop was asleep", prefix)

    assertEquals("docker_unavailable", recognized.reason)
    assertNull(freeText.reason)
  }

  @Test
  fun `an unverified or partial pass warns without changing the verdict and does not retest findings`() {
    val text =
      """
      Verdict: passed

      ## Coverage
      - backend: passed
      - web: passed backend: mocked
      - ios: incomplete reason: device_contended
      - not a coverage line
      """.trimIndent()

    val declared = setOf(QaTargetKind("backend"), QaTargetKind("android"))

    val report = QaReportParser.parse(text, prefix, declaredKinds = declared)

    assertEquals(QaVerdict.PASSED, report.verdict)
    assertEquals(listOf("backend", "web", "ios"), report.coverage.map { it.kind.wireValue })
    assertEquals(QaBackendAttribution.MOCKED, report.coverage[1].backend)
    assertEquals(
      listOf(
        "QA report states passed but lists no assertions; the pass is unverified",
        "QA report states passed without isolation evidence",
        "QA report has no coverage for target android",
        "QA covered target ios only partially: incomplete (device_contended)",
        "Target web passed against a mocked backend; backend integration is untested",
      ),
      report.warnings,
    )
    assertEquals(report.warnings, report.coverageWarnings)
    assertFalse(report.retestsFindings)
  }

  private companion object {
    const val OVERSIZED_REPORT_LENGTH = 300_001
  }
}
