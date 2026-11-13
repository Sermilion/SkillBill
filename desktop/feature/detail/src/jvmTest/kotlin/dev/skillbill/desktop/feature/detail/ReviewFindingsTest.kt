package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewReport
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
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

    assertEquals("5 findings · 2 fixed · 1 open · 1 declined · 1 false positive", reviewFindingsTally(findings))
    assertEquals("no findings", reviewFindingsTally(emptyList()))
  }

  @Test
  fun `a review report caption leads with the verdict and ends with the tally`() {
    val report = ReviewReport("changes_requested", null, listOf(finding(ReviewFindingStatus.Accepted)))

    assertEquals("Changes requested · 1 finding · 1 accepted", reviewReportCaption(report))
  }

  @Test
  fun `a recorded lane is not painted clean while a completed finding has no specialist`() = runComposeUiTest {
    val findings = mutableStateOf(listOf(finding(ReviewFindingStatus.Open).copy(specialist = " ")))
    var clean = Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        clean = SkillBillTheme.colors.stDone
        ReviewLanes(listOf("bill-kotlin-code-review-testing"), findings.value)
      }
    }
    fun hasCleanDot(): Boolean {
      val pixels = onRoot().captureToImage().toPixelMap()
      return (0 until pixels.width).any { x -> (0 until pixels.height).any { y -> pixels[x, y] == clean } }
    }
    assertFalse(hasCleanDot())
    runOnIdle { findings.value = emptyList() }
    assertTrue(hasCleanDot())
  }

  @Test
  fun `findings without a specialist do not complete lane attribution`() {
    val sparse = listOf(finding(ReviewFindingStatus.Open))
    val mixed = listOf(
      finding(ReviewFindingStatus.Open).copy(specialist = "bill-kotlin-code-review-testing"),
      finding(ReviewFindingStatus.Open),
    )

    assertEquals(true, reviewFindingsAttributionComplete(emptyList()))
    assertEquals(false, reviewFindingsAttributionComplete(sparse))
    assertEquals(false, reviewFindingsAttributionComplete(mixed))
    assertEquals(emptyMap(), reviewAttributedFindingsPerLane(sparse))
    assertEquals(mapOf("bill-kotlin-code-review-testing" to 1), reviewAttributedFindingsPerLane(mixed))
  }
}
