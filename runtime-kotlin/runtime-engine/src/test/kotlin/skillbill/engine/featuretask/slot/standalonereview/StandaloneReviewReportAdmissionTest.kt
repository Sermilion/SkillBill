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
import skillbill.review.model.ReviewLaneAggregationInput
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
  fun `a warning that the review could not be completed prevents approval`() {
    val warning = "warning: the review could not be completed"
    val report = StandaloneReviewReportAdmission.admit("NO_FINDINGS\nverdict: approved\n$warning", emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertTrue(report.hasIncompleteReviewWarning)
    assertEquals("changes_requested", report.verdict)
    assertTrue(warning in report.registerOutput)
  }

  @Test
  fun `scope warnings preserve incomplete coverage without treating a negated gap as incomplete`() {
    val warnings =
      listOf(
        "I couldn't open the diffs of the agent notes, so they are not covered.",
        "Some files were not reviewed.",
      )
    warnings.forEach { warning ->
      val report =
        StandaloneReviewReportAdmission.admit(
          "NO_FINDINGS\n$warning\nverdict: approved",
          emptyResult(),
          false,
        )
      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertTrue(report.hasIncompleteReviewWarning, warning)
      assertTrue(warning in report.warnings)
    }
    val complete =
      StandaloneReviewReportAdmission.admit(
        "NO_FINDINGS\nwarning: no files were omitted from the review\nverdict: approved",
        emptyResult(),
        false,
      )
    assertTrue(complete.admitted, complete.rejectionReasons.joinToString())
    assertFalse(complete.hasIncompleteReviewWarning)
  }

  @Test
  fun `coverage without an explicit gap waits for verifier classification`() {
    val output = "NO_FINDINGS\ncoverage: all changed files reviewed\nverdict: approved"

    val report = StandaloneReviewReportAdmission.admit(output, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("approved", report.verdict)
    assertFalse(report.hasIncompleteReviewWarning)
    assertTrue(report.warnings.isEmpty())
    assertEquals("coverage: all changed files reviewed", report.reviewWarnings.single().text)
    assertFalse(report.reviewWarnings.single().alreadyIncomplete)
    assertTrue("coverage: all changed files reviewed" in report.registerOutput)
  }

  @Test
  fun `delegated integration coverage without an explicit gap waits for verifier classification`() {
    val result =
      emptyResult().copy(
        lane1 = ParallelReviewLaneStatus("codex", true, reviewDisposition = ReviewLaneReviewDisposition.COMPLETE),
        coverage = ReviewCoverageReport(listOf("security"), emptyList(), true),
        integration =
          ReviewIntegrationPassOutcome(
            commitSequenceDigest = "sequence",
            terminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
            summarizedLaneCount = 1,
            rawOutput = "coverage: all changed files reviewed\nNO_FINDINGS",
          ),
      )

    val report = StandaloneReviewReportAdmission.admit("NO_FINDINGS\nverdict: approved", result, false, true)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("approved", report.verdict)
    assertFalse(report.hasIncompleteReviewWarning)
    assertTrue(report.warnings.isEmpty())
    val integrationWarning = report.reviewWarnings.single { it.source == "delegated integration report" }
    assertFalse(integrationWarning.alreadyIncomplete)
    assertTrue("coverage: all changed files reviewed" in report.registerOutput)
  }

  @Test
  fun `coverage continuations that state a gap remain incomplete`() {
    val coverage = "coverage: partial\nSome files were not reviewed."
    val report =
      StandaloneReviewReportAdmission.admit(
        "NO_FINDINGS\n$coverage\nverdict: approved",
        emptyResult(),
        false,
      )

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertTrue(report.hasIncompleteReviewWarning)
    assertEquals(coverage, report.reviewWarnings.single().text)
    assertTrue(report.reviewWarnings.single().alreadyIncomplete)
    assertTrue(coverage in report.warnings)
  }

  @Test
  fun `typed delegated coverage remains authoritative when lanes are incomplete`() {
    val result =
      emptyResult().copy(
        coverage =
          ReviewCoverageReport(
            cleanLanes = emptyList(),
            incompleteLanes =
              listOf(
                ReviewLaneAggregationInput(
                  lane = "security",
                  commitSequenceDigest = "sequence",
                  disposition = ReviewLaneReviewDisposition.INCOMPLETE,
                  unreviewedUnits = listOf("src/Auth.kt"),
                ),
              ),
            integrationCompleted = false,
          ),
      )

    val report = StandaloneReviewReportAdmission.admit("NO_FINDINGS\nverdict: approved", result, false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertTrue(report.hasIncompleteReviewWarning)
    assertTrue(report.reviewWarnings.single().alreadyIncomplete)
    assertTrue(report.coverage?.contains("Coverage: NOT clean") == true)
    assertTrue(report.registerOutput.contains("Coverage: NOT clean"), report.registerOutput)
  }

  @Test
  fun `prose that admits a gap or names a severe finding cannot present as approved`() {
    val outputs =
      listOf(
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
        "No findings.",
        "- `src/Gate.kt:124` [low, style]: consider a shorter name.",
      )

    outputs.forEach { prose ->
      val report = StandaloneReviewReportAdmission.admit("$prose\nverdict: approved", emptyResult(), false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      assertEquals("approved", report.verdict, prose)
    }
  }

  @Test
  fun `verified low prose findings approve while conservative controls keep requesting changes`() {
    val cases =
      listOf(
        "- src/Gate.kt:124 [low, style]: consider a shorter name" to "approved",
        "- src/Gate.kt:124: consider a shorter name\nSeverity: low" to "approved",
        "- src/Gate.kt:124 [major, correctness]: authorization check is missing" to "changes_requested",
        "- src/Gate.kt:124 [low, style]: consider a shorter name" to "changes_requested",
        "- src/Gate.kt:124 [low, style]: conflicting labels\nSeverity: major" to "changes_requested",
        "- src/Gate.kt:124 [major, confidence: low]: authorization check is missing" to "changes_requested",
      )

    cases.forEachIndexed { index, (findingText, expectedVerdict) ->
      val report = StandaloneReviewReportAdmission.admit("$findingText\nverdict: approved", emptyResult(), false)

      assertTrue(report.admitted, report.rejectionReasons.joinToString())
      val disposition = if (index == 3) "unresolved" else "confirmed"
      assertEquals(
        expectedVerdict,
        verifiedFindingVerdict(report, mapOf("F-001" to disposition), emptyMap()),
        findingText,
      )
    }
  }

  @Test
  fun `a partial clean statement cannot establish an empty census`() {
    val report =
      StandaloneReviewReportAdmission.admit(
        "No high-severity issues found; the change is small and well tested.\nverdict: approved",
        emptyResult(),
        false,
      )
    assertFalse(report.admitted)
  }

  @Test
  fun `a prose report retains each finding in the census`() {
    val prose =
      "I reviewed.\n\n" +
        "- `src/Gate.kt:124` [medium, correctness]: the marker list misses too-old Java output.\n" +
        "- `docs/policy.md:36` [low, docs-accuracy]: the doc overstates the check."
    val rawOutput = "$prose\n\nverdict: changes_requested"

    val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertEquals(2, report.census.size)
    assertTrue(report.census.any { "the marker list misses too-old Java output" in it.text })
    assertTrue(report.census.any { "the doc overstates the check" in it.text })
  }

  @Test
  fun `a summary paragraph before structured findings is admitted as context`() {
    val rawOutput =
      "lanes: kotlin-architecture, kotlin-testing\n\n" +
        "I reviewed PR #3175 against `main`. The core change is sound and paging continues as before.\n\n" +
        "- [F-001] Minor | High | src/Writer.kt:35 | the default argument lets the refresh path skip the contract\n" +
        "- [F-002] Minor | High | src/WriterTest.kt:301 | the fixture is written out inline twice\n\n" +
        "verdict: changes_requested"

    val report = StandaloneReviewReportAdmission.admit(rawOutput, emptyResult(), false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("changes_requested", report.verdict)
    assertEquals(2, report.census.size)
    assertTrue(report.census.none { "The core change is sound" in it.text })
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
  fun `a merged identity collision retains unmerged findings with distinct identities`() {
    val rawOutput =
      "- [F-001] Major | High | src/Auth.kt:1 | broken authorization\n" +
        "- [F-002] Minor | High | src/Style.kt:8 | simplify this expression\n" +
        "verdict: changes_requested"
    val merged =
      ParallelReviewMergedFinding(
        "F-002",
        listOf("codex"),
        ParallelReviewSeverity.MAJOR,
        "High",
        "src/Auth.kt:1",
        "broken authorization",
      )
    val result = emptyResult().copy(mergeResult = ParallelReviewMergeResult(listOf(merged), ""))

    val report = StandaloneReviewReportAdmission.admit(rawOutput, result, false)

    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals("F-002", report.findingGroups.first().identity)
    assertEquals(2, report.findingGroups.map { it.identity }.toSet().size)
    assertEquals(report.census, report.findingGroups.flatMap { it.members })
    assertEquals(merged, report.findingGroups.first().finding)
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

    assertFalse(report.admitted, report.rejectionReasons.joinToString())
    assertTrue(report.rejectionReasons.any { "unambiguously" in it || "malformed" in it })
    assertEquals(rawOutput, report.rawOutput)
  }

  @Test
  fun `census retains prose and integration findings and keeps a clean census empty`() {
    val prose = "- `src/Style.kt:8` [low, style]: simplify this expression."
    val integration =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MINOR,
        "Low",
        "src/Integration.kt:12",
        "integration-only issue",
      )
    val result =
      ParallelCodeReviewResult(
        mergeResult = ParallelReviewMergeResult(emptyList(), ""),
        lane1 = ParallelReviewLaneStatus("codex", true, reviewDisposition = ReviewLaneReviewDisposition.COMPLETE),
        coverage = ReviewCoverageReport(listOf("style"), emptyList(), true),
        integration =
          ReviewIntegrationPassOutcome(
            commitSequenceDigest = "sequence",
            terminalOutcome = ReviewIntegrationTerminalOutcome.COMPLETED,
            summarizedLaneCount = 1,
            findings = listOf(integration),
            rawOutput = "- [F-001] Minor | Low | src/Integration.kt:12 | integration-only issue",
          ),
      )

    val report = StandaloneReviewReportAdmission.admit("$prose\nverdict: approved", result, false, true)
    assertTrue(report.admitted, report.rejectionReasons.joinToString())
    assertEquals(2, report.census.size)
    assertTrue(report.census.any { prose in it.text })
    assertTrue(report.census.any { "integration-only issue" in it.text })
    assertEquals(
      emptyList(),
      StandaloneReviewReportAdmission.admit("NO_FINDINGS\nverdict: approved", emptyResult(), false).census,
    )
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
