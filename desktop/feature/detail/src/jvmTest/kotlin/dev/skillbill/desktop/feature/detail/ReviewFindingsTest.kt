package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewFindingsTest {
  private fun finding(status: ReviewFindingStatus) = ReviewReportFinding("F-001", "Minor", null, null, null, "text", status)

  @Test
  fun `the tally counts every status present in status order`() {
    val findings = listOf(
      finding(ReviewFindingStatus.Declined),
      finding(ReviewFindingStatus.Fixed),
      finding(ReviewFindingStatus.Open),
      finding(ReviewFindingStatus.Fixed),
      finding(ReviewFindingStatus.FalsePositive),
    )

    assertEquals("5 findings · 1 open · 2 fixed · 1 declined · 1 false positive", reviewFindingsTally(findings))
    assertEquals("no findings", reviewFindingsTally(emptyList()))
  }

  @Test
  fun `a review report caption leads with the verdict and ends with the tally`() {
    val report = ReviewReport("changes_requested", null, listOf(finding(ReviewFindingStatus.Accepted)))

    assertEquals("Changes requested · 1 finding · 1 accepted", reviewReportCaption(report))
  }
}
