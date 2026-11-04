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
}
