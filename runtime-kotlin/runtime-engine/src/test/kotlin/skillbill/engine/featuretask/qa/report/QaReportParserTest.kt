package skillbill.engine.featuretask.qa.report

import skillbill.scaffold.model.QaTargetKind
import skillbill.workflow.taskruntime.model.qa.QaAssertionOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunAssertion
import skillbill.workflow.taskruntime.model.qa.QaRunTemporaryEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaReportParserTest {
  private val prefix = qaTestEvidencePrefix("parser")

  @Test
  fun `out-of-scope mobile targets do not turn a desktop pass into partial coverage`() {
    val report =
      QaReportParser.parse(
        """
        |Verdict: passed
        |## Coverage
        |- desktop: passed
        |- android: skipped [reason: empty_scope]
        |- ios: skipped [reason: empty_scope]
        |## Assertions
        |- Desktop window opened at the required size.
        |## Isolation
        |- Disposable app data.
        """.trimMargin(),
        prefix,
        declaredKinds = setOf(QaTargetKind("desktop"), QaTargetKind("android"), QaTargetKind("ios")),
      )

    assertEquals(QaVerdict.PASSED, report.verdict)
    assertTrue(report.coverageWarnings.isEmpty())
    assertFalse(report.coverage.any { it.isPartial })
  }

  @Test
  fun `a pass with every declared target out of scope remains unverified`() {
    val report =
      QaReportParser.parse(
        """
        |Verdict: passed
        |## Coverage
        |- android: skipped [reason: empty_scope]
        |## Assertions
        |- Desktop window opened.
        |## Isolation
        |- Disposable app data.
        """.trimMargin(),
        prefix,
        declaredKinds = setOf(QaTargetKind("android")),
      )

    assertTrue(report.coverageWarnings.any { it.contains("every declared target is out of scope") })
  }

  @Test
  fun `an oversized report with a cleanup heading settles with its full text and no finding`() {
    val text =
      buildString {
        appendLine("Verdict: passed")
        appendLine()
        appendLine("## Assertions")
        appendLine("- [passed] login: home shows the user name.")
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

  @Test
  fun `tagged assertions parse into outcome title detail and evidence`() {
    val report =
      QaReportParser.parse(
        """
        |Verdict: findings_open
        |## Assertions
        |- [PASSED] `a: b` login: home shows
        |  the user name. Evidence: ${prefix}home.png
        |- [failed] Save
        |- [not_exercised] Offline: airplane mode failed to engage.
        |### QA-1 Save fails
        |Steps: tap save.
        """.trimMargin(),
        prefix,
      )

    assertEquals(
      listOf(
        QaRunAssertion(
          QaAssertionOutcome.PASSED,
          "`a: b` login",
          "home shows the user name.",
          listOf("${prefix}home.png"),
        ),
        QaRunAssertion(QaAssertionOutcome.FAILED, "Save", "", emptyList()),
        QaRunAssertion(QaAssertionOutcome.NOT_EXERCISED, "Offline", "airplane mode failed to engage.", emptyList()),
      ),
      report.assertions,
    )
    assertTrue(report.warnings.isEmpty())
  }

  @Test
  fun `an assertion with evidence but no detail keeps a clean title and a blank title counts as untagged`() {
    val clean =
      QaReportParser.parse("Verdict: passed\n## Assertions\n- [passed] Login: Evidence: ${prefix}home.png\n", prefix)
    val blank = QaReportParser.parse("Verdict: passed\n## Assertions\n- [passed]: it worked\n", prefix)

    assertEquals(
      listOf(QaRunAssertion(QaAssertionOutcome.PASSED, "Login", "", listOf("${prefix}home.png"))),
      clean.assertions,
    )
    assertTrue(blank.assertions.isEmpty())
    assertTrue("QA report has untagged assertions; its checks are not counted" in blank.warnings)
  }

  @Test
  fun `one untagged bullet discards the assertions with a warning outside coverage warnings`() {
    val message = "QA report has untagged assertions; its checks are not counted"
    val report =
      QaReportParser.parse(
        "Verdict: passed\n## Assertions\n- [passed] One: ok\n1. Two was fine\n- [passed] Three: ok\n",
        prefix,
      )

    assertEquals(QaVerdict.PASSED, report.verdict)
    assertTrue(report.assertions.isEmpty())
    assertTrue(message in report.warnings)
    assertFalse(message in report.coverageWarnings)
  }

  @Test
  fun `a failed tag with no finding warns outside coverage warnings`() {
    val message = "QA report tags a failed assertion but lists no finding"
    val report = QaReportParser.parse("Verdict: findings_open\n## Assertions\n- [failed] Save: broke\n", prefix)

    assertTrue(message in report.warnings)
    assertFalse(message in report.coverageWarnings)
  }

  @Test
  fun `tag lines are not verdict words`() {
    val report = QaReportParser.parse("## Assertions\n- [passed] Login: ok\n- [failed] Findings row: broke\n", prefix)

    assertEquals(QaVerdict.INCOMPLETE, report.verdict)
  }

  @Test
  fun `finding labels fill fields without evidence paths and an unlabelled section yields nulls`() {
    val labelled =
      QaReportParser.parse(
        """
        |Verdict: findings_open
        |### QA-1 Save fails
        |- **Steps:** open the form
        |  then tap save.
        |**Expected**: the form closes. Evidence: ${prefix}expected.png
        |observed: it stays open.
        |Evidence: ${prefix}form.png
        |### QA-2 Other
        |It broke somehow.
        """.trimMargin(),
        prefix,
      )

    val first = labelled.findings.first()
    assertEquals("open the form then tap save.", first.steps)
    assertEquals("the form closes.", first.expected)
    assertEquals("it stays open.", first.observed)
    assertEquals(listOf("${prefix}expected.png", "${prefix}form.png"), first.evidencePaths)
    val second = labelled.findings.last()
    assertNull(second.steps)
    assertNull(second.expected)
    assertNull(second.observed)
  }

  @Test
  fun `temporary edits count only tagged edits as left behind and None means no edits`() {
    val none = QaReportParser.parse("Verdict: passed\n## Temporary edits\nNone.\n", prefix)
    val edits =
      QaReportParser.parse(
        """
        |Verdict: passed
        |## Temporary edits
        |- `src/App.kt`: forced an error
        |- [NOT RESTORED] `.env`: changed the port
        |- build.gradle: shortened a timeout
        |- changed a mock
        """.trimMargin(),
        prefix,
      )

    assertTrue(none.temporaryEdits.isEmpty())
    assertEquals(
      listOf(
        QaRunTemporaryEdit("src/App.kt", "forced an error", false),
        QaRunTemporaryEdit(".env", "changed the port", true),
        QaRunTemporaryEdit("build.gradle", "shortened a timeout", false),
        QaRunTemporaryEdit(null, "changed a mock", false),
      ),
      edits.temporaryEdits,
    )
  }

  private companion object {
    const val OVERSIZED_REPORT_LENGTH = 300_001
  }
}
