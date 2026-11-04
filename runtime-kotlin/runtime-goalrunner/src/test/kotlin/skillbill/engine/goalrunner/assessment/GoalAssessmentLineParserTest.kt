package skillbill.engine.goalrunner.assessment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoalAssessmentLineParserTest {
  @Test
  fun `assessor projection requires complete well-formed records and an exact count`() {
    val testValueLine =
      "Test value: src/HandlerTest.kt:28 guards src/Handler.kt:12 reason the test passes when callback invocation " +
        "is removed"
    val complete =
      listOf(
        "Finding: minor src/Handler.kt:12 scenario An empty input skips the callback",
        testValueLine,
        "Projection complete: 2",
      ).joinToString("\n")

    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(
        GoalAssessmentLineParser.parseAssessor(complete),
      )

    assertEquals(2, parsed.report.projectionCount)
    assertEquals(2, parsed.report.findings.size)
    assertEquals(1, parsed.report.findings.count { it.kind.wireValue == "test_value" })
    listOf(
      complete.replace("Projection complete: 2", "Projection complete: 1"),
      complete.replace("Projection complete: 2", ""),
      complete.replace(
        testValueLine,
        "Test value: malformed",
      ),
      "Finding: none\n" +
        "Finding: minor src/Handler.kt:12 scenario An empty input skips the callback\n" +
        "Projection complete: 1",
    ).forEach { output ->
      assertIs<GoalAssessmentParse.Malformed>(GoalAssessmentLineParser.parseAssessor(output))
    }
  }

  @Test
  fun `assessor projection ignores verifier lines an assessor echoed from the shared grammar`() {
    val output =
      """
      Finding: Minor .github/workflows/claude-code-review.yml:156 scenario A PR opened at 300 lines grows to 700 lines on a `synchronize` push and now runs the guide session.
      Test value: scripts/claude-code-review/tier.test.js:17 guards scripts/claude-code-review/tier.js:55 reason The test would still pass if tier.js:55 checked bot-only before high-risk.
      Scenario IS-001: holds evidence scripts/claude-code-review/tier.js:9, .github/workflows/claude-code-review.yml:100
      Scenario IS-002: holds evidence .github/workflows/claude-code-review.yml:216-219, :251, :290
      Scenario IS-003: holds evidence .github/workflows/claude-code-review.yml:236,280, scripts/claude-code-review/post-findings.js:36
      Scenario IS-004: holds evidence scripts/claude-code-review/tier.js:29,31, .github/workflows/claude-code-review.yml:157
      Verify: none
      Verification complete: 0
      Projection complete: 7
      """.trimIndent()

    val parsed =
      assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(GoalAssessmentLineParser.parseAssessor(output))

    assertEquals(2, parsed.report.findings.size)
    assertEquals(4, parsed.report.scenarios.size)
    assertEquals(emptyList(), parsed.report.rejectedLines)
    assertIs<GoalAssessmentParse.Parsed<GoalAssessorReport>>(
      GoalAssessmentLineParser.parseAssessor(output.replace("Projection complete: 7", "Projection complete: 6")),
    )
    assertIs<GoalAssessmentParse.Malformed>(
      GoalAssessmentLineParser.parseAssessor(output.replace("Projection complete: 7", "Projection complete: 8")),
    )
  }

  @Test
  fun `verifier projection rejects duplicates missing evidence and count mismatch`() {
    val verdict = "Verify GA-001: confirmed evidence src/Handler.kt:12 still skips the callback"

    assertIs<GoalAssessmentParse.Parsed<GoalVerifierReport>>(
      GoalAssessmentLineParser.parseVerifier("$verdict\nVerification complete: 1"),
    )
    listOf(
      "$verdict\n$verdict\nVerification complete: 2",
      "Verify GA-001: confirmed evidence    \nVerification complete: 1",
      "$verdict\nVerification complete: 2",
      verdict,
    ).forEach { output ->
      assertIs<GoalAssessmentParse.Malformed>(GoalAssessmentLineParser.parseVerifier(output))
    }
  }
}
