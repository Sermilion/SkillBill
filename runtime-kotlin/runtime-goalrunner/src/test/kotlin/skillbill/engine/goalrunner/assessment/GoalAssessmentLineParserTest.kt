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
