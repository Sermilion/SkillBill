package skillbill.engine.goalrunner.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalAssessmentLineParserTest {
  @Test
  fun `assessor projection parses findings and test values without a completion marker`() {
    val testValueLine =
      "Test value: src/HandlerTest.kt:28 guards src/Handler.kt:12 reason the test passes when callback invocation " +
        "is removed"
    val complete =
      listOf(
        "Finding: minor src/Handler.kt:12 scenario An empty input skips the callback",
        testValueLine,
      ).joinToString("\n")

    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(
        GoalAssessmentLineParser.parseAssessor(complete),
      )

    assertEquals(2, parsed.report.findings.size)
    assertEquals(1, parsed.report.findings.count { it.kind.wireValue == "test_value" })
    listOf(
      complete.replace(testValueLine, "Test value: malformed"),
      "The handler looks fine.",
    ).forEach { output ->
      assertIs<GoalAssessmentParse.Malformed>(GoalAssessmentLineParser.parseAssessor(output))
    }
  }

  @Test
  fun `assessor projection accepts a miscounted marker, finding none beside a test value and keyword prose`() {
    val output =
      """
      **Test value.** The precedence test at `tier.test.js:17` pairs a networking file with a translation.
      - **Finding content:** lane ids, severity, confidence, path and line.
      Finding: none
      Test value: `scripts/claude-code-review/tier.test.js:17` guards scripts/claude-code-review/tier.js:55 reason swapping the precedence still returns high-risk
      Scenario IS-001: holds evidence scripts/claude-code-review/tier.js:9, .github/workflows/claude-code-review.yml:100
      **Scenario IS-004:** holds evidence scripts/claude-code-review/tier.js:29
      Verify: none
      Projection complete: 5
      """.trimIndent()

    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(GoalAssessmentLineParser.parseAssessor(output))

    assertEquals("scripts/claude-code-review/tier.test.js", parsed.report.findings.single().path)
    assertEquals(listOf("IS-001", "IS-004"), parsed.report.scenarios.map { it.scenarioRef })
    assertEquals(emptyList(), parsed.report.rejectedLines)
  }

  @Test
  fun `assessor projection with only settled scenarios has no findings`() {
    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(
        GoalAssessmentLineParser.parseAssessor("Scenario IS-002: holds evidence src/Handler.kt:12"),
      )

    assertEquals(emptyList(), parsed.report.findings)
  }

  @Test
  fun `verifier projection rejects duplicates and missing evidence but not a miscounted marker`() {
    val verdict = "Verify GA-001: confirmed evidence src/Handler.kt:12 still skips the callback"

    listOf(verdict, "$verdict\nVerification complete: 2").forEach { output ->
      assertIs<GoalAssessmentParse.Parsed<GoalVerifierReport>>(GoalAssessmentLineParser.parseVerifier(output))
    }
    listOf(
      "$verdict\n$verdict",
      "Verify GA-001: confirmed evidence    ",
    ).forEach { output ->
      assertIs<GoalAssessmentParse.Malformed>(GoalAssessmentLineParser.parseVerifier(output))
    }
  }

  @Test
  fun `verifier projection ignores assessor records echoed after the verdicts`() {
    val verdicts =
      """
      All five candidates are settled: four are confirmed test-value gaps and one is refuted.

      - **GA-003 (refuted):** at head, the marker that `inlineBody` writes and the regex that reads it back agree.

      Verify GA-001: confirmed evidence case 1 at scripts/claude-code-review/post-findings.test.js:47 pairs `outcome: 'failure'` with `structuredOutput: ''`.
      Verify GA-002: confirmed evidence the lowercase signal `compose` (scripts/claude-code-review/lanes.js:46) still matches.
      Verify GA-003: refuted evidence `FINDING_MARKER_RX` matches the 12-character key (scripts/claude-code-review/post-findings.js:3).
      Verify GA-004: confirmed evidence `RequestSigner.kt` matches no `BOT_ONLY` pattern (scripts/claude-code-review/tier.js:28-36).
      Verify GA-005: confirmed evidence This duplicates GA-001 at scripts/claude-code-review/post-findings.test.js:49.
      """.trimIndent()
    val echoed =
      """
      Test value: scripts/claude-code-review/post-findings.test.js:47 guards scripts/claude-code-review/post-findings.js:20 reason every non-success outcome also has empty structured output
      Test value: scripts/claude-code-review/lanes.test.js:21 guards scripts/claude-code-review/lanes.js:158 reason the lowercase signal `compose` still matches
      Test value: scripts/claude-code-review/tier.test.js:17 guards scripts/claude-code-review/tier.js:55 reason the input contains a file that is not bot-only
      """.trimIndent()

    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalVerifierReport>>(
        GoalAssessmentLineParser.parseVerifier("$verdicts\n\n$echoed"),
      )

    val verdictsByRef = parsed.report.verdicts.associate { it.findingRef to it.verdict.wireValue }
    assertEquals(
      mapOf(
        "GA-001" to "confirmed",
        "GA-002" to "confirmed",
        "GA-003" to "refuted",
        "GA-004" to "confirmed",
        "GA-005" to "confirmed",
      ),
      verdictsByRef,
    )
    listOf(
      echoed,
      "$verdicts\nVerify GA-006: unresolved evidence scripts/claude-code-review/tier.js:55\n$echoed",
    ).forEach { output ->
      assertIs<GoalAssessmentParse.Malformed>(GoalAssessmentLineParser.parseVerifier(output))
    }
  }
}
