package skillbill.engine.featuretask.slot.standalonereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.application.review.model.ParallelReviewLaneStatus
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewCoverageReport
import skillbill.review.model.ReviewLaneReviewDisposition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneReviewReportAdmissionTest {
  @Test
  fun `an incomplete review warning stays visible and cannot present as approved`() {
    val warning = "Review incomplete: could not inspect the diff"
    val outputs =
      listOf(
        "NO_FINDINGS\nverdict: approved\n$warning",
        "$warning\nNO_FINDINGS\nverdict: approved",
        "NO_FINDINGS\n$warning\nverdict: approved",
      )

    outputs.forEach { rawOutput ->
      val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertEquals("changes_requested", report.verdict, rawOutput)
      assertTrue(warning in report.registerOutput, report.registerOutput)
    }
  }

  @Test
  fun `prose that admits a gap or names a severe finding cannot present as approved`() {
    val outputs =
      listOf(
        "I couldn't open the diffs of the agent notes, so they are not covered.",
        "Some files were not reviewed.",
        "- `src/Gate.kt:124` [high, correctness]: the marker list misses too-old Java output.",
        "One high-severity issue in the token refresh path.",
        "Severity: critical. The migration drops the users table.",
      )

    outputs.forEach { prose ->
      val report = StandaloneReviewReportAdmission.admit("$prose\nverdict: approved", emptyResult(), false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertEquals("changes_requested", report.verdict, prose)
    }
  }

  @Test
  fun `clean prose approvals stay approved`() {
    val outputs =
      listOf(
        "I reviewed all six files and could not find any issues.",
        "No high-severity issues found; the change is small and well tested.",
        "- `src/Gate.kt:124` [low, style]: consider a shorter name.",
      )

    outputs.forEach { prose ->
      val report = StandaloneReviewReportAdmission.admit("$prose\nverdict: approved", emptyResult(), false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertEquals("approved", report.verdict, prose)
    }
  }

  @Test
  fun `a prose report is admitted and presented as written`() {
    val prose =
      "I reviewed the six modified files against HEAD.\n\n" +
        "- `src/Gate.kt:124` [medium, correctness]: the marker list misses too-old Java output.\n" +
        "- `docs/policy.md:36` [low, docs-accuracy]: the doc overstates the check."
    val rawOutput = "$prose\n\nverdict: changes_requested"

    val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertEquals("$prose\nverdict: changes_requested", report.registerOutput)
  }

  @Test
  fun `a verdict line need not be last and repeated identical verdicts are one verdict`() {
    val outputs =
      listOf(
        "verdict: approved\nNO_FINDINGS",
        "NO_FINDINGS\nVerdict: Approved\nverdict: approved",
      )

    outputs.forEach { rawOutput ->
      val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

      assertTrue(report.admitted, rawOutput)
      assertEquals("approved", report.verdict)
    }
  }

  @Test
  fun `a report without one canonical verdict is rejected`() {
    val outputs =
      listOf(
        "",
        "Looks fine to me.",
        "verdict: approved\nverdict: changes_requested",
        "verdict: lgtm",
      )

    outputs.forEach { rawOutput ->
      val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

      assertFalse(report.admitted, rawOutput)
      assertEquals(null, report.verdict)
    }
  }

  @Test
  fun `a truncated report is rejected`() {
    val report = StandaloneReviewReportAdmission.admit("NO_FINDINGS\nverdict: approved", emptyResult(), true)

    assertFalse(report.admitted)
    assertTrue(report.rejectionReasons.any { "truncated" in it })
  }

  @Test
  fun `an approved report with a parsed Major finding presents changes requested`() {
    val rawOutput = "- [F-001] Major | High | src/Auth.kt:1 | broken authorization\nverdict: approved"

    val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
  }

  @Test
  fun `complete reports admit both verdicts with blank lines and trailing whitespace`() {
    val finding =
      ParallelReviewMergedFinding(
        "F-001",
        listOf("codex"),
        ParallelReviewSeverity.MAJOR,
        "High",
        "src/Auth.kt:1",
        "broken authorization",
      )
    val validOutputs =
      listOf(
        "\nNO_FINDINGS\n\nverdict: approved \n\t\n" to emptyList(),
        "\n- [F-001] Major | High | src/Auth.kt:1 | broken authorization\n\n" +
          "verdict: changes_requested \n\t\n" to listOf(finding),
      )

    validOutputs.forEach { (rawOutput, findings) ->
      val result =
        ParallelCodeReviewResult(
          mergeResult = ParallelReviewMergeResult(findings, ""),
          lane1 = ParallelReviewLaneStatus("codex", true),
        )

      val report = StandaloneReviewReportAdmission.admit(rawOutput, result, false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertEquals(findings, report.findings)
      assertEquals(rawOutput, report.rawOutput)
      assertEquals(if (findings.isEmpty()) "approved" else "changes_requested", report.verdict)
    }
  }

  @Test
  fun `a new integration Major completes with changes requested and preserves parent output`() {
    val parentOutput = "NO_FINDINGS\nverdict: approved"
    val integrationOutput = "- [F-001] Major | High | commits=first,second | src/Auth.kt:1 | broken authorization"
    val result =
      ParallelCodeReviewResult(
        mergeResult =
          ParallelReviewMergeResult(
            listOf(
              ParallelReviewMergedFinding(
                "F-001",
                listOf("integration"),
                ParallelReviewSeverity.MAJOR,
                "High",
                "src/Auth.kt:1",
                "broken authorization",
              ),
            ),
            "",
          ),
        lane1 = ParallelReviewLaneStatus("codex", true, reviewDisposition = ReviewLaneReviewDisposition.COMPLETE),
        coverage = ReviewCoverageReport(listOf("security"), emptyList(), true),
        integration =
          ReviewIntegrationPassOutcome(
            commitSequenceDigest = "sequence",
            terminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
            summarizedLaneCount = 1,
            findings =
              listOf(
                ParallelReviewRawFinding(
                  ParallelReviewSeverity.MAJOR,
                  "High",
                  "src/Auth.kt:1",
                  "broken authorization",
                  commitShas = listOf("first", "second"),
                ),
              ),
            rawOutput = integrationOutput,
          ),
      )

    val report = StandaloneReviewReportAdmission.admit(parentOutput, result, false, true)

    assertTrue(report.admitted)
    assertEquals("changes_requested", report.verdict)
    assertTrue(report.registerOutput.endsWith("verdict: changes_requested"))
    assertEquals(parentOutput, report.rawOutput)
    assertEquals(result.mergeResult.findings, report.findings)
  }

  @Test
  fun `a malformed finding identifier stays visible and cannot present as approved`() {
    val malformed = "- [F-ABC] Major | High | src/Auth.kt:1 | broken authorization"
    val rawOutput = "$malformed\nNO_FINDINGS\nverdict: approved"

    val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertTrue(malformed in report.registerOutput, report.registerOutput)
    assertEquals(rawOutput, report.rawOutput)
  }

  @Test
  fun `an approved parent cannot hide rejected integration findings`() {
    val parentOutput = "NO_FINDINGS\nverdict: approved"
    val result =
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(emptyList(), ""),
        lane1 = ParallelReviewLaneStatus("codex", true, reviewDisposition = ReviewLaneReviewDisposition.COMPLETE),
        coverage = ReviewCoverageReport(listOf("security"), emptyList(), true),
        integration =
          ReviewIntegrationPassOutcome(
            commitSequenceDigest = "sequence",
            terminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
            summarizedLaneCount = 1,
            rawOutput = "NO_FINDINGS",
          ),
      )
    assertTrue(StandaloneReviewReportAdmission.admit(parentOutput, result, false, true).admitted)
    val rejectedOutputs =
      listOf(
        "- [F-ABC] Major | High | commits=first,second | src/Auth.kt:1 | broken authorization",
        "- [F-001] Major | High | commits=first,second | src/Auth.kt:0 | broken authorization",
        "- [F-001] Major | High | commits=first,unknown | src/Auth.kt:1 | broken authorization",
      )
    rejectedOutputs.forEach { integrationOutput ->
      val incomplete = result.copy(integration = result.integration?.copy(rawOutput = integrationOutput))
      val report = StandaloneReviewReportAdmission.admit(parentOutput, incomplete, false, true)
      assertFalse(report.admitted, integrationOutput)
      assertTrue(report.rejectionReasons.any { "integration report" in it })
      assertEquals(integrationOutput, incomplete.integration?.rawOutput)
    }
  }

  private fun emptyResult(): ParallelCodeReviewResult =
    ParallelCodeReviewResult(
      mergeResult = ParallelReviewMergeResult(emptyList(), ""),
      lane1 = ParallelReviewLaneStatus("codex", true),
    )
}
