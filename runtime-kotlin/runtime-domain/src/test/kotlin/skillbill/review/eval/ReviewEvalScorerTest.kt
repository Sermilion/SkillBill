package skillbill.review.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewEvalScorerTest {
  @Test
  fun `a finding a few lines off the expected line is a true positive inside the window`() {
    val expected =
      listOf(
        expectedFinding(
          id = "tp-auth",
          file = "Auth.kt",
          line = 12,
          lane = "bill-kmp-code-review-security",
        ),
      )
    val score =
      ReviewEvalScorer.score(
        cannedRegister(
          "- [F-001] Major | High | specialist=bill-kmp-code-review-security | Auth.kt:15 | Token is logged",
        ),
        expected,
      )

    assertEquals(listOf("tp-auth"), score.truePositives.map { it.expected.id })
    assertEquals(emptyList(), score.falsePositives)
    assertEquals(emptyList(), score.missedTruePositives)
    assertEquals(1.0, score.overall.precision)
    assertEquals(1.0, score.overall.recall)
    val lane = score.perLane.getValue("bill-kmp-code-review-security")
    assertEquals(1.0, lane.precision)
    assertEquals(1.0, lane.recall)
    assertEquals(false, score.partial)
  }

  @Test
  fun `a reported finding that matches a non_issue is a false positive`() {
    val expected =
      listOf(
        expectedFinding(
          id = "tp-auth",
          file = "Auth.kt",
          line = 12,
          lane = "bill-kmp-code-review-security",
        ),
        expectedFinding(
          id = "ni-widget",
          file = "Widget.kt",
          line = 40,
          lane = "bill-kmp-code-review-security",
          label = ReviewEvalExpectedLabel.NON_ISSUE,
        ),
      )
    val score =
      ReviewEvalScorer.score(
        cannedRegister(
          """
          - [F-001] Major | High | specialist=bill-kmp-code-review-security | Auth.kt:12 | Token is logged
          - [F-002] Minor | Low | specialist=bill-kmp-code-review-security | Widget.kt:40 | Unused import
          """.trimIndent(),
        ),
        expected,
      )

    assertEquals(listOf("tp-auth"), score.truePositives.map { it.expected.id })
    assertEquals(listOf("ni-widget"), score.falsePositives.map { it.expected.id })
    assertEquals(0.5, score.overall.precision)
    assertEquals(1.0, score.overall.recall)
    val lane = score.perLane.getValue("bill-kmp-code-review-security")
    assertEquals(0.5, lane.precision)
    assertEquals(1.0, lane.recall)
  }

  @Test
  fun `an unlabeled finding is excluded from true positives false positives and precision`() {
    val expected = listOf(expectedFinding(id = "tp-auth", file = "Auth.kt", line = 12, lane = "android"))
    val score =
      ReviewEvalScorer.score(
        cannedRegister(
          """
          - [F-001] Major | High | Auth.kt:12 | Token is logged
          - [F-002] Minor | Low | Extra.kt:99 | Naming is inconsistent
          """.trimIndent(),
        ),
        expected,
      )

    assertEquals(listOf("tp-auth"), score.truePositives.map { it.expected.id })
    assertEquals(emptyList(), score.falsePositives)
    assertEquals(listOf("F-002"), score.unlabeledFindings.map { it.findingId })
    assertEquals(1.0, score.overall.precision)
    assertEquals(1.0, score.overall.recall)
    assertEquals(1.0, score.perLane.getValue(REVIEW_EVAL_UNATTRIBUTED_LANE).precision)
    assertEquals(1.0, score.perLane.getValue("android").recall)
  }

  @Test
  fun `a reported line range that overlaps an expected line is a true positive`() {
    val expected =
      listOf(
        expectedFinding(
          id = "tp-auth",
          file = "Auth.kt",
          line = 17,
          lane = "bill-kmp-code-review-security",
        ),
      )
    val score =
      ReviewEvalScorer.score(
        cannedRegister(
          "- [F-001] Major | High | specialist=bill-kmp-code-review-security | Auth.kt:10-20 | Token is logged",
        ),
        expected,
      )

    assertEquals(listOf("tp-auth"), score.truePositives.map { it.expected.id })
    assertEquals(emptyList(), score.unlabeledFindings)
    assertEquals(emptyList(), score.missedTruePositives)
    assertEquals(1.0, score.overall.precision)
    assertEquals(1.0, score.overall.recall)
  }

  @Test
  fun `a duplicate expected id still lists the unmatched true positive as missed`() {
    val expected =
      listOf(
        expectedFinding(id = "tp-dup", file = "Auth.kt", line = 12, lane = "android"),
        expectedFinding(id = "tp-dup", file = "Widget.kt", line = 40, lane = "android"),
      )
    val score =
      ReviewEvalScorer.score(
        cannedRegister("- [F-001] Major | High | Auth.kt:12 | Token is logged"),
        expected,
      )

    assertEquals(listOf("tp-dup"), score.truePositives.map { it.expected.id })
    assertEquals(listOf("Widget.kt"), score.missedTruePositives.map { it.file })
    assertEquals(1.0, score.overall.precision)
    assertEquals(0.5, score.overall.recall)
  }

  @Test
  fun `prose without extractable finding locations yields curation and partial scores`() {
    val expected = listOf(expectedFinding(id = "tp-auth", file = "Auth.kt", line = 12, lane = "android"))
    val score =
      ReviewEvalScorer.score(
        "The login token is logged in plaintext during sign-in.",
        expected,
      )

    assertTrue(score.curationItems.isNotEmpty())
    assertEquals(true, score.partial)
    assertEquals(emptyList(), score.truePositives)
    assertEquals(listOf("tp-auth"), score.missedTruePositives.map { it.id })
    assertEquals(0.0, score.overall.precision)
    assertEquals(0.0, score.overall.recall)
  }

  private fun cannedRegister(findings: String): String =
    """
    Review run ID: rvw-eval-test
    Review session ID: rvs-eval-test
    $findings
    """.trimIndent()

  private fun expectedFinding(
    id: String,
    file: String,
    line: Int,
    lane: String,
    label: ReviewEvalExpectedLabel = ReviewEvalExpectedLabel.TRUE_POSITIVE,
  ): ReviewEvalExpectedFinding =
    ReviewEvalExpectedFinding(
      id = id,
      file = file,
      lineStart = line,
      lineEnd = line,
      lane = lane,
      label = label,
    )
}
