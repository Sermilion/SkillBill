package skillbill.engine.work

import skillbill.ports.idestatus.model.StandaloneReviewReportFinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StandaloneReviewReportReaderTest {
  @Test
  fun `a prose report keeps its summary and lists each lane finding with its location`() {
    val report =
      StandaloneReviewReportReader.read(
        """
        The review is done. I found two minor issues and nothing blocking.

        **Findings:**

        Minor | bill-kotlin-code-review-testing | feature/ui/Flow.kt:180 | Nothing tests the resume sync.

        Minor | bill-kmp-code-review-code-quality | feature/ui/State.kt:64 | The dialog compares message strings | twice.
        verdict: approved
        """.trimIndent(),
      )!!

    assertEquals("approved", report.verdict)
    assertEquals("The review is done. I found two minor issues and nothing blocking.", report.summary)
    assertEquals(
      listOf(
        StandaloneReviewReportFinding(
          "F-001",
          "Minor",
          null,
          "bill-kotlin-code-review-testing",
          "feature/ui/Flow.kt:180",
          "Nothing tests the resume sync.",
        ),
        StandaloneReviewReportFinding(
          "F-002",
          "Minor",
          null,
          "bill-kmp-code-review-code-quality",
          "feature/ui/State.kt:64",
          "The dialog compares message strings | twice.",
        ),
      ),
      report.findings,
    )
  }

  @Test
  fun `a register line keeps its id, confidence, specialist and structured path`() {
    val report =
      StandaloneReviewReportReader.read(
        """
        - [F-007] Major | High | specialist=bill-kotlin-code-review-security[paths=a] | path="src/A.kt" | line=9 | Token logged.
        verdict: changes_requested
        """.trimIndent(),
      )!!

    assertEquals("changes_requested", report.verdict)
    assertNull(report.summary)
    assertEquals(
      listOf(
        StandaloneReviewReportFinding(
          "F-007",
          "Major",
          "high",
          "bill-kotlin-code-review-security",
          "src/A.kt:9",
          "Token logged.",
        ),
      ),
      report.findings,
    )
  }

  @Test
  fun `a sentence that only starts with a severity word stays in the summary`() {
    val report = StandaloneReviewReportReader.read("Minor | wording only\nNO_FINDINGS\nverdict: approved")!!

    assertEquals(emptyList(), report.findings)
    assertEquals("Minor | wording only", report.summary)
  }
}
