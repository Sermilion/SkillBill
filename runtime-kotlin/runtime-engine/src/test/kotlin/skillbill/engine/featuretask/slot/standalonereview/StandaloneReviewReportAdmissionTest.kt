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
  fun `an incomplete review warning cannot be discarded to approve a report`() {
    val result =
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(emptyList(), ""),
        lane1 = ParallelReviewLaneStatus("codex", true),
      )
    val warning = "Review incomplete: could not inspect the diff"
    val invalidOutputs =
      listOf(
        "NO_FINDINGS\nverdict: approved\n$warning",
        "$warning\nNO_FINDINGS\nverdict: approved",
        "NO_FINDINGS\n$warning\nverdict: approved",
        "verdict: approved\nNO_FINDINGS",
      )

    invalidOutputs.forEach { rawOutput ->
      val report = StandaloneReviewReportAdmission.admit(rawOutput, result, false)

      assertFalse(report.admitted, rawOutput)
      assertTrue(report.rejectionReasons.isNotEmpty(), rawOutput)
      assertEquals(rawOutput, report.rawOutput)
    }
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
  fun `a malformed finding identifier cannot become an empty approval`() {
    val rawOutput = "- [F-ABC] Major | High | src/Auth.kt:1 | broken authorization\nNO_FINDINGS\nverdict: approved"
    val result =
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(emptyList(), ""),
        lane1 = ParallelReviewLaneStatus("codex", true),
      )

    val report = StandaloneReviewReportAdmission.admit(rawOutput, result, false)

    assertFalse(report.admitted)
    assertTrue(report.rejectionReasons.any { "malformed finding identifier" in it })
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
}
